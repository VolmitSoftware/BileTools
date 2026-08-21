package com.volmit.bile;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Framed remote jar transfer protocol used by master deploy and slave receive.
 *
 * Frame layout:
 * <pre>
 * magic (int) | version (byte) | password (UTF) | fileName (UTF)
 * length (long) | sha256 (32 bytes) | payload (length bytes)
 * </pre>
 */
public final class RemoteDeployProtocol {
    public static final int MAGIC = 0xB11EB11E;
    public static final byte VERSION = 1;
    public static final int SHA256_BYTES = 32;

    private RemoteDeployProtocol() {
    }

    public static void streamFile(File file, String address, int port, String password, int timeoutMs, long maxBytes) throws IOException {
        streamFile(file, file == null ? null : file.getName(), address, port, password, timeoutMs, maxBytes);
    }

    public static void streamFile(File file,
                                  String transferFileName,
                                  String address,
                                  int port,
                                  String password,
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

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            socket.setTcpNoDelay(true);

            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 FileInputStream fin = new FileInputStream(file)) {
                dos.writeInt(MAGIC);
                dos.writeByte(VERSION);
                dos.writeUTF(password == null ? "" : password);
                dos.writeUTF(safeName);
                dos.writeLong(length);
                dos.write(digest);

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

                dos.flush();
            }
        }
    }

    public static File receiveFile(Socket client, String expectedPassword, File pluginsFolder, long maxBytes) throws IOException {
        if (client == null) {
            throw new IOException("Client socket is null");
        }
        if (pluginsFolder == null) {
            throw new IOException("Plugins folder is null");
        }

        try (DataInputStream din = new DataInputStream(client.getInputStream())) {
            int magic = din.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid remote deploy magic: 0x" + Integer.toHexString(magic));
            }

            byte version = din.readByte();
            if (version != VERSION) {
                throw new IOException("Unsupported remote deploy protocol version: " + version);
            }

            String password = din.readUTF();
            if (expectedPassword == null || !expectedPassword.equals(password)) {
                throw new IOException("Remote deploy password mismatch");
            }

            String rawName = din.readUTF();
            String fileName = sanitizeJarFileName(rawName);
            long length = din.readLong();
            if (length < 0 || length > maxBytes) {
                throw new IOException("Remote transfer size rejected: " + length);
            }

            byte[] expectedDigest = din.readNBytes(SHA256_BYTES);
            if (expectedDigest.length != SHA256_BYTES) {
                throw new IOException("Incomplete SHA-256 digest from remote peer");
            }

            File target = new File(pluginsFolder, fileName).getCanonicalFile();
            File parent = pluginsFolder.getCanonicalFile();
            if (!target.getPath().startsWith(parent.getPath() + File.separator) && !target.equals(parent)) {
                throw new IOException("Resolved transfer path escapes plugins folder: " + fileName);
            }

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

                byte[] actualDigest = digest.digest();
                if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                    throw new IOException("SHA-256 mismatch for " + fileName);
                }

                Path targetPath = target.toPath();
                try {
                    Files.move(part.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(part.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                installed = true;
                return target;
            } finally {
                if (!installed) {
                    Files.deleteIfExists(part.toPath());
                }
            }
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
