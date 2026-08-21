package com.volmit.bile.watch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PluginJarDirectoryWatcherTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void ignoresPartialTransfersAndSignalsOnlyCompletedJar() throws Exception {
        Path directory = temporaryFolder.newFolder("plugins").toPath();
        Path partial = directory.resolve("Demo.jar.part");
        Path completed = directory.resolve("Demo.jar");
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            watcher.start(0L);
            Files.writeString(partial, "partial", StandardCharsets.UTF_8);
            assertTrue(watcher.poll(2L).signals().isEmpty());

            moveComplete(partial, completed);
            PluginJarDirectoryWatcher.PollResult result = watcher.poll(4L);

            assertEquals(List.of(completed.toAbsolutePath().normalize()),
                    result.signals().stream().map(PluginJarDirectoryWatcher.Signal::path).toList());
            assertEquals(1, watcher.snapshot().size());
        }
    }

    @Test
    public void reconciliationFindsCreateModifyAndDelete() throws Exception {
        Path directory = temporaryFolder.newFolder("reconcile-plugins").toPath();
        Path jar = directory.resolve("Demo.jar");
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            watcher.start(0L);
            Files.writeString(jar, "one", StandardCharsets.UTF_8);
            assertSignal(watcher.poll(2L), jar);

            Files.writeString(jar, "longer-content", StandardCharsets.UTF_8);
            assertSignal(watcher.poll(4L), jar);

            Files.delete(jar);
            assertSignal(watcher.poll(6L), jar);
            assertTrue(watcher.snapshot().isEmpty());
        }
    }

    @Test
    public void forcedReconciliationDoesNotWaitForThePeriodicInterval() throws Exception {
        Path directory = temporaryFolder.newFolder("forced-reconcile-plugins").toPath();
        Path jar = directory.resolve("Demo.jar");
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, Long.MAX_VALUE)) {
            watcher.start(0L);
            Files.writeString(jar, "one", StandardCharsets.UTF_8);

            assertSignal(watcher.reconcileNow(1L), jar);
        }
    }

    @Test
    public void restoresOnlyDirectJarBaselineEntries() throws Exception {
        Path directory = temporaryFolder.newFolder("baseline-plugins").toPath();
        Path jar = directory.resolve("Demo.jar");
        Path nested = directory.resolve("nested").resolve("Nested.jar");
        JarSnapshotStager.FileStamp stamp = new JarSnapshotStager.FileStamp(1L, 2L, "key");
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            watcher.restoreBaseline(Map.of(jar, stamp, nested, stamp));

            assertEquals(Map.of(jar.toAbsolutePath().normalize(), stamp), watcher.snapshot());
        }
    }

    @Test
    public void recoversAfterWatchedDirectoryIsRecreated() throws Exception {
        Path directory = temporaryFolder.newFolder("recreated-plugins").toPath();
        Path jar = directory.resolve("Demo.jar");
        Files.writeString(jar, "one", StandardCharsets.UTF_8);
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            watcher.start(0L);
            Files.delete(jar);
            Files.delete(directory);
            assertSignal(watcher.poll(2L), jar);

            Files.createDirectories(directory);
            Files.writeString(jar, "two", StandardCharsets.UTF_8);
            assertSignal(watcher.poll(4L), jar);
            assertTrue(watcher.isNativeWatchActive());
        }
    }

    @Test
    public void excludesNestedAndNonJarFiles() throws Exception {
        Path directory = temporaryFolder.newFolder("filtered-plugins").toPath();
        Path nestedDirectory = Files.createDirectories(directory.resolve("nested"));
        Files.writeString(directory.resolve("notes.txt"), "text", StandardCharsets.UTF_8);
        Files.writeString(nestedDirectory.resolve("Nested.jar"), "jar", StandardCharsets.UTF_8);
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            watcher.start(0L);

            assertTrue(watcher.snapshot().isEmpty());
            assertFalse(watcher.poll(2L).signals().stream()
                    .anyMatch(signal -> signal.path().getFileName().toString().equals("Nested.jar")));
        }
    }

    @Test
    public void preservesHealthyEntriesAndRetriesUnreadableJarPaths() throws Exception {
        Path directory = temporaryFolder.newFolder("partial-reconcile-plugins").toPath();
        Path goodJar = directory.resolve("Good.jar");
        Path brokenJar = Files.createDirectory(directory.resolve("Broken.jar"));
        Files.writeString(goodJar, "one", StandardCharsets.UTF_8);
        try (PluginJarDirectoryWatcher watcher = new PluginJarDirectoryWatcher(directory, 1L)) {
            assertThrows(IOException.class, () -> watcher.start(0L));
            assertNotNull(watcher.snapshot().get(goodJar.toAbsolutePath().normalize()));

            Files.writeString(goodJar, "longer-content", StandardCharsets.UTF_8);
            PluginJarDirectoryWatcher.PollResult result = watcher.poll(2L);

            assertTrue(result.reconciliationSucceeded());
            assertNotNull(result.failure());
            assertSignal(result, goodJar);
            assertSignal(result, brokenJar);
            assertNotNull(watcher.snapshot().get(goodJar.toAbsolutePath().normalize()));
        }
    }

    private void assertSignal(PluginJarDirectoryWatcher.PollResult result, Path expected) {
        assertTrue(result.signals().stream().anyMatch(signal -> signal.path().equals(expected.toAbsolutePath().normalize())));
    }

    private void moveComplete(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }
}
