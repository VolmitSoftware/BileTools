package com.volmit.bile.watch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AutomaticReloadQueue {
    private final long minimumBatchIntervalNanos;
    private final Map<String, Candidate> pending = new LinkedHashMap<>();

    private boolean batchInFlight;
    private boolean awaitingReloadCompletion;
    private long nextBatchNanos = Long.MIN_VALUE;

    public AutomaticReloadQueue(long minimumBatchIntervalNanos) {
        this.minimumBatchIntervalNanos = Math.max(1L, minimumBatchIntervalNanos);
    }

    public synchronized Submission submit(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String key = normalize(candidate.pluginName());
        Candidate existing = pending.get(key);
        if (existing != null && !shouldReplace(existing, candidate)) {
            return new Submission(false, candidate);
        }
        pending.put(key, candidate);
        return new Submission(true, existing);
    }

    public synchronized Optional<Batch> beginBatch(long nowNanos) {
        if (batchInFlight || awaitingReloadCompletion || pending.isEmpty() || nowNanos < nextBatchNanos) {
            return Optional.empty();
        }

        List<Candidate> candidates = new ArrayList<>(pending.values());
        pending.clear();
        batchInFlight = true;
        return Optional.of(new Batch(candidates, nowNanos));
    }

    public synchronized void completeBatch(long completedNanos) {
        if (!batchInFlight) {
            throw new IllegalStateException("No automatic reload batch is in flight");
        }
        batchInFlight = false;
        nextBatchNanos = Math.max(
                nextBatchNanos,
                saturatingAdd(completedNanos, minimumBatchIntervalNanos));
    }

    public synchronized List<Candidate> clear() {
        List<Candidate> discarded = new ArrayList<>(pending.values());
        pending.clear();
        batchInFlight = false;
        awaitingReloadCompletion = false;
        nextBatchNanos = Long.MIN_VALUE;
        return discarded;
    }

    public synchronized boolean hasWork() {
        return batchInFlight || awaitingReloadCompletion || !pending.isEmpty();
    }

    public synchronized boolean isBatchInFlight() {
        return batchInFlight;
    }

    public synchronized int pendingCount() {
        return pending.size();
    }

    public synchronized List<String> pendingPluginNames() {
        List<String> names = new ArrayList<>(pending.size());
        for (Candidate candidate : pending.values()) {
            names.add(candidate.pluginName());
        }
        return List.copyOf(names);
    }

    public synchronized List<Path> pendingSources() {
        List<Path> sources = new ArrayList<>(pending.size());
        for (Candidate candidate : pending.values()) {
            sources.add(candidate.source());
        }
        return List.copyOf(sources);
    }

    public synchronized boolean hasPendingNewer(String pluginName, long generation) {
        Candidate candidate = pending.get(normalize(pluginName));
        return candidate != null && candidate.generation() > generation;
    }

    public synchronized boolean hasPendingReplacement(Candidate candidate) {
        Candidate pendingCandidate = pending.get(normalize(candidate.pluginName()));
        return pendingCandidate != null && shouldReplace(candidate, pendingCandidate);
    }

    public synchronized long nextBatchNanos() {
        return nextBatchNanos;
    }

    public synchronized long remainingBatchDelay(long nowNanos) {
        if (nextBatchNanos <= nowNanos) {
            return 0L;
        }
        return Math.min(minimumBatchIntervalNanos, nextBatchNanos - nowNanos);
    }

    public synchronized void deferBatchesUntil(long earliestBatchNanos) {
        nextBatchNanos = Math.max(nextBatchNanos, earliestBatchNanos);
    }

    public synchronized void awaitReloadCompletion() {
        awaitingReloadCompletion = true;
    }

    public synchronized boolean completeReloadHandoff(long completedNanos) {
        if (!awaitingReloadCompletion) {
            return false;
        }
        awaitingReloadCompletion = false;
        nextBatchNanos = Math.max(
                nextBatchNanos,
                saturatingAdd(completedNanos, minimumBatchIntervalNanos));
        return true;
    }

    public synchronized boolean isAwaitingReloadCompletion() {
        return awaitingReloadCompletion;
    }

    public long completionCooldownNanos() {
        return minimumBatchIntervalNanos;
    }

    private String normalize(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            throw new IllegalArgumentException("pluginName must not be blank");
        }
        return pluginName.trim().toLowerCase(Locale.ROOT);
    }

    private boolean shouldReplace(Candidate existing, Candidate candidate) {
        if (existing.source().equals(candidate.source())) {
            return candidate.generation() > existing.generation();
        }
        int existingPriority = actionPriority(existing.action());
        int candidatePriority = actionPriority(candidate.action());
        return candidatePriority > existingPriority
                || (candidatePriority == existingPriority
                && candidate.generation() > existing.generation());
    }

    private int actionPriority(Action action) {
        return switch (action) {
            case UNLOAD -> 0;
            case NOOP -> 1;
            case UPSERT -> 2;
        };
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public enum Action {
        UPSERT,
        UNLOAD,
        NOOP
    }

    public record Candidate(String pluginName,
                            Path source,
                            long generation,
                            Action action,
                            JarSnapshotStager.StagedJar stagedJar,
                            boolean remoteDeploy) {
        public Candidate {
            Objects.requireNonNull(pluginName, "pluginName");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(action, "action");
            source = source.toAbsolutePath().normalize();
            if (action == Action.UPSERT && stagedJar == null) {
                throw new IllegalArgumentException("UPSERT candidates require a staged jar");
            }
            if (action != Action.UPSERT && stagedJar != null) {
                throw new IllegalArgumentException(action + " candidates cannot carry a staged jar");
            }
        }

        public void discardSnapshot() {
            if (stagedJar != null) {
                stagedJar.delete();
            }
        }
    }

    public record Submission(boolean accepted, Candidate discarded) {
    }

    public record Batch(List<Candidate> candidates, long startedNanos) {
        public Batch {
            candidates = List.copyOf(candidates);
        }
    }
}
