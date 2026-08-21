package com.volmit.bile.watch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WatcherStateHandoff {
    private static final int MAGIC = 0xB11E57A7;
    private static final int VERSION = 4;
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_REPLACEMENTS_PER_ENTRY = 1_000;
    private static final long JVM_START_MILLIS = ManagementFactory.getRuntimeMXBean().getStartTime();

    private WatcherStateHandoff() {
    }

    public static void write(Path target,
                             Path watchedDirectory,
                             long remainingAutomaticBatchNanos,
                             boolean awaitAutomaticReloadCompletion,
                             Collection<Entry> entries) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(watchedDirectory, "watchedDirectory");
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IOException("Watcher handoff contains too many entries: " + entries.size());
        }

        Path normalizedDirectory = watchedDirectory.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Watcher handoff has no parent directory: " + normalizedTarget);
        }
        Files.createDirectories(parent);
        Path partial = parent.resolve(normalizedTarget.getFileName() + ".part");
        boolean complete = false;
        try {
            try (DataOutputStream output = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(partial)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeLong(System.currentTimeMillis());
                output.writeLong(JVM_START_MILLIS);
                output.writeLong(remainingAutomaticBatchNanos);
                output.writeBoolean(awaitAutomaticReloadCompletion);
                output.writeInt(entries.size());
                for (Entry entry : entries) {
                    Path path = entry.path().toAbsolutePath().normalize();
                    if (!normalizedDirectory.equals(path.getParent())) {
                        throw new IOException("Watcher handoff entry escapes watched directory: " + path);
                    }
                    output.writeUTF(path.getFileName().toString());
                    output.writeUTF(entry.pluginName());
                    if (entry.replacements().size() > MAX_REPLACEMENTS_PER_ENTRY) {
                        throw new IOException("Watcher handoff entry contains too many replacements: "
                                + entry.replacements().size());
                    }
                    output.writeInt(entry.replacements().size());
                    for (Replacement replacement : entry.replacements()) {
                        Path sourcePath = replacement.sourcePath().toAbsolutePath().normalize();
                        if (!normalizedDirectory.equals(sourcePath.getParent())) {
                            throw new IOException("Watcher handoff replacement source escapes watched directory: "
                                    + sourcePath);
                        }
                        output.writeUTF(replacement.pluginName());
                        output.writeUTF(sourcePath.getFileName().toString());
                    }
                    JarSnapshotStager.FileStamp stamp = entry.stamp();
                    output.writeBoolean(stamp != null);
                    if (stamp != null) {
                        output.writeLong(stamp.size());
                        output.writeLong(stamp.modifiedNanos());
                        output.writeUTF(stamp.fileKey());
                    }
                    output.writeUTF(entry.appliedFingerprint());
                    output.writeBoolean(entry.pending());
                }
            }

            moveComplete(partial, normalizedTarget);
            complete = true;
        } finally {
            Files.deleteIfExists(partial);
            if (!complete) {
                Files.deleteIfExists(normalizedTarget);
            }
        }
    }

    public static Snapshot readAndDelete(Path target,
                                         Path watchedDirectory,
                                         long maximumAgeMillis) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(watchedDirectory, "watchedDirectory");
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedTarget)) {
            return null;
        }

        try {
            return read(normalizedTarget, watchedDirectory, maximumAgeMillis);
        } finally {
            Files.deleteIfExists(normalizedTarget);
            Files.deleteIfExists(normalizedTarget.resolveSibling(normalizedTarget.getFileName() + ".part"));
        }
    }

    public static void delete(Path target) throws IOException {
        if (target == null) {
            return;
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Files.deleteIfExists(normalizedTarget);
        Files.deleteIfExists(normalizedTarget.resolveSibling(normalizedTarget.getFileName() + ".part"));
    }

    private static Snapshot read(Path target,
                                 Path watchedDirectory,
                                 long maximumAgeMillis) throws IOException {
        Path normalizedDirectory = watchedDirectory.toAbsolutePath().normalize();
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(target)))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid watcher handoff header");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported watcher handoff version: " + version);
            }
            long createdMillis = input.readLong();
            long jvmStartMillis = input.readLong();
            if (jvmStartMillis != JVM_START_MILLIS) {
                return null;
            }
            long ageMillis = System.currentTimeMillis() - createdMillis;
            if (ageMillis < 0L || ageMillis > Math.max(1L, maximumAgeMillis)) {
                return null;
            }
            long remainingAutomaticBatchNanos = input.readLong();
            boolean awaitAutomaticReloadCompletion = input.readBoolean();
            int entryCount = input.readInt();
            if (entryCount < 0 || entryCount > MAX_ENTRIES) {
                throw new IOException("Invalid watcher handoff entry count: " + entryCount);
            }

            Map<Path, Entry> entries = new LinkedHashMap<>();
            for (int index = 0; index < entryCount; index++) {
                String fileName = input.readUTF();
                Path path = normalizedDirectory.resolve(fileName).toAbsolutePath().normalize();
                if (!normalizedDirectory.equals(path.getParent())
                        || !path.getFileName().toString().equals(fileName)) {
                    throw new IOException("Invalid watcher handoff file name: " + fileName);
                }
                String pluginName = input.readUTF();
                int replacementCount = input.readInt();
                if (replacementCount < 0 || replacementCount > MAX_REPLACEMENTS_PER_ENTRY) {
                    throw new IOException("Invalid watcher handoff replacement count: " + replacementCount);
                }
                List<Replacement> replacements = new ArrayList<>(replacementCount);
                for (int replacementIndex = 0; replacementIndex < replacementCount; replacementIndex++) {
                    String replacedPluginName = input.readUTF();
                    String sourceFileName = input.readUTF();
                    Path sourcePath = normalizedDirectory.resolve(sourceFileName).toAbsolutePath().normalize();
                    if (!normalizedDirectory.equals(sourcePath.getParent())
                            || !sourcePath.getFileName().toString().equals(sourceFileName)) {
                        throw new IOException("Invalid watcher handoff replacement source: " + sourceFileName);
                    }
                    replacements.add(new Replacement(replacedPluginName, sourcePath));
                }
                JarSnapshotStager.FileStamp stamp = null;
                if (input.readBoolean()) {
                    stamp = new JarSnapshotStager.FileStamp(
                            input.readLong(), input.readLong(), input.readUTF());
                }
                String appliedFingerprint = input.readUTF();
                boolean pending = input.readBoolean();
                Entry entry = new Entry(
                        path, pluginName, replacements, stamp, appliedFingerprint, pending);
                if (entries.put(path, entry) != null) {
                    throw new IOException("Duplicate watcher handoff entry: " + fileName);
                }
            }
            return new Snapshot(
                    createdMillis,
                    remainingAutomaticBatchNanos,
                    awaitAutomaticReloadCompletion,
                    entries);
        }
    }

    private static void moveComplete(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Entry(Path path,
                        String pluginName,
                        List<Replacement> replacements,
                        JarSnapshotStager.FileStamp stamp,
                        String appliedFingerprint,
                        boolean pending) {
        public Entry {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            pluginName = pluginName == null ? "" : pluginName.trim();
            replacements = replacements == null ? List.of() : List.copyOf(replacements);
            appliedFingerprint = appliedFingerprint == null ? "" : appliedFingerprint.trim();
        }
    }

    public record Replacement(String pluginName, Path sourcePath) {
        public Replacement {
            pluginName = pluginName == null ? "" : pluginName.trim();
            if (pluginName.isEmpty()) {
                throw new IllegalArgumentException("Replacement plugin name must not be blank");
            }
            sourcePath = Objects.requireNonNull(sourcePath, "sourcePath").toAbsolutePath().normalize();
        }
    }

    public record Snapshot(long createdMillis,
                           long remainingAutomaticBatchNanos,
                           boolean awaitAutomaticReloadCompletion,
                           Map<Path, Entry> entries) {
        public Snapshot {
            entries = Map.copyOf(entries);
        }
    }
}
