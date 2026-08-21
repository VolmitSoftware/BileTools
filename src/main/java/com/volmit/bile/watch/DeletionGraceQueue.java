package com.volmit.bile.watch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class DeletionGraceQueue {
    private final long graceNanos;
    private final Map<Path, Tombstone> tombstones = new LinkedHashMap<>();

    public DeletionGraceQueue(long graceNanos) {
        this.graceNanos = Math.max(1L, graceNanos);
    }

    public Tombstone schedule(Path path, String pluginName, long generation, long nowNanos) {
        Path normalized = normalize(path);
        Tombstone existing = tombstones.get(normalized);
        if (existing != null) {
            return existing;
        }

        Tombstone tombstone = new Tombstone(
                normalized, pluginName, generation, saturatingAdd(nowNanos, graceNanos));
        tombstones.put(normalized, tombstone);
        return tombstone;
    }

    public Tombstone cancel(Path path) {
        return tombstones.remove(normalize(path));
    }

    public List<Tombstone> cancelPlugin(String pluginName, Path retainedPath) {
        String pluginKey = normalizePluginName(pluginName);
        Path retained = retainedPath == null ? null : normalize(retainedPath);
        List<Tombstone> canceled = new ArrayList<>();
        for (Tombstone tombstone : new ArrayList<>(tombstones.values())) {
            if (!normalizePluginName(tombstone.pluginName()).equals(pluginKey)) {
                continue;
            }
            tombstones.remove(tombstone.path());
            if (retained == null || !retained.equals(tombstone.path())) {
                canceled.add(tombstone);
            }
        }
        return canceled;
    }

    public List<Tombstone> expire(long nowNanos) {
        List<Tombstone> expired = new ArrayList<>();
        for (Tombstone tombstone : new ArrayList<>(tombstones.values())) {
            if (nowNanos < tombstone.deadlineNanos()) {
                continue;
            }
            tombstones.remove(tombstone.path());
            expired.add(tombstone);
        }
        return expired;
    }

    public List<Tombstone> snapshot() {
        return List.copyOf(tombstones.values());
    }

    public void clear() {
        tombstones.clear();
    }

    public boolean contains(Path path) {
        return tombstones.containsKey(normalize(path));
    }

    public boolean isEmpty() {
        return tombstones.isEmpty();
    }

    private Path normalize(Path path) {
        return Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    private String normalizePluginName(String pluginName) {
        return Objects.requireNonNull(pluginName, "pluginName").trim().toLowerCase(Locale.ROOT);
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    public record Tombstone(Path path, String pluginName, long generation, long deadlineNanos) {
        public Tombstone {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            Objects.requireNonNull(pluginName, "pluginName");
            if (pluginName.trim().isEmpty()) {
                throw new IllegalArgumentException("pluginName must not be blank");
            }
        }
    }
}
