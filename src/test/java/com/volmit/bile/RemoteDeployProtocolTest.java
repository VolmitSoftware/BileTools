package com.volmit.bile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RemoteDeployProtocolTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void immutableStageTransfersUnderAuthoritativeJarName() throws Exception {
        File staged = temporaryFolder.newFile("17-random-stage.jar");
        byte[] payload = "immutable plugin bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(staged.toPath(), payload);
        File pluginsDirectory = temporaryFolder.newFolder("plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0)) {
            Future<File> received = receiverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    return RemoteDeployProtocol.receiveFile(socket, "secret", pluginsDirectory, 1_024L * 1_024L);
                }
            });

            RemoteDeployProtocol.streamFile(
                    staged,
                    "Demo.jar",
                    "127.0.0.1",
                    server.getLocalPort(),
                    "secret",
                    5_000,
                    1_024L * 1_024L);

            File installed = received.get(5L, TimeUnit.SECONDS);
            assertEquals("Demo.jar", installed.getName());
            assertArrayEquals(payload, Files.readAllBytes(installed.toPath()));
            assertFalse(new File(pluginsDirectory, "Demo.jar.part").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }

    @Test
    public void rejectsTemporaryAuthoritativeNameBeforeConnecting() throws Exception {
        File staged = temporaryFolder.newFile("17-random-stage.jar");

        IOException failure = assertThrows(IOException.class, () -> RemoteDeployProtocol.streamFile(
                staged,
                "Demo.jar.part",
                "127.0.0.1",
                1,
                "secret",
                1_000,
                1_024L));

        assertEquals("Transfer file must be a .jar: Demo.jar.part", failure.getMessage());
    }

    @Test
    public void truncatedTransferRemovesPartialFile() throws Exception {
        File pluginsDirectory = temporaryFolder.newFolder("truncated-plugins");
        ExecutorService receiverExecutor = Executors.newSingleThreadExecutor();

        try (ServerSocket server = new ServerSocket(0);
             Socket sender = new Socket("127.0.0.1", server.getLocalPort())) {
            Future<File> received = receiverExecutor.submit(() -> {
                try (Socket socket = server.accept()) {
                    return RemoteDeployProtocol.receiveFile(socket, "secret", pluginsDirectory, 1_024L);
                }
            });

            try (DataOutputStream output = new DataOutputStream(sender.getOutputStream())) {
                output.writeInt(RemoteDeployProtocol.MAGIC);
                output.writeByte(RemoteDeployProtocol.VERSION);
                output.writeUTF("secret");
                output.writeUTF("Demo.jar");
                output.writeLong(5L);
                output.write(new byte[RemoteDeployProtocol.SHA256_BYTES]);
                output.write(new byte[]{1, 2});
            }

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> received.get(5L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
            assertFalse(new File(pluginsDirectory, "Demo.jar.part").exists());
            assertFalse(new File(pluginsDirectory, "Demo.jar").exists());
        } finally {
            receiverExecutor.shutdownNow();
        }
    }
}
