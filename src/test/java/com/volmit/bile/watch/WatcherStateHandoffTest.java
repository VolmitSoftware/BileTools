package com.volmit.bile.watch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WatcherStateHandoffTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void roundTripsAppliedAndPendingWatcherStateAndConsumesTheFile() throws Exception {
        Path directory = temporaryFolder.newFolder("plugins").toPath();
        Path target = temporaryFolder.newFolder("data").toPath().resolve("watcher-handoff.bin");
        Path appliedPath = directory.resolve("Applied.jar");
        Path pendingPath = directory.resolve("Pending.jar");
        JarSnapshotStager.FileStamp stamp = new JarSnapshotStager.FileStamp(42L, 91L, "file-key");
        WatcherStateHandoff.write(target, directory, 9_000L, false, List.of(
                new WatcherStateHandoff.Entry(appliedPath, "Applied", List.of(), stamp, "abc123", false),
                new WatcherStateHandoff.Entry(
                        pendingPath,
                        "Pending",
                        List.of(
                                new WatcherStateHandoff.Replacement("PreviousPending", appliedPath),
                                new WatcherStateHandoff.Replacement("LegacyPending", pendingPath)),
                        null,
                        "old456",
                        true)
        ));

        WatcherStateHandoff.Snapshot restored = WatcherStateHandoff.readAndDelete(
                target, directory, 60_000L);

        assertEquals("abc123", restored.entries().get(appliedPath.toAbsolutePath()).appliedFingerprint());
        assertEquals(stamp, restored.entries().get(appliedPath.toAbsolutePath()).stamp());
        assertEquals(9_000L, restored.remainingAutomaticBatchNanos());
        assertFalse(restored.awaitAutomaticReloadCompletion());
        assertTrue(restored.entries().get(pendingPath.toAbsolutePath()).pending());
        assertEquals("PreviousPending",
                restored.entries().get(pendingPath.toAbsolutePath()).replacements().get(0).pluginName());
        assertEquals(appliedPath.toAbsolutePath(),
                restored.entries().get(pendingPath.toAbsolutePath()).replacements().get(0).sourcePath());
        assertEquals("LegacyPending",
                restored.entries().get(pendingPath.toAbsolutePath()).replacements().get(1).pluginName());
        assertEquals(pendingPath.toAbsolutePath(),
                restored.entries().get(pendingPath.toAbsolutePath()).replacements().get(1).sourcePath());
        assertFalse(Files.exists(target));
    }

    @Test
    public void rejectsEntriesOutsideTheWatchedDirectory() throws Exception {
        Path directory = temporaryFolder.newFolder("bounded-plugins").toPath();
        Path target = temporaryFolder.newFolder("bounded-data").toPath().resolve("watcher-handoff.bin");
        Path outside = directory.getParent().resolve("Outside.jar");

        assertThrows(IOException.class, () -> WatcherStateHandoff.write(
                target,
                directory,
                0L,
                false,
                List.of(new WatcherStateHandoff.Entry(outside, "Outside", List.of(), null, "", true))));
        assertFalse(Files.exists(target));
    }

    @Test
    public void rejectsHandoffFromAnotherJvmLifetime() throws Exception {
        Path directory = temporaryFolder.newFolder("restart-plugins").toPath();
        Path target = temporaryFolder.newFolder("restart-data").toPath().resolve("watcher-handoff.bin");
        WatcherStateHandoff.write(target, directory, 0L, false, List.of());
        try (RandomAccessFile file = new RandomAccessFile(target.toFile(), "rw")) {
            file.seek(Integer.BYTES * 2L + Long.BYTES);
            file.writeLong(Long.MIN_VALUE);
        }

        assertNull(WatcherStateHandoff.readAndDelete(target, directory, 60_000L));
        assertFalse(Files.exists(target));
    }

    @Test
    public void restoresTheCompletionAnchoredTrailingBatchDelay() throws Exception {
        Path directory = temporaryFolder.newFolder("cooldown-plugins").toPath();
        Path target = temporaryFolder.newFolder("cooldown-data").toPath().resolve("watcher-handoff.bin");
        AutomaticReloadQueue original = new AutomaticReloadQueue(3_000L);
        original.submit(unload("First", 1L));
        original.beginBatch(100L).orElseThrow();
        original.submit(unload("Trailing", 2L));
        original.completeBatch(10_000L);
        long persistedRemaining = original.remainingBatchDelay(11_000L);
        WatcherStateHandoff.write(target, directory, persistedRemaining, false, List.of());

        WatcherStateHandoff.Snapshot snapshot = WatcherStateHandoff.readAndDelete(
                target, directory, 60_000L);
        AutomaticReloadQueue restored = new AutomaticReloadQueue(3_000L);
        restored.deferBatchesUntil(50_000L + snapshot.remainingAutomaticBatchNanos());
        restored.submit(unload("Trailing", 2L));

        assertEquals(2_000L, snapshot.remainingAutomaticBatchNanos());
        assertTrue(restored.beginBatch(51_999L).isEmpty());
        assertTrue(restored.beginBatch(52_000L).isPresent());
    }

    @Test
    public void automaticSelfReloadWaitsForLongDependentPhaseBeforeStartingCooldown() throws Exception {
        Path directory = temporaryFolder.newFolder("self-reload-plugins").toPath();
        Path target = temporaryFolder.newFolder("self-reload-data").toPath().resolve("watcher-handoff.bin");
        Path completionMarker = target.resolveSibling("watcher-handoff-completion.lock");
        AutomaticReloadQueue original = new AutomaticReloadQueue(3_000L);
        original.submit(unload("BileTools", 1L));
        original.beginBatch(100L).orElseThrow();
        original.completeBatch(10_000L);
        AutomaticReloadQueue restored = new AutomaticReloadQueue(3_000L);
        WatcherStateHandoff.Snapshot snapshot;

        try (AutomaticReloadCompletionHandoff ignored =
                     AutomaticReloadCompletionHandoff.begin(completionMarker)) {
            WatcherStateHandoff.write(
                    target, directory, original.completionCooldownNanos(), true, List.of());
            snapshot = WatcherStateHandoff.readAndDelete(target, directory, 60_000L);
            restored.awaitReloadCompletion();
            restored.submit(unload("Trailing", 2L));

            assertTrue(snapshot.awaitAutomaticReloadCompletion());
            assertFalse(AutomaticReloadCompletionHandoff.completionObserved(completionMarker));
            assertTrue(restored.beginBatch(50_000L).isEmpty());
            assertTrue(restored.beginBatch(9_999_999L).isEmpty());
        }

        long reloadReturnedNanos = 10_000_000L;
        assertTrue(AutomaticReloadCompletionHandoff.completionObserved(completionMarker));
        assertTrue(restored.completeReloadHandoff(reloadReturnedNanos));

        assertEquals(3_000L, snapshot.remainingAutomaticBatchNanos());
        assertTrue(restored.beginBatch(10_002_999L).isEmpty());
        assertTrue(restored.beginBatch(10_003_000L).isPresent());
    }

    private AutomaticReloadQueue.Candidate unload(String pluginName, long generation) {
        return new AutomaticReloadQueue.Candidate(
                pluginName,
                Path.of(pluginName + ".jar"),
                generation,
                AutomaticReloadQueue.Action.UNLOAD,
                null,
                false);
    }
}
