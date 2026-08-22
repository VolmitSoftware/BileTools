package com.volmit.bile.watch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class PluginJarDirectoryWatcher implements AutoCloseable {
    static final long EXACT_FINGERPRINT_BYTE_BUDGET = 1024L * 1024L;
    static final int EXACT_FINGERPRINT_FILE_BUDGET = 1;
    private static final long EXACT_FINGERPRINT_TIME_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final int EXACT_FINGERPRINT_BUFFER_BYTES = 64 * 1024;

    private final Path directory;
    private final long reconciliationIntervalNanos;
    private final boolean nativeWatchingEnabled;
    private final long exactFingerprintByteBudget;
    private final int exactFingerprintFileBudget;
    private final LongSupplier completionClock;
    private final Map<Path, JarSnapshotStager.FileStamp> knownFiles = new HashMap<>();
    private final Map<Path, String> knownFingerprints = new HashMap<>();
    private final Set<Path> signaledFingerprintBaselines = new HashSet<>();
    private final ByteBuffer exactFingerprintBuffer = ByteBuffer.allocate(EXACT_FINGERPRINT_BUFFER_BYTES);

    private WatchService watchService;
    private WatchKey watchKey;
    private long nextReconciliationNanos;
    private long nextExactFingerprintNanos;
    private List<Path> exactFingerprintPaths = List.of();
    private int exactFingerprintIndex;
    private ExactFingerprintProgress exactFingerprintProgress;
    private boolean exactFingerprintActive;

    public PluginJarDirectoryWatcher(Path directory, long reconciliationIntervalNanos) {
        this(directory, reconciliationIntervalNanos, true);
    }

    PluginJarDirectoryWatcher(Path directory, long reconciliationIntervalNanos, boolean nativeWatchingEnabled) {
        this(
                directory,
                reconciliationIntervalNanos,
                nativeWatchingEnabled,
                EXACT_FINGERPRINT_BYTE_BUDGET,
                EXACT_FINGERPRINT_FILE_BUDGET
        );
    }

    PluginJarDirectoryWatcher(Path directory,
                              long reconciliationIntervalNanos,
                              boolean nativeWatchingEnabled,
                              long exactFingerprintByteBudget,
                              int exactFingerprintFileBudget) {
        this(
                directory,
                reconciliationIntervalNanos,
                nativeWatchingEnabled,
                exactFingerprintByteBudget,
                exactFingerprintFileBudget,
                System::nanoTime
        );
    }

    PluginJarDirectoryWatcher(Path directory,
                              long reconciliationIntervalNanos,
                              boolean nativeWatchingEnabled,
                              long exactFingerprintByteBudget,
                              int exactFingerprintFileBudget,
                              LongSupplier completionClock) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.reconciliationIntervalNanos = Math.max(1L, reconciliationIntervalNanos);
        this.nativeWatchingEnabled = nativeWatchingEnabled;
        this.exactFingerprintByteBudget = Math.max(1L, exactFingerprintByteBudget);
        this.exactFingerprintFileBudget = Math.max(1, exactFingerprintFileBudget);
        this.completionClock = Objects.requireNonNull(completionClock, "completionClock");
    }

    public void start(long nowNanos) throws IOException {
        closeService();
        IOException failure = null;
        if (nativeWatchingEnabled) {
            try {
                watchService = FileSystems.getDefault().newWatchService();
                registerDirectory();
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                failure = asIOException("Native plugin directory watching is unavailable", exception);
                closeService();
            }
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
        resetExactFingerprintCycle(nowNanos);
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

        boolean exactFingerprintDue = forceReconciliation || nowNanos >= nextExactFingerprintNanos;
        if (exactFingerprintDue && !exactFingerprintActive) {
            startExactFingerprintCycle();
        }
        if (exactFingerprintActive) {
            IOException exactFailure = advanceExactFingerprintCycle(signals, nowNanos);
            if (exactFailure != null) {
                reconciliationSucceeded = false;
                if (failure == null) {
                    failure = exactFailure;
                } else {
                    failure.addSuppressed(exactFailure);
                }
            }
        } else if (exactFingerprintDue) {
            nextExactFingerprintNanos = saturatingAdd(nowNanos, reconciliationIntervalNanos);
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
        resetExactFingerprintProgress();
        if (baseline == null) {
            knownFingerprints.clear();
            signaledFingerprintBaselines.clear();
            return;
        }
        for (Map.Entry<Path, JarSnapshotStager.FileStamp> entry : baseline.entrySet()) {
            Path normalized = normalizeJarPath(entry.getKey());
            if (normalized != null && entry.getValue() != null) {
                knownFiles.put(normalized, entry.getValue());
            }
        }
        knownFingerprints.keySet().retainAll(knownFiles.keySet());
        signaledFingerprintBaselines.retainAll(knownFiles.keySet());
    }

    public void synchronize(Path path) {
        Path normalized = normalizeJarPath(path);
        if (normalized == null) {
            return;
        }

        markSignaledFingerprintBaseline(normalized);
        try {
            JarSnapshotStager.FileStamp stamp = readStamp(normalized);
            if (stamp == null) {
                removeKnownPath(normalized);
            } else {
                knownFiles.put(normalized, stamp);
            }
        } catch (IOException ignored) {
        }
    }

    public void synchronizeFingerprint(Path path, String fingerprint) {
        Path normalized = normalizeJarPath(path);
        if (normalized == null || fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        knownFingerprints.put(normalized, fingerprint);
        signaledFingerprintBaselines.remove(normalized);
        if (exactFingerprintProgress != null && exactFingerprintProgress.path().equals(normalized)) {
            exactFingerprintProgress = null;
        }
    }

    public boolean isNativeWatchActive() {
        return watchKey != null && watchKey.isValid();
    }

    @Override
    public void close() {
        closeService();
        knownFiles.clear();
        knownFingerprints.clear();
        signaledFingerprintBaselines.clear();
        resetExactFingerprintProgress();
    }

    private boolean drainNativeEvents(Map<Path, Boolean> signals) {
        WatchService service = watchService;
        if (service == null) {
            return false;
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
                markSignaledFingerprintBaseline(unresolvedPath);
            }
            for (Map.Entry<Path, JarSnapshotStager.FileStamp> entry : current.entrySet()) {
                JarSnapshotStager.FileStamp previous = knownFiles.get(entry.getKey());
                if (!entry.getValue().equals(previous)) {
                    signals.putIfAbsent(entry.getKey(), false);
                    markSignaledFingerprintBaseline(entry.getKey());
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
        Set<Path> removedFingerprints = new HashSet<>(knownFingerprints.keySet());
        removedFingerprints.removeAll(current.keySet());
        for (Path removed : removedFingerprints) {
            removeKnownFingerprint(removed);
        }
        signaledFingerprintBaselines.retainAll(current.keySet());
        if (exactFingerprintProgress != null && !current.containsKey(exactFingerprintProgress.path())) {
            exactFingerprintProgress = null;
        }
        if (directorySnapshot.failure() != null) {
            throw new PartialReconciliationException(directorySnapshot.failure());
        }
    }

    private void startExactFingerprintCycle() {
        if (knownFiles.isEmpty()) {
            resetExactFingerprintProgress();
            return;
        }
        List<Path> paths = new ArrayList<>(knownFiles.keySet());
        paths.sort(Comparator.comparing(Path::toString));
        exactFingerprintPaths = List.copyOf(paths);
        exactFingerprintIndex = 0;
        exactFingerprintProgress = null;
        exactFingerprintActive = true;
    }

    private IOException advanceExactFingerprintCycle(Map<Path, Boolean> signals, long nowNanos) {
        long startedAtNanos = System.nanoTime();
        long bytesRead = 0L;
        int filesStarted = exactFingerprintProgress == null ? 0 : 1;
        IOException failure = null;

        while (bytesRead < exactFingerprintByteBudget
                && (exactFingerprintProgress != null || filesStarted < exactFingerprintFileBudget)) {
            if (bytesRead > 0L
                    && System.nanoTime() - startedAtNanos >= EXACT_FINGERPRINT_TIME_BUDGET_NANOS) {
                break;
            }
            if (exactFingerprintProgress == null) {
                if (filesStarted >= exactFingerprintFileBudget) {
                    break;
                }
                Path path = nextExactFingerprintPath();
                if (path == null) {
                    finishExactFingerprintCycle(nowNanos);
                    break;
                }
                filesStarted++;
                try {
                    JarSnapshotStager.FileStamp stamp = readStamp(path);
                    if (stamp == null) {
                        if (knownFiles.containsKey(path)) {
                            signals.putIfAbsent(path, false);
                        }
                        removeKnownPath(path);
                        continue;
                    }
                    exactFingerprintProgress = new ExactFingerprintProgress(
                            path,
                            stamp,
                            stamp.size(),
                            0L,
                            sha256()
                    );
                } catch (IOException exception) {
                    signals.putIfAbsent(path, false);
                    markSignaledFingerprintBaseline(path);
                    failure = appendFailure(failure, exception);
                    continue;
                }
            }

            long remainingBytes = exactFingerprintByteBudget - bytesRead;
            ExactFingerprintAdvance advance = advanceExactFingerprint(
                    exactFingerprintProgress,
                    remainingBytes,
                    startedAtNanos
            );
            bytesRead += advance.bytesRead();
            Path completedPath = exactFingerprintProgress.path();
            exactFingerprintProgress = advance.progress();
            if (advance.failure() != null) {
                signals.putIfAbsent(completedPath, false);
                markSignaledFingerprintBaseline(completedPath);
                failure = appendFailure(failure, advance.failure());
            } else if (advance.observedStamp() != null || advance.missing()) {
                signalObservedExactChange(completedPath, advance.observedStamp(), signals);
            } else if (advance.fingerprint() != null) {
                completeExactFingerprint(completedPath, advance.fingerprint(), signals);
            }
            if (exactFingerprintProgress == null && exactFingerprintIndex >= exactFingerprintPaths.size()) {
                finishExactFingerprintCycle(nowNanos);
                break;
            }
            if (advance.bytesRead() == 0L && exactFingerprintProgress != null) {
                break;
            }
        }
        return failure;
    }

    private Path nextExactFingerprintPath() {
        while (exactFingerprintIndex < exactFingerprintPaths.size()) {
            Path path = exactFingerprintPaths.get(exactFingerprintIndex++);
            if (knownFiles.containsKey(path)) {
                return path;
            }
        }
        return null;
    }

    private ExactFingerprintAdvance advanceExactFingerprint(ExactFingerprintProgress progress,
                                                            long byteBudget,
                                                            long startedAtNanos) {
        try {
            JarSnapshotStager.FileStamp before = readStamp(progress.path());
            if (before == null) {
                return ExactFingerprintAdvance.missingResult();
            }
            if (!before.equals(progress.stamp())) {
                return ExactFingerprintAdvance.changedResult(before);
            }

            long consumed = 0L;
            long offset = progress.offset();
            try (FileChannel channel = FileChannel.open(progress.path(), StandardOpenOption.READ)) {
                channel.position(offset);
                while (consumed < byteBudget && offset < progress.size()) {
                    if (consumed > 0L
                            && System.nanoTime() - startedAtNanos >= EXACT_FINGERPRINT_TIME_BUDGET_NANOS) {
                        break;
                    }
                    int limit = (int) Math.min(
                            exactFingerprintBuffer.capacity(),
                            Math.min(byteBudget - consumed, progress.size() - offset)
                    );
                    exactFingerprintBuffer.clear();
                    exactFingerprintBuffer.limit(limit);
                    int read = channel.read(exactFingerprintBuffer);
                    if (read <= 0) {
                        break;
                    }
                    progress.digest().update(exactFingerprintBuffer.array(), 0, read);
                    consumed += read;
                    offset += read;
                }
            }

            JarSnapshotStager.FileStamp after = readStamp(progress.path());
            if (after == null) {
                return new ExactFingerprintAdvance(null, null, null, true, consumed, null);
            }
            if (!after.equals(progress.stamp())) {
                return new ExactFingerprintAdvance(null, null, after, false, consumed, null);
            }
            if (offset < progress.size()) {
                return new ExactFingerprintAdvance(
                        new ExactFingerprintProgress(
                                progress.path(),
                                progress.stamp(),
                                progress.size(),
                                offset,
                                progress.digest()
                        ),
                        null,
                        null,
                        false,
                        consumed,
                        null
                );
            }
            String fingerprint = HexFormat.of().formatHex(progress.digest().digest());
            return new ExactFingerprintAdvance(null, fingerprint, null, false, consumed, null);
        } catch (IOException exception) {
            return new ExactFingerprintAdvance(null, null, null, false, 0L, exception);
        }
    }

    private void completeExactFingerprint(Path path, String fingerprint, Map<Path, Boolean> signals) {
        String previous = knownFingerprints.put(path, fingerprint);
        boolean alreadySignaled = signaledFingerprintBaselines.remove(path);
        if (previous != null && !previous.equals(fingerprint) && !alreadySignaled) {
            signals.putIfAbsent(path, false);
        }
    }

    private void signalObservedExactChange(Path path,
                                           JarSnapshotStager.FileStamp observedStamp,
                                           Map<Path, Boolean> signals) {
        signals.putIfAbsent(path, false);
        if (observedStamp == null) {
            removeKnownPath(path);
            return;
        }
        knownFiles.put(path, observedStamp);
        markSignaledFingerprintBaseline(path);
    }

    private void markSignaledFingerprintBaseline(Path path) {
        signaledFingerprintBaselines.add(path);
        if (exactFingerprintProgress != null && exactFingerprintProgress.path().equals(path)) {
            exactFingerprintProgress = null;
        }
    }

    private void removeKnownPath(Path path) {
        knownFiles.remove(path);
        removeKnownFingerprint(path);
    }

    private void removeKnownFingerprint(Path path) {
        knownFingerprints.remove(path);
        signaledFingerprintBaselines.remove(path);
        if (exactFingerprintProgress != null && exactFingerprintProgress.path().equals(path)) {
            exactFingerprintProgress = null;
        }
    }

    private void resetExactFingerprintCycle(long nowNanos) {
        resetExactFingerprintProgress();
        nextExactFingerprintNanos = saturatingAdd(nowNanos, reconciliationIntervalNanos);
    }

    private void finishExactFingerprintCycle(long nowNanos) {
        resetExactFingerprintProgress();
        long completedAtNanos = Math.max(nowNanos, completionClock.getAsLong());
        nextExactFingerprintNanos = saturatingAdd(completedAtNanos, reconciliationIntervalNanos);
    }

    private void resetExactFingerprintProgress() {
        exactFingerprintPaths = List.of();
        exactFingerprintIndex = 0;
        exactFingerprintProgress = null;
        exactFingerprintActive = false;
    }

    private MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private IOException appendFailure(IOException previous, IOException next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
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
        if (!nativeWatchingEnabled || watchKey != null && watchKey.isValid()) {
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

    private record ExactFingerprintProgress(Path path,
                                            JarSnapshotStager.FileStamp stamp,
                                            long size,
                                            long offset,
                                            MessageDigest digest) {
    }

    private record ExactFingerprintAdvance(ExactFingerprintProgress progress,
                                           String fingerprint,
                                           JarSnapshotStager.FileStamp observedStamp,
                                           boolean missing,
                                           long bytesRead,
                                           IOException failure) {
        private static ExactFingerprintAdvance missingResult() {
            return new ExactFingerprintAdvance(null, null, null, true, 0L, null);
        }

        private static ExactFingerprintAdvance changedResult(JarSnapshotStager.FileStamp observedStamp) {
            return new ExactFingerprintAdvance(null, null, observedStamp, false, 0L, null);
        }
    }

    private static final class PartialReconciliationException extends IOException {
        private PartialReconciliationException(IOException cause) {
            super("One or more plugin jars could not be inspected", cause);
        }
    }
}
