package com.volmit.bile;

import com.volmit.bile.watch.JarSnapshotStager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginRecoveryStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void retainsRunningBytesAcrossOverwriteAndReplacement() throws Exception {
        Path directory = temporary.newFolder("recovery").toPath();
        Path source = temporary.getRoot().toPath().resolve("Plugin.jar");
        writeJar(source, "old");
        byte[] original = Files.readAllBytes(source);
        PluginRecoveryStore store = new PluginRecoveryStore(directory);
        store.remember("Example", source);
        JarSnapshotStager.StagedJar transaction = store.retain("example");
        try {
            writeJar(source, "new");
            store.remember("Example", source);
            assertArrayEquals(original, Files.readAllBytes(transaction.staged()));
            PluginRecoveryStore reopened = new PluginRecoveryStore(directory);
            JarSnapshotStager.StagedJar latest = reopened.retain("EXAMPLE");
            try {
                assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(latest.staged()));
            } finally {
                latest.delete();
            }
        } finally {
            transaction.delete();
        }
    }

    @Test
    public void invalidReplacementPreservesLastGoodJar() throws Exception {
        PluginRecoveryStore store = new PluginRecoveryStore(temporary.newFolder("recovery").toPath());
        Path source = temporary.getRoot().toPath().resolve("Plugin.jar");
        writeJar(source, "good");
        byte[] original = Files.readAllBytes(source);
        store.remember("Example", source);
        Files.writeString(source, "incomplete upload");
        assertThrows(IOException.class, () -> store.remember("Example", source));
        JarSnapshotStager.StagedJar retained = store.retain("Example");
        try {
            assertArrayEquals(original, Files.readAllBytes(retained.staged()));
        } finally {
            retained.delete();
        }
        assertTrue(store.contains("Example"));
        store.forget("Example");
        assertFalse(store.contains("Example"));
        assertThrows(IOException.class, () -> store.retain("Example"));
    }

    private void writeJar(Path path, String version) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(("name: Example\nmain: example.Main\nversion: " + version + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
