package com.volmit.bile.watch;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PluginJarDirectoryWatcher implements AutoCloseable {
    private final Path directory;
    private final long reconciliationIntervalNanos;
    private final Map<Path, JarSnapshotStager.FileStamp> knownFiles = new HashMap<>();

    private WatchService watchService;
    private WatchKey watchKey;
    private long nextReconciliationNanos;

    public PluginJarDirectoryWatcher(Path directory, long reconciliationIntervalNanos) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.reconciliationIntervalNanos = Math.max(1L, reconciliationIntervalNanos);
    }

    public void start(long nowNanos) throws IOException {
        closeService();
        IOException failure = null;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerDirectory();
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            failure = asIOException("Native plugin directory watching is unavailable", exception);
            closeService();
        }

        try {
            reconcile(new LinkedHashMap<>(), false);
        } catch (IOException exception) {
            if (failure != null) {
                exception.addSuppressed(failure);
            }
            throw exception;
        }
        nextReconciliationNanos = saturatingAdd(nowNanos, reconciliationIntervalNanos);
        if (failure != null) {
            throw failure;
        }
    }

    public PollResult poll(long nowNanos) {
        return poll(nowNanos, false);
    }

    public PollResult reconcileNow(long nowNanos) {
        return poll(nowNanos, true);
    }

    private PollResult poll(long nowNanos, boolean forceReconciliation) {
        Map<Path, Boolean> signals = new LinkedHashMap<>();
        boolean reconciliationRequired = drainNativeEvents(signals) || forceReconciliation;
        if (nowNanos >= nextReconciliationNanos) {
            reconciliationRequired = true;
            nextReconciliationNanos = saturatingAdd(nowNanos, reconciliationIntervalNanos);
        }

        IOException failure = null;
        boolean reconciliationSucceeded = true;
        if (reconciliationRequired) {
            try {
                registerDirectory();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                reconcile(signals, true);
            } catch (IOException exception) {
                reconciliationSucceeded = exception instanceof PartialReconciliationException;
                if (failure != null) {
                    exception.addSuppressed(failure);
                }
                failure = exception;
            }
        }

        List<Signal> result = new ArrayList<>(signals.size());
        for (Map.Entry<Path, Boolean> entry : signals.entrySet()) {
            result.add(new Signal(entry.getKey(), entry.getValue()));
        }
        return new PollResult(result, failure, reconciliationSucceeded);
    }

    public Map<Path, JarSnapshotStager.FileStamp> snapshot() {
        return Map.copyOf(knownFiles);
    }

    public void restoreBaseline(Map<Path, JarSnapshotStager.FileStamp> baseline) {
        knownFiles.clear();
        if (baseline == null) {
            return;
        }
        for (Map.Entry<Path, JarSnapshotStager.FileStamp> entry : baseline.entrySet()) {
            Path normalized = normalizeJarPath(entry.getKey());
            if (normalized != null && entry.getValue() != null) {
                knownFiles.put(normalized, entry.getValue());
            }
        }
    }

    public void synchronize(Path path) {
        Path normalized = normalizeJarPath(path);
        if (normalized == null) {
            return;
        }

        try {
            JarSnapshotStager.FileStamp stamp = readStamp(normalized);
            if (stamp == null) {
                knownFiles.remove(normalized);
            } else {
                knownFiles.put(normalized, stamp);
            }
        } catch (IOException ignored) {
        }
    }

    public boolean isNativeWatchActive() {
        return watchKey != null && watchKey.isValid();
    }

    @Override
    public void close() {
        closeService();
        knownFiles.clear();
    }

    private boolean drainNativeEvents(Map<Path, Boolean> signals) {
        WatchService service = watchService;
        if (service == null) {
            return true;
        }

        boolean reconciliationRequired = false;
        try {
            WatchKey ready;
            while ((ready = service.poll()) != null) {
                for (WatchEvent<?> event : ready.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        reconciliationRequired = true;
                        continue;
                    }

                    Object context = event.context();
                    if (!(context instanceof Path relative)) {
                        reconciliationRequired = true;
                        continue;
                    }

                    Path path = normalizeJarPath(directory.resolve(relative));
                    if (path == null) {
                        continue;
                    }
                    signals.put(path, true);
                    synchronize(path);
                }

                if (!ready.reset()) {
                    if (ready == watchKey) {
                        watchKey = null;
                    }
                    reconciliationRequired = true;
                }
            }
        } catch (ClosedWatchServiceException exception) {
            closeService();
            reconciliationRequired = true;
        }
        return reconciliationRequired;
    }

    private void reconcile(Map<Path, Boolean> signals, boolean emitChanges) throws IOException {
        DirectorySnapshot directorySnapshot = listCurrentFiles();
        Map<Path, JarSnapshotStager.FileStamp> current = directorySnapshot.files();
        if (emitChanges) {
            for (Path unresolvedPath : directorySnapshot.unresolvedPaths()) {
                signals.putIfAbsent(unresolvedPath, false);
            }
            for (Map.Entry<Path, JarSnapshotStager.FileStamp> entry : current.entrySet()) {
                JarSnapshotStager.FileStamp previous = knownFiles.get(entry.getKey());
                if (!entry.getValue().equals(previous)) {
                    signals.putIfAbsent(entry.getKey(), false);
                }
            }
            for (Path previous : knownFiles.keySet()) {
                if (!current.containsKey(previous)) {
                    signals.putIfAbsent(previous, false);
                }
            }
        }

        knownFiles.clear();
        knownFiles.putAll(current);
        if (directorySnapshot.failure() != null) {
            throw new PartialReconciliationException(directorySnapshot.failure());
        }
    }

    private DirectorySnapshot listCurrentFiles() throws IOException {
        Map<Path, JarSnapshotStager.FileStamp> current = new HashMap<>();
        Set<Path> unresolvedPaths = new HashSet<>();
        BasicFileAttributes directoryAttributes;
        try {
            directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return new DirectorySnapshot(current, unresolvedPaths, null);
        } catch (SecurityException exception) {
            throw new IOException("Cannot inspect plugin directory " + directory, exception);
        }
        if (!directoryAttributes.isDirectory()) {
            return new DirectorySnapshot(current, unresolvedPaths, null);
        }

        IOException failure = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                Path normalized = normalizeJarPath(path);
                if (normalized == null) {
                    continue;
                }
                try {
                    JarSnapshotStager.FileStamp stamp = readStamp(normalized);
                    if (stamp != null) {
                        current.put(normalized, stamp);
                    }
                } catch (IOException exception) {
                    unresolvedPaths.add(normalized);
                    JarSnapshotStager.FileStamp previous = knownFiles.get(normalized);
                    if (previous != null) {
                        current.put(normalized, previous);
                    }
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        } catch (DirectoryIteratorException exception) {
            throw new IOException("Cannot enumerate plugin directory " + directory, exception.getCause());
        } catch (SecurityException exception) {
            throw new IOException("Cannot enumerate plugin directory " + directory, exception);
        }
        return new DirectorySnapshot(current, unresolvedPaths, failure);
    }

    private void registerDirectory() throws IOException {
        if (watchKey != null && watchKey.isValid()) {
            return;
        }
        try {
            if (!Files.isDirectory(directory)) {
                return;
            }
            if (watchService == null) {
                watchService = FileSystems.getDefault().newWatchService();
            }

            watchKey = directory.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        } catch (UnsupportedOperationException | SecurityException exception) {
            closeService();
            throw new IOException("Native plugin directory watching is unavailable", exception);
        }
    }

    private Path normalizeJarPath(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!directory.equals(normalized.getParent())) {
            return null;
        }
        String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") ? normalized : null;
    }

    private JarSnapshotStager.FileStamp readStamp(Path path) throws IOException {
        try {
            return JarSnapshotStager.FileStamp.read(path);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            return null;
        } catch (SecurityException exception) {
            throw new IOException("Cannot inspect plugin jar " + path.getFileName(), exception);
        }
    }

    private void closeService() {
        WatchService service = watchService;
        watchService = null;
        watchKey = null;
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (IOException ignored) {
        }
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private IOException asIOException(String message, Throwable failure) {
        if (failure instanceof IOException exception) {
            return exception;
        }
        return new IOException(message, failure);
    }

    public record Signal(Path path, boolean nativeEvent) {
    }

    public record PollResult(List<Signal> signals, IOException failure, boolean reconciliationSucceeded) {
        public PollResult {
            signals = List.copyOf(signals);
        }
    }

    private record DirectorySnapshot(Map<Path, JarSnapshotStager.FileStamp> files,
                                     Set<Path> unresolvedPaths,
                                     IOException failure) {
    }

    private static final class PartialReconciliationException extends IOException {
        private PartialReconciliationException(IOException cause) {
            super("One or more plugin jars could not be inspected", cause);
        }
    }
}
