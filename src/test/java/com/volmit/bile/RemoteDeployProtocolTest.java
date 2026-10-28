package com.volmit.bile;

import art.arcane.volmlib.util.crypto.Ed25519;
import art.arcane.volmlib.util.crypto.Ed25519Tls;
import art.arcane.volmlib.util.network.IpRange;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RemoteDeployProtocolTest {
    private static final long MAX_BYTES = 1_024L * 1_024L;

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void mutuallyTrustedTransferInstallsUnderAuthoritativeJarName() throws Exception {
        KeyPair master = Ed25519.generateKeyPair();
        KeyPair receiver = Ed25519.generateKeyPair();
        File staged = temporaryFolder.newFile("17-random-stage.jar");
        byte[] payload = "immutable plugin bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(staged.toPath(), payload);
        File pluginsDirectory = temporaryFolder.newFolder("plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receive(receiverExecutor, server, tls(receiver, master), pluginsDirectory);

            RemoteDeployProtocol.streamFile(staged, "Demo.jar", target(server), tls(master, receiver), 5_000, MAX_BYTES);

            File installed = received.get(5L, TimeUnit.SECONDS);
            assertEquals("Demo.jar", installed.getName());
            assertArrayEquals(payload, Files.readAllBytes(installed.toPath()));
            assertFalse(new File(pluginsDirectory, "Demo.jar.part").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void receiverRefusesAnUntrustedMaster() throws Exception {
        KeyPair master = Ed25519.generateKeyPair();
        KeyPair receiver = Ed25519.generateKeyPair();
        KeyPair otherMaster = Ed25519.generateKeyPair();
        File staged = temporaryFolder.newFile("stage.jar");
        Files.write(staged.toPath(), new byte[]{1, 2, 3});
        File pluginsDirectory = temporaryFolder.newFolder("untrusted-plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receive(receiverExecutor, server, tls(receiver, otherMaster), pluginsDirectory);

            IOException failure = assertThrows(IOException.class, () -> RemoteDeployProtocol.streamFile(
                    staged, "Demo.jar", target(server), tls(master, receiver), 5_000, MAX_BYTES));

            assertTrue(failure.getMessage(), failure.getMessage().contains("trusted-keys"));
            IOException receiverFailure = assertReceiverFailed(received);
            assertTrue(receiverFailure.getMessage(), receiverFailure.getMessage().contains(Ed25519.fingerprint(master.getPublic())));
            assertFalse(new File(pluginsDirectory, "Demo.jar").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void masterRefusesAnUntrustedReceiverBeforeSendingTheJar() throws Exception {
        KeyPair master = Ed25519.generateKeyPair();
        KeyPair receiver = Ed25519.generateKeyPair();
        KeyPair impostor = Ed25519.generateKeyPair();
        File staged = temporaryFolder.newFile("stage.jar");
        Files.write(staged.toPath(), new byte[]{1, 2, 3});
        File pluginsDirectory = temporaryFolder.newFolder("impostor-plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receive(receiverExecutor, server, tls(impostor, master), pluginsDirectory);

            IOException failure = assertThrows(IOException.class, () -> RemoteDeployProtocol.streamFile(
                    staged, "Demo.jar", target(server), tls(master, receiver), 5_000, MAX_BYTES));

            assertTrue(failure.getMessage(), failure.getMessage().contains(Ed25519.fingerprint(impostor.getPublic())));
            assertReceiverFailed(received);
            assertFalse(new File(pluginsDirectory, "Demo.jar").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void payloadThatDoesNotMatchTheAnnouncedDigestIsDiscarded() throws Exception {
        KeyPair master = Ed25519.generateKeyPair();
        KeyPair receiver = Ed25519.generateKeyPair();
        File pluginsDirectory = temporaryFolder.newFolder("tampered-plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();
        byte[] announcedPayload = {1, 2, 3};

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receive(receiverExecutor, server, tls(receiver, master), pluginsDirectory);

            try (SSLSocket sender = connect(server, tls(master, receiver))) {
                DataInputStream input = new DataInputStream(sender.getInputStream());
                DataOutputStream output = new DataOutputStream(sender.getOutputStream());
                writeHeader(input, output, announcedPayload.length, MessageDigest.getInstance("SHA-256").digest(announcedPayload));
                assertEquals(RemoteDeployProtocol.STATUS_ACCEPTED, input.readByte());
                output.write(new byte[]{9, 9, 9});
                output.flush();

                assertEquals(RemoteDeployProtocol.STATUS_REJECTED, input.readByte());
                assertTrue(input.readUTF().contains("SHA-256 mismatch"));
            }
            assertReceiverFailed(received);
            assertFalse(new File(pluginsDirectory, "Demo.jar.part").exists());
            assertFalse(new File(pluginsDirectory, "Demo.jar").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void truncatedTransferRemovesPartialFile() throws Exception {
        KeyPair master = Ed25519.generateKeyPair();
        KeyPair receiver = Ed25519.generateKeyPair();
        File pluginsDirectory = temporaryFolder.newFolder("truncated-plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receive(receiverExecutor, server, tls(receiver, master), pluginsDirectory);

            try (SSLSocket sender = connect(server, tls(master, receiver))) {
                DataInputStream input = new DataInputStream(sender.getInputStream());
                DataOutputStream output = new DataOutputStream(sender.getOutputStream());
                writeHeader(input, output, 5L, new byte[RemoteDeployProtocol.SHA256_BYTES]);
                assertEquals(RemoteDeployProtocol.STATUS_ACCEPTED, input.readByte());
                output.write(new byte[]{1, 2});
                output.flush();
            }

            assertReceiverFailed(received);
            assertFalse(new File(pluginsDirectory, "Demo.jar.part").exists());
            assertFalse(new File(pluginsDirectory, "Demo.jar").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void rejectsTemporaryAuthoritativeNameBeforeConnecting() throws Exception {
        File staged = temporaryFolder.newFile("17-random-stage.jar");
        KeyPair master = Ed25519.generateKeyPair();

        IOException failure = assertThrows(IOException.class, () -> RemoteDeployProtocol.streamFile(
                staged, "Demo.jar.part", new RemoteDeployTarget("127.0.0.1", 1),
                tls(master, master), 1_000, 1_024L));

        assertEquals("Transfer file must be a .jar: Demo.jar.part", failure.getMessage());
    }

    @Test
    public void trustedKeyListsMustBeNonEmptyAndValid() {
        IOException empty = assertThrows(IOException.class,
                () -> RemoteDeployProtocol.decodeTrustedKeys(SlaveBileServer.TRUSTED_KEYS_PATH, List.of()));
        IOException invalid = assertThrows(IOException.class,
                () -> RemoteDeployProtocol.decodeTrustedKeys("remote-deploy.master.trusted-keys", List.of("not-a-key")));

        assertTrue(empty.getMessage().contains("remote-deploy.slave.trusted-keys is empty"));
        assertTrue(invalid.getMessage().contains("remote-deploy.master.trusted-keys entry 1"));
    }

    @Test
    public void allowedAddressesAdmitEveryoneWhenEmptyAndOnlyListedRangesOtherwise() throws Exception {
        List<IpRange> ranges = SlaveBileServer.parseAllowedAddresses(List.of("10.0.0.0/8", "192.168.1.5"));

        assertTrue(SlaveBileServer.isAllowed(List.of(), IpRange.parseLiteral("203.0.113.9")));
        assertTrue(SlaveBileServer.isAllowed(ranges, IpRange.parseLiteral("10.4.5.6")));
        assertTrue(SlaveBileServer.isAllowed(ranges, IpRange.parseLiteral("192.168.1.5")));
        assertFalse(SlaveBileServer.isAllowed(ranges, IpRange.parseLiteral("192.168.1.6")));
        IOException invalid = assertThrows(IOException.class,
                () -> SlaveBileServer.parseAllowedAddresses(List.of("10.0.0.0/8", "play.example.org")));
        assertTrue(invalid.getMessage().contains("entry 2"));
    }

    @Test
    public void parsesHostPortTargets() {
        assertEquals(new RemoteDeployTarget("play.example.org", 9876), RemoteDeployTarget.parse(" play.example.org:9876 "));
        assertEquals(new RemoteDeployTarget("::1", 9876), RemoteDeployTarget.parse("[::1]:9876"));
        assertEquals("[::1]:9876", new RemoteDeployTarget("::1", 9876).toString());
        assertThrows(IllegalArgumentException.class, () -> RemoteDeployTarget.parse("play.example.org:9876:extra"));
        assertThrows(IllegalArgumentException.class, () -> RemoteDeployTarget.parse("play.example.org"));
        assertThrows(IllegalArgumentException.class, () -> RemoteDeployTarget.parse("play.example.org:0"));
    }

    private static SSLContext tls(KeyPair identity, KeyPair trustedPeer) throws IOException {
        return RemoteDeployProtocol.tlsContext(identity, List.of(trustedPeer.getPublic()));
    }

    private static Future<File> receive(ExecutorService executor,
                                        ServerSocket server,
                                        SSLContext tls,
                                        File pluginsDirectory) {
        return executor.submit(() -> {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5_000);
                try (SSLSocket secured = RemoteDeployProtocol.acceptHandshake(tls, socket)) {
                    return RemoteDeployProtocol.receiveFile(secured, pluginsDirectory, MAX_BYTES);
                }
            }
        });
    }

    private static SSLSocket connect(ServerSocket server, SSLContext tls) throws IOException {
        Socket raw = new Socket("127.0.0.1", server.getLocalPort());
        raw.setSoTimeout(5_000);
        return Ed25519Tls.clientSocket(tls, raw, "127.0.0.1", server.getLocalPort());
    }

    private static RemoteDeployTarget target(ServerSocket server) {
        return new RemoteDeployTarget("127.0.0.1", server.getLocalPort());
    }

    private static void writeHeader(DataInputStream input, DataOutputStream output, long length, byte[] digest) throws IOException {
        assertEquals(RemoteDeployProtocol.MAGIC, input.readInt());
        assertEquals(RemoteDeployProtocol.VERSION, input.readByte());
        output.writeInt(RemoteDeployProtocol.MAGIC);
        output.writeByte(RemoteDeployProtocol.VERSION);
        output.writeUTF("Demo.jar");
        output.writeLong(length);
        output.write(digest);
        output.flush();
    }

    private static IOException assertReceiverFailed(Future<File> received) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> received.get(5L, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof IOException);
        return (IOException) failure.getCause();
    }
}
