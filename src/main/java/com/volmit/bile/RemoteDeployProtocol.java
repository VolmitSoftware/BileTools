package com.volmit.bile;

import art.arcane.volmlib.util.crypto.Ed25519;
import art.arcane.volmlib.util.crypto.Ed25519Tls;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Framed remote jar transfer protocol used by master deploy and slave receive.
 *
 * Both servers authenticate inside a mutual TLS 1.3 handshake with their Ed25519 keys, each
 * side accepting only the peer keys it trusts. The exchange then runs inside the encrypted
 * channel:
 * <pre>
 * slave  -> master: magic (int) | version (byte)
 * master -> slave:  magic (int) | version (byte) | fileName (UTF) | length (long) | sha256 (32 bytes)
 * slave  -> master: status (byte) [| reason (UTF)]
 * master -> slave:  payload (length bytes)
 * slave  -> master: status (byte) [| reason (UTF)]
 * </pre>
 */
public final class RemoteDeployProtocol {
    public static final int MAGIC = 0xB11EB11E;
    public static final byte VERSION = 3;
    public static final int SHA256_BYTES = 32;
    public static final byte STATUS_ACCEPTED = 0;
    public static final byte STATUS_REJECTED = 1;
    private static final String CERTIFICATE_NAME = "BileTools";

    private RemoteDeployProtocol() {
    }

    public static SSLContext tlsContext(KeyPair identity, Collection<PublicKey> trustedPeers) throws IOException {
        requireEd25519();
        try {
            return Ed25519Tls.context(identity, CERTIFICATE_NAME, trustedPeers);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not create the remote deploy TLS context", e);
        }
    }

