package com.volmit.bile.watch;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AutomaticReloadQueueTest {
    private static final long INTERVAL_NANOS = 3_000L;

    @Test
    public void enforcesCooldownAndRunsExactlyOneLatestTrailingBatch() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate first = unload("Demo", 1L);
        AutomaticReloadQueue.Candidate second = unload("Demo", 2L);
        AutomaticReloadQueue.Candidate latest = unload("Demo", 3L);

        assertTrue(queue.submit(first).accepted());
        AutomaticReloadQueue.Batch firstBatch = queue.beginBatch(100L).orElseThrow();
        assertEquals(1L, firstBatch.candidates().get(0).generation());

        assertTrue(queue.submit(second).accepted());
        AutomaticReloadQueue.Submission latestSubmission = queue.submit(latest);
        assertTrue(latestSubmission.accepted());
        assertSame(second, latestSubmission.discarded());
        assertTrue(queue.hasPendingNewer("demo", first.generation()));
        assertFalse(queue.hasPendingNewer("Demo", latest.generation()));
        assertTrue(queue.beginBatch(150L).isEmpty());

        queue.completeBatch(200L);
        assertTrue(queue.beginBatch(3_199L).isEmpty());
        AutomaticReloadQueue.Batch trailingBatch = queue.beginBatch(3_200L).orElseThrow();
        assertEquals(1, trailingBatch.candidates().size());
        assertEquals(3L, trailingBatch.candidates().get(0).generation());

        queue.completeBatch(3_300L);
        assertTrue(queue.beginBatch(20_000L).isEmpty());
        assertFalse(queue.hasWork());
    }

    @Test
    public void longRunningBatchStartsItsCooldownOnlyAfterCompletion() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        queue.submit(unload("Demo", 1L));
        queue.beginBatch(100L).orElseThrow();
        queue.submit(unload("Demo", 2L));

        queue.completeBatch(10_000L);

        assertEquals(13_000L, queue.nextBatchNanos());
        assertEquals(3_000L, queue.remainingBatchDelay(10_000L));
        assertEquals(1L, queue.remainingBatchDelay(12_999L));
        assertTrue(queue.beginBatch(12_999L).isEmpty());
        assertEquals(2L, queue.beginBatch(13_000L).orElseThrow().candidates().get(0).generation());
    }

    @Test
    public void completionWithoutAnActiveBatchIsRejected() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);

        assertThrows(IllegalStateException.class, () -> queue.completeBatch(1L));
    }

    @Test
    public void reloadCompletionGateBlocksEveryCandidateUntilCompletionCooldown() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        queue.awaitReloadCompletion();
        queue.submit(unload("Trailing", 2L));

        assertTrue(queue.hasWork());
        assertTrue(queue.beginBatch(Long.MAX_VALUE).isEmpty());
        assertTrue(queue.completeReloadHandoff(10_000L));
        assertFalse(queue.isAwaitingReloadCompletion());
        assertTrue(queue.beginBatch(12_999L).isEmpty());
        assertTrue(queue.beginBatch(13_000L).isPresent());
    }

    @Test
    public void refusedSelfReloadRetainsTheExactStagedCandidateUntilCooldownExpires() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate candidate = upsert("BileTools", "BileTools.jar", 4L);
        queue.submit(candidate);
        assertSame(candidate, queue.beginBatch(100L).orElseThrow().candidates().get(0));
        queue.completeBatch(200L);

        AutomaticReloadQueue.Submission retry = queue.submit(candidate);

        assertTrue(retry.accepted());
        assertTrue(queue.beginBatch(3_199L).isEmpty());
        assertSame(candidate, queue.beginBatch(3_200L).orElseThrow().candidates().get(0));
    }

    @Test
    public void refusedRemoteBeforeSelfRetainsTheExactRemoteCandidateForRetry() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate candidate = upsert("BileTools", "BileTools.jar", 5L, true);
        queue.submit(candidate);
        assertSame(candidate, queue.beginBatch(100L).orElseThrow().candidates().get(0));
        queue.completeBatch(200L);

        AutomaticReloadQueue.Submission retry = queue.submit(candidate);

        assertTrue(retry.accepted());
        AutomaticReloadQueue.Candidate retried = queue.beginBatch(3_200L).orElseThrow().candidates().get(0);
        assertSame(candidate, retried);
        assertTrue(retried.remoteDeploy());
    }

    @Test
    public void rejectsOlderGenerationWithoutDiscardingPendingLatest() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate latest = unload("Demo", 7L);
        AutomaticReloadQueue.Candidate stale = unload("demo", 6L);

        assertTrue(queue.submit(latest).accepted());
        AutomaticReloadQueue.Submission staleSubmission = queue.submit(stale);
        assertFalse(staleSubmission.accepted());
        assertSame(stale, staleSubmission.discarded());

        Optional<AutomaticReloadQueue.Batch> batch = queue.beginBatch(0L);
        assertTrue(batch.isPresent());
        assertSame(latest, batch.get().candidates().get(0));
    }

    @Test
    public void preservesEveryPluginInTheBatch() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        queue.submit(unload("Alpha", 1L));
        queue.submit(unload("Beta", 2L));
        queue.submit(unload("Gamma", 3L));

        AutomaticReloadQueue.Batch batch = queue.beginBatch(0L).orElseThrow();
        assertEquals(3, batch.candidates().size());
    }

    @Test
    public void restoresADeferredBatchDeadlineAcrossOwnerReload() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        queue.deferBatchesUntil(7_000L);
        queue.submit(unload("Alpha", 1L));

        assertTrue(queue.beginBatch(6_999L).isEmpty());
        assertTrue(queue.beginBatch(7_000L).isPresent());
    }

    @Test
    public void newerNoopSupersedesPendingContentUpdate() {
        JarSnapshotStager.StagedJar stagedJar = new JarSnapshotStager.StagedJar(
                Path.of("Demo.jar"),
                Path.of("staged-Demo.jar"),
                2L,
                new JarSnapshotStager.FileStamp(10L, 20L, "key"),
                "fingerprint");
        AutomaticReloadQueue.Candidate update = new AutomaticReloadQueue.Candidate(
                "Demo", Path.of("Demo.jar"), 2L, AutomaticReloadQueue.Action.UPSERT, stagedJar, false);
        AutomaticReloadQueue.Candidate unchanged = new AutomaticReloadQueue.Candidate(
                "Demo", Path.of("Demo.jar"), 3L, AutomaticReloadQueue.Action.NOOP, null, false);
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);

        queue.submit(update);
        AutomaticReloadQueue.Submission submission = queue.submit(unchanged);

        assertTrue(submission.accepted());
        assertSame(update, submission.discarded());
        assertSame(unchanged, queue.beginBatch(0L).orElseThrow().candidates().get(0));
    }

    @Test
    public void currentJarUpsertDominatesNewerUnloadFromAnOldFilename() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate oldFileUnload = new AutomaticReloadQueue.Candidate(
                "Demo", Path.of("Demo-old.jar"), 9L, AutomaticReloadQueue.Action.UNLOAD, null, false);
        AutomaticReloadQueue.Candidate currentFileUpsert = upsert("Demo", "Demo-new.jar", 8L);
        queue.submit(oldFileUnload);

        AutomaticReloadQueue.Submission submission = queue.submit(currentFileUpsert);

        assertTrue(submission.accepted());
        assertSame(oldFileUnload, submission.discarded());
        assertTrue(queue.hasPendingReplacement(oldFileUnload));
        assertSame(currentFileUpsert, queue.beginBatch(0L).orElseThrow().candidates().get(0));
    }

    @Test
    public void oldFilenameUnloadCannotReplaceAQueuedCurrentJarUpsert() {
        AutomaticReloadQueue queue = new AutomaticReloadQueue(INTERVAL_NANOS);
        AutomaticReloadQueue.Candidate currentFileUpsert = upsert("Demo", "Demo-new.jar", 8L);
        AutomaticReloadQueue.Candidate oldFileUnload = new AutomaticReloadQueue.Candidate(
                "Demo", Path.of("Demo-old.jar"), 9L, AutomaticReloadQueue.Action.UNLOAD, null, false);
        queue.submit(currentFileUpsert);

        AutomaticReloadQueue.Submission submission = queue.submit(oldFileUnload);

        assertFalse(submission.accepted());
        assertSame(oldFileUnload, submission.discarded());
        assertSame(currentFileUpsert, queue.beginBatch(0L).orElseThrow().candidates().get(0));
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

    private AutomaticReloadQueue.Candidate upsert(String pluginName, String sourceName, long generation) {
        return upsert(pluginName, sourceName, generation, false);
    }

    private AutomaticReloadQueue.Candidate upsert(String pluginName,
                                                  String sourceName,
                                                  long generation,
                                                  boolean remoteDeploy) {
        Path source = Path.of(sourceName);
        JarSnapshotStager.StagedJar stagedJar = new JarSnapshotStager.StagedJar(
                source,
                Path.of("staged-" + sourceName),
                generation,
                new JarSnapshotStager.FileStamp(10L, 20L, "key"),
                "fingerprint");
        return new AutomaticReloadQueue.Candidate(
                pluginName, source, generation, AutomaticReloadQueue.Action.UPSERT, stagedJar, remoteDeploy);
    }
}
