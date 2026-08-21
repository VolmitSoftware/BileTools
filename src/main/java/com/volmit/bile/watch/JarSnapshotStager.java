package com.volmit.bile.watch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarSnapshotStager {
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private JarSnapshotStager() {
    }

    public static StagedJar stage(Path source, Path stagingDirectory, long generation) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(stagingDirectory, "stagingDirectory");

        FileStamp before = FileStamp.read(source);
        Files.createDirectories(stagingDirectory);
        String baseName = generation + "-" + UUID.randomUUID();
        Path partial = stagingDirectory.resolve(baseName + ".jar.part");
        Path staged = stagingDirectory.resolve(baseName + ".jar");
        boolean completed = false;

        try {
            MessageDigest digest = sha256();
            long copied = copyAndDigest(source, partial, digest);
            FileStamp after = FileStamp.read(source);
            if (!before.equals(after) || copied != after.size()) {
                throw new SourceChangedException(source);
            }

            byte[] snapshotDigest = digest.digest();
            validatePluginJar(partial);
            FileStamp verificationBefore = FileStamp.read(source);
            byte[] sourceDigest = digest(source);
            FileStamp verificationAfter = FileStamp.read(source);
            if (!after.equals(verificationBefore)
                    || !verificationBefore.equals(verificationAfter)
                    || !MessageDigest.isEqual(snapshotDigest, sourceDigest)) {
                throw new SourceChangedException(source);
            }
            moveComplete(partial, staged);
            completed = true;
            return new StagedJar(source.toAbsolutePath().normalize(), staged, generation, after,
                    HexFormat.of().formatHex(snapshotDigest));
        } finally {
            Files.deleteIfExists(partial);
            if (!completed) {
                Files.deleteIfExists(staged);
            }
        }
    }

    public static String fingerprint(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        FileStamp before = FileStamp.read(source);
        byte[] fingerprint = digest(source);
        FileStamp after = FileStamp.read(source);
        if (!before.equals(after)) {
            throw new SourceChangedException(source);
        }
        return HexFormat.of().formatHex(fingerprint);
    }

    private static long copyAndDigest(Path source, Path target, MessageDigest digest) throws IOException {
        long copied = 0L;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(target))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                copied += read;
            }
        }
        return copied;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] digest(Path source) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static void validatePluginJar(Path staged) throws IOException {
        try (ZipFile archive = new ZipFile(staged.toFile())) {
            boolean descriptorFound = false;
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            Enumeration<? extends ZipEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals("plugin.yml") || entry.getName().equals("paper-plugin.yml")) {
                    descriptorFound = true;
                }
                if (entry.isDirectory()) {
                    continue;
                }
                CRC32 crc = new CRC32();
                long uncompressedBytes = 0L;
                try (InputStream input = archive.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        crc.update(buffer, 0, read);
                        uncompressedBytes += read;
                    }
                }
                if (entry.getSize() >= 0L && entry.getSize() != uncompressedBytes) {
                    throw new InvalidPluginJarException(staged, "size mismatch for " + entry.getName());
                }
                if (entry.getCrc() >= 0L && entry.getCrc() != crc.getValue()) {
                    throw new InvalidPluginJarException(staged, "CRC mismatch for " + entry.getName());
                }
            }
            if (!descriptorFound) {
                throw new InvalidPluginJarException(staged, "plugin.yml and paper-plugin.yml are both missing");
            }
        }
    }

    private static void moveComplete(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    public record FileStamp(long size, long modifiedNanos, String fileKey) {
        public static FileStamp read(Path path) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IOException("Not a regular file: " + path);
            }
            Object key = attributes.fileKey();
            return new FileStamp(
                    attributes.size(),
                    attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS),
                    key == null ? "" : key.toString()
            );
        }
    }

    public record StagedJar(Path source, Path staged, long generation, FileStamp sourceStamp, String sha256) {
        public StagedJar {
            source = source.toAbsolutePath().normalize();
            staged = staged.toAbsolutePath().normalize();
        }

        public void delete() {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                staged.toFile().deleteOnExit();
            }
        }
    }

    public static final class SourceChangedException extends IOException {
        public SourceChangedException(Path source) {
            super("Source changed while staging: " + source);
        }
    }

    public static final class InvalidPluginJarException extends IOException {
        public InvalidPluginJarException(Path source, String reason) {
            super("Invalid plugin jar " + source + ": " + reason);
        }
    }
}