    public static void streamFile(File file,
                                  String transferFileName,
                                  RemoteDeployTarget target,
                                  SSLContext tls,
                                  int timeoutMs,
                                  long maxBytes) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("Source file does not exist: " + file);
        }

        long length = file.length();
        if (length < 0 || length > maxBytes) {
            throw new IOException("File exceeds max transfer size (" + length + " > " + maxBytes + "): " + file.getName());
        }

        String safeName = sanitizeJarFileName(transferFileName);
        byte[] digest = sha256OfFile(file);

        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(target.host(), target.port()), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            raw.setTcpNoDelay(true);
        } catch (IOException e) {
            raw.close();
            throw e;
        }

        try (SSLSocket socket = clientHandshake(tls, raw, target)) {
            sendOverTls(socket, file, safeName, length, digest);
        } catch (SSLException e) {
            throw trustFailure(e);
        }
    }

    private static IOException trustFailure(IOException cause) {
        return new IOException("TLS setup with the receiver failed: " + cause.getMessage()
                + ". Check that the receiver lists this server's key in remote-deploy.slave.trusted-keys"
                + " and that the receiver's key is in remote-deploy.master.trusted-keys", cause);
    }

    private static void sendOverTls(SSLSocket socket, File file, String safeName, long length, byte[] digest) throws IOException {
        DataInputStream din = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        int magic;
        byte version;
        try {
            magic = din.readInt();
            version = din.readByte();
        } catch (IOException e) {
            throw trustFailure(e);
        }
        checkPreamble(magic, version);
        dos.writeInt(MAGIC);
        dos.writeByte(VERSION);
        dos.writeUTF(safeName);
        dos.writeLong(length);
        dos.write(digest);
        dos.flush();
        readStatus(din);

        try (FileInputStream fin = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            long remaining = length;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = fin.read(buffer, 0, toRead);
                if (read < 0) {
                    throw new IOException("Unexpected EOF while streaming " + safeName);
                }
                dos.write(buffer, 0, read);
                remaining -= read;
            }
        }
        dos.flush();
        readStatus(din);
    }

    public static SSLSocket acceptHandshake(SSLContext tls, Socket accepted) throws IOException {
        try {
            return Ed25519Tls.serverSocket(tls, accepted);
        } catch (IOException e) {
            throw new IOException("Remote deploy TLS handshake failed: " + e.getMessage()
                    + ". Check that the master's key is in remote-deploy.slave.trusted-keys"
                    + " and that the master lists this server's key in remote-deploy.master.trusted-keys", e);
        }
    }

    public static File receiveFile(SSLSocket client, File pluginsFolder, long maxBytes) throws IOException {
        if (client == null) {
            throw new IOException("Client socket is null");
        }
        if (pluginsFolder == null) {
            throw new IOException("Plugins folder is null");
        }

        DataInputStream din = new DataInputStream(new BufferedInputStream(client.getInputStream()));
        DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(client.getOutputStream()));
        dos.writeInt(MAGIC);
        dos.writeByte(VERSION);
        dos.flush();
        try {
            File target = receiveHeaderAndPayload(din, dos, pluginsFolder, maxBytes);
            writeStatus(dos, STATUS_ACCEPTED, null);
            return target;
        } catch (IOException e) {
            reject(dos, e.getMessage());
            throw e;
        }
    }

    public static List<PublicKey> decodeTrustedKeys(String settingPath, List<String> encodedKeys) throws IOException {
        requireEd25519();
        if (encodedKeys.isEmpty()) {
            throw new IOException(settingPath + " is empty; add the public key of each server to trust");
        }
        List<PublicKey> keys = new ArrayList<>(encodedKeys.size());
        for (int index = 0; index < encodedKeys.size(); index++) {
            try {
                keys.add(Ed25519.decodePublicKeyText(encodedKeys.get(index)));
            } catch (GeneralSecurityException e) {
                throw new IOException(settingPath + " entry " + (index + 1) + " is not a valid Ed25519 public key", e);
            }
        }
        return List.copyOf(keys);
    }

    public static void requireEd25519() throws IOException {
        if (!Ed25519.isAvailable()) {
            throw new IOException("Ed25519 is unavailable in this Java runtime; remote deploy needs the jdk.crypto.ec module");
        }
    }

    public static String sanitizeJarFileName(String rawName) throws IOException {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IOException("Empty transfer file name");
        }

        String name = rawName.trim().replace('\\', '/');
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(File.separatorChar));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }

        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("..")) {
            throw new IOException("Illegal transfer file name: " + rawName);
        }

        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("Transfer file must be a .jar: " + name);
        }

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                throw new IOException("Illegal character in transfer file name: " + name);
            }
        }

        return name;
    }

    private static SSLSocket clientHandshake(SSLContext tls, Socket raw, RemoteDeployTarget target) throws IOException {
        try {
            return Ed25519Tls.clientSocket(tls, raw, target.host(), target.port());
        } catch (IOException e) {
            raw.close();
            throw trustFailure(e);
        }
    }

    private static File receiveHeaderAndPayload(DataInputStream din,
                                                DataOutputStream dos,
                                                File pluginsFolder,
                                                long maxBytes) throws IOException {
        readPreamble(din);
        String rawName = din.readUTF();
        long length = din.readLong();
        byte[] expectedDigest = readExactly(din, SHA256_BYTES, "SHA-256 digest");

        String fileName = sanitizeJarFileName(rawName);
        if (length < 0 || length > maxBytes) {
            throw new IOException("Remote transfer size rejected: " + length);
        }

        File target = new File(pluginsFolder, fileName).getCanonicalFile();
        File parent = pluginsFolder.getCanonicalFile();
        if (!target.getPath().startsWith(parent.getPath() + File.separator) && !target.equals(parent)) {
            throw new IOException("Resolved transfer path escapes plugins folder: " + fileName);
        }

        writeStatus(dos, STATUS_ACCEPTED, null);
        receivePayload(din, pluginsFolder, fileName, target, length, expectedDigest);
        return target;
    }

    private static void receivePayload(DataInputStream din,
                                       File pluginsFolder,
                                       String fileName,
                                       File target,
                                       long length,
                                       byte[] expectedDigest) throws IOException {
        File part = new File(pluginsFolder, fileName + ".part");
        boolean installed = false;
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            }

            try (FileOutputStream fos = new FileOutputStream(part)) {
                byte[] buffer = new byte[8192];
                long remaining = length;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = din.read(buffer, 0, toRead);
                    if (read < 0) {
                        throw new IOException("Unexpected EOF receiving " + fileName);
                    }
                    fos.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    remaining -= read;
                }
                fos.flush();
            }

            if (!MessageDigest.isEqual(expectedDigest, digest.digest())) {
                throw new IOException("SHA-256 mismatch for " + fileName);
            }

            Path targetPath = target.toPath();
            try {
                Files.move(part.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(part.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            installed = true;
        } finally {
            if (!installed) {
                Files.deleteIfExists(part.toPath());
            }
        }
    }

    private static void readPreamble(DataInputStream din) throws IOException {
        int magic = din.readInt();
        checkPreamble(magic, din.readByte());
    }

    private static void checkPreamble(int magic, byte version) throws IOException {
        if (magic != MAGIC) {
            throw new IOException("Invalid remote deploy magic: 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("Unsupported remote deploy protocol version " + version
                    + "; this server speaks version " + VERSION + ". Run the same BileTools version on both servers.");
        }
    }

    private static byte[] readExactly(DataInputStream din, int length, String field) throws IOException {
        byte[] bytes = din.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Incomplete " + field + " from remote peer");
        }
        return bytes;
    }

    private static void readStatus(DataInputStream din) throws IOException {
        byte status = din.readByte();
        if (status == STATUS_ACCEPTED) {
            return;
        }
        if (status == STATUS_REJECTED) {
            throw new IOException("Receiver rejected the transfer: " + din.readUTF());
        }
        throw new IOException("Unknown remote deploy status: " + status);
    }

    private static void writeStatus(DataOutputStream dos, byte status, String reason) throws IOException {
        dos.writeByte(status);
        if (status == STATUS_REJECTED) {
            dos.writeUTF(reason == null || reason.isBlank() ? "transfer failed" : reason);
        }
        dos.flush();
    }

    private static void reject(DataOutputStream dos, String reason) {
        try {
            writeStatus(dos, STATUS_REJECTED, reason);
        } catch (IOException ignored) {
        }
    }

    private static byte[] sha256OfFile(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(file);
                 DigestInputStream din = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (din.read(buffer) != -1) {
                    // drain
                }
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }
}
