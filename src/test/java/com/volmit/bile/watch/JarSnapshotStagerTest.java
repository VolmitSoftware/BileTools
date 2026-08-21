package com.volmit.bile.watch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JarSnapshotStagerTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stagesAnImmutableValidatedSnapshot() throws Exception {
        Path source = temporaryFolder.newFile("Demo.jar").toPath();
        Path stagingDirectory = temporaryFolder.newFolder("stage").toPath();
        writePluginJar(source, "1.0.0");

        JarSnapshotStager.StagedJar stagedJar = JarSnapshotStager.stage(source, stagingDirectory, 17L);
        writePluginJar(source, "2.0.0");

        assertEquals(17L, stagedJar.generation());
        assertEquals("1.0.0", readVersion(stagedJar.staged()));
        assertEquals("2.0.0", readVersion(source));
        assertEquals(64, stagedJar.sha256().length());
        try (Stream<Path> paths = Files.list(stagingDirectory)) {
            List<Path> partials = paths.filter(path -> path.getFileName().toString().endsWith(".part")).toList();
            assertTrue(partials.isEmpty());
        }

        stagedJar.delete();
        assertFalse(Files.exists(stagedJar.staged()));
    }

    @Test
    public void rejectsArchivesWithoutAPluginDescriptor() throws Exception {
        Path source = temporaryFolder.newFile("invalid.jar").toPath();
        Path stagingDirectory = temporaryFolder.newFolder("invalid-stage").toPath();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new ZipEntry("payload.txt"));
            output.write("payload".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertThrows(JarSnapshotStager.InvalidPluginJarException.class,
                () -> JarSnapshotStager.stage(source, stagingDirectory, 1L));
        try (Stream<Path> paths = Files.list(stagingDirectory)) {
            assertEquals(0L, paths.count());
        }
    }

    @Test
    public void rejectsCrcCorruptionOutsideThePluginDescriptor() throws Exception {
        Path source = temporaryFolder.newFile("corrupt.jar").toPath();
        Path stagingDirectory = temporaryFolder.newFolder("corrupt-stage").toPath();
        byte[] payload = "stable-payload-content".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write("name: Demo\nversion: 1\nmain: example.Demo\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            CRC32 crc = new CRC32();
            crc.update(payload);
            ZipEntry payloadEntry = new ZipEntry("payload.bin");
            payloadEntry.setMethod(ZipEntry.STORED);
            payloadEntry.setSize(payload.length);
            payloadEntry.setCompressedSize(payload.length);
            payloadEntry.setCrc(crc.getValue());
            output.putNextEntry(payloadEntry);
            output.write(payload);
            output.closeEntry();
        }

        byte[] archive = Files.readAllBytes(source);
        int payloadOffset = findBytes(archive, payload);
        archive[payloadOffset] ^= 1;
        Files.write(source, archive);

        assertThrows(JarSnapshotStager.InvalidPluginJarException.class,
                () -> JarSnapshotStager.stage(source, stagingDirectory, 2L));
    }

    @Test
    public void fingerprintDetectsSameSizeSameTimestampContentChanges() throws Exception {
        Path source = temporaryFolder.newFile("same-stamp.jar").toPath();
        FileTime timestamp = FileTime.fromMillis(1_700_000_000_000L);
        Files.writeString(source, "one", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, timestamp);
        String first = JarSnapshotStager.fingerprint(source);

        Files.writeString(source, "two", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, timestamp);
        String second = JarSnapshotStager.fingerprint(source);

        assertFalse(first.equals(second));
    }

    private void writePluginJar(Path target, String version) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(("name: Demo\nversion: " + version + "\nmain: example.Demo\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private String readVersion(Path source) throws IOException {
        try (ZipFile archive = new ZipFile(source.toFile())) {
            ZipEntry entry = archive.getEntry("plugin.yml");
            try (InputStream input = archive.getInputStream(entry)) {
                String descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : descriptor.lines().toList()) {
                    if (line.startsWith("version: ")) {
                        return line.substring("version: ".length());
                    }
                }
            }
        }
        throw new IOException("Version is missing");
    }

    private int findBytes(byte[] source, byte[] target) throws IOException {
        for (int start = 0; start <= source.length - target.length; start++) {
            boolean match = true;
            for (int offset = 0; offset < target.length; offset++) {
                if (source[start + offset] != target[offset]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return start;
            }
        }
        throw new IOException("Stored payload was not found in the test archive");
    }
}
