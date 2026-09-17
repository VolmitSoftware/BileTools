package com.volmit.bile.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.volmit.bile.watch.AutomaticReloadQueue;
import com.volmit.bile.watch.DeletionGraceQueue;
import com.volmit.bile.watch.JarSnapshotStager;
import com.volmit.bile.watch.PluginDependencyOrder;
import com.volmit.bile.watch.PluginJarDirectoryWatcher;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class VelocityWatchOrchestrator implements AutoCloseable {
    private static final long RECONCILIATION_NANOS = TimeUnit.MILLISECONDS.toNanos(2500L);
    private static final long DELETION_GRACE_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long BATCH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long EXECUTOR_SHUTDOWN_SECONDS = 5L;
    private static final int STAGING_RETRY_LIMIT = 18;
    private static final String DEFAULT_SELF_ID = "biletools";
    private static final String STAGING_DIRECTORY_NAME = "watcher-stage";

    private final ProxyServer proxy;
    private final Object selfInstance;
    private final Logger logger;
    private final Path pluginsDirectory;
    private final Path stagingDirectory;
    private final VelocityConfig config;
    private final VelocityPluginHotloader hotloader;
    private final ProxyMessages messages;
    private final LongSupplier clock;
    private final Function<Path, Optional<VelocityPluginDescriptor>> descriptorReader;
    private final String selfId;
    private final ExecutorService pluginOperations;
    private final ExecutorService snapshotOperations;
    private final AtomicReference<Thread> pluginOperationsThread = new AtomicReference<>();
    private final AutomaticReloadQueue reloadQueue = new AutomaticReloadQueue(BATCH_INTERVAL_NANOS);
    private final DeletionGraceQueue deletionQueue = new DeletionGraceQueue(DELETION_GRACE_NANOS);
    private final Map<Path, PendingObservation> pendingObservations = new LinkedHashMap<>();
    private final Map<Path, Long> latestGenerations = new HashMap<>();
    private final Map<Path, String> readFailures = new HashMap<>();
    private final Set<Path> unresolvedSignals = new LinkedHashSet<>();
    private final Map<Path, VelocityPluginDescriptor> trackedDescriptors = new ConcurrentHashMap<>();
    private final Map<Path, String> appliedFingerprints = new ConcurrentHashMap<>();
    private final Map<Path, String> announcedSelfFingerprints = new HashMap<>();
    private final Map<Path, String> announcedForeignFingerprints = new HashMap<>();
    private final Queue<StageCompletion> completedStages = new ConcurrentLinkedQueue<>();
    private final Queue<FingerprintSync> pendingFingerprintSyncs = new ConcurrentLinkedQueue<>();
    private final Queue<Path> pendingForgets = new ConcurrentLinkedQueue<>();
    private final Set<String> dirtyPlugins = ConcurrentHashMap.newKeySet();
    private final AtomicLong reloadsTotal = new AtomicLong();

    private PluginJarDirectoryWatcher watcher;
    private volatile ScheduledTask tickTask;
    private long nextGeneration;
    private int activeStagingTasks;
    private String lastWatcherFailure;
    private boolean busyCadence;
    private volatile int watchedJars;
    private volatile long lastReloadMillis;
    private volatile boolean watcherHealthy;
    private volatile boolean running;

    public VelocityWatchOrchestrator(ProxyServer proxy,
                                     Object selfInstance,
                                     Logger logger,
                                     Path pluginsDirectory,
                                     Path dataDirectory,
                                     VelocityConfig config,
                                     VelocityPluginHotloader hotloader,
                                     ProxyMessages messages) {
        this(proxy, selfInstance, logger, pluginsDirectory, dataDirectory, config, hotloader, messages,
                System::nanoTime, VelocityPluginDescriptor::tryRead);
    }

    VelocityWatchOrchestrator(ProxyServer proxy,
                              Object selfInstance,
                              Logger logger,
                              Path pluginsDirectory,
                              Path dataDirectory,
                              VelocityConfig config,
                              VelocityPluginHotloader hotloader,
                              ProxyMessages messages,
                              LongSupplier clock,
                              Function<Path, Optional<VelocityPluginDescriptor>> descriptorReader) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.selfInstance = Objects.requireNonNull(selfInstance, "selfInstance");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.pluginsDirectory = Objects.requireNonNull(pluginsDirectory, "pluginsDirectory").toAbsolutePath().normalize();
        this.stagingDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve(STAGING_DIRECTORY_NAME);
        this.config = Objects.requireNonNull(config, "config");
        this.hotloader = Objects.requireNonNull(hotloader, "hotloader");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.descriptorReader = Objects.requireNonNull(descriptorReader, "descriptorReader");
        this.selfId = resolveSelfId();
        this.pluginOperations = Executors.newSingleThreadExecutor(
                operationsThreadFactory("BileTools-PluginOps", pluginOperationsThread));
        this.snapshotOperations = Executors.newSingleThreadExecutor(
                operationsThreadFactory("BileTools-Snapshot", new AtomicReference<>()));
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        watcher = new PluginJarDirectoryWatcher(pluginsDirectory, RECONCILIATION_NANOS);
        try {
            watcher.start(clock.getAsLong());
            watcherHealthy = true;
        } catch (IOException exception) {
            watcherHealthy = false;
            logger.warn("Plugin jar watching could not start for {}; reconciliation will retry", pluginsDirectory, exception);
        }
        seedTrackedDescriptors();
        watchedJars = watcher.snapshot().size();
        scheduleTick(config.idlePollMillis());
        logger.info("Watching {} for proxy plugin jar changes ({} jars).", pluginsDirectory, watchedJars);
    }

    public CompletableFuture<String> manualLoad(String idOrFile) {
        String argument = Objects.requireNonNull(idOrFile, "idOrFile").trim();
        String pluginId = resolvePluginId(argument);
        return announce(dispatch(pluginId, () -> {
            Path source = resolveManualSource(argument);
            PluginContainer container = hotloader.load(source);
            String id = container.getDescription().getId();
            recordApplied(source);
            clearDirty(pluginId);
            clearDirty(id);
            return "Loaded " + id + " from " + source.getFileName() + ".";
        }));
    }

    public CompletableFuture<String> manualUnload(String id) {
        String argument = Objects.requireNonNull(id, "id").trim();
        String pluginId = resolvePluginId(argument);
        return announce(dispatch(pluginId, () -> {
            Path source = hotloader.sourceOf(pluginId).orElse(null);
            Set<Path> dependents = hotloader.unload(pluginId, UnloadReason.HOT_UNLOAD);
            clearDirty(pluginId);
            if (source != null) {
                forgetPath(source);
            }
            if (dependents.isEmpty()) {
                return "Unloaded " + pluginId + ".";
            }
            return "Unloaded " + pluginId + " and " + dependents.size() + " dependent plugins.";
        }));
    }

    public CompletableFuture<String> manualReload(String id) {
        String argument = Objects.requireNonNull(id, "id").trim();
        String pluginId = resolvePluginId(argument);
        return announce(dispatch(pluginId, () -> {
            Optional<PluginContainer> container = hotloader.find(pluginId);
            if (container.isEmpty()) {
                throw new HotloadException(HotloadException.Kind.NOT_LOADED, "No loaded plugin with id " + argument + ".");
            }
            String resolvedId = container.get().getDescription().getId();
            Path source = recordedSource(resolvedId);
            hotloader.reload(resolvedId, source);
            if (source != null) {
                recordApplied(source);
            }
            clearDirty(pluginId);
            clearDirty(resolvedId);
            return "Reloaded " + resolvedId + ".";
        }));
    }

    public OrchestratorSnapshot snapshot() {
        return new OrchestratorSnapshot(watchedJars, Set.copyOf(dirtyPlugins), reloadsTotal.get(),
                lastReloadMillis, watcherHealthy);
    }

    @Override
    public void close() {
        running = false;
        ScheduledTask task = tickTask;
        tickTask = null;
        if (task != null) {
            task.cancel();
        }
        for (AutomaticReloadQueue.Candidate candidate : reloadQueue.clear()) {
            candidate.discardSnapshot();
        }
        deletionQueue.clear();
        shutdown(snapshotOperations);
        shutdown(pluginOperations);
        StageCompletion completion;
        while ((completion = completedStages.poll()) != null) {
            if (completion.stagedJar() != null) {
                completion.stagedJar().delete();
            }
        }
        PluginJarDirectoryWatcher current = watcher;
        watcher = null;
        if (current != null) {
            current.close();
        }
    }

    void tick() {
        if (!running) {
            return;
        }
        long nowNanos = clock.getAsLong();
        try {
            applyWatcherUpdates(nowNanos);
            pollWatcher(nowNanos);
            retryUnresolvedSignals(nowNanos);
            drainStageCompletions(nowNanos);
            advancePendingObservations(nowNanos);
            expireDeletionTombstones(nowNanos);
            startBatch(nowNanos);
            updateCadence();
        } catch (RuntimeException exception) {
            logger.error("Plugin jar watcher tick failed", exception);
        }
    }

    int activeStagingTaskCount() {
        return activeStagingTasks;
    }

    int completedStageCount() {
        return completedStages.size();
    }

    boolean isBatchInFlight() {
        return reloadQueue.isBatchInFlight();
    }

    private void scheduleTick(long pollMillis) {
        ScheduledTask previous = tickTask;
        if (previous != null) {
            previous.cancel();
        }
        tickTask = proxy.getScheduler().buildTask(selfInstance, this::tick)
                .repeat(pollMillis, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void updateCadence() {
        boolean busy = !pendingObservations.isEmpty()
                || activeStagingTasks > 0
                || !completedStages.isEmpty()
                || !unresolvedSignals.isEmpty()
                || !deletionQueue.isEmpty()
                || reloadQueue.hasWork();
        if (busy == busyCadence) {
            return;
        }
        busyCadence = busy;
        scheduleTick(busy ? config.activePollMillis() : config.idlePollMillis());
    }

    private void applyWatcherUpdates(long nowNanos) {
        FingerprintSync sync;
        while ((sync = pendingFingerprintSyncs.poll()) != null) {
            if (watcher == null) {
                continue;
            }
            if (!Files.isRegularFile(sync.source())) {
                handleJarSignal(sync.source(), nowNanos);
                continue;
            }
            watcher.synchronize(sync.source());
            watcher.synchronizeFingerprint(sync.source(), sync.fingerprint());
        }
        Path forgotten;
        while ((forgotten = pendingForgets.poll()) != null) {
            latestGenerations.remove(forgotten);
            pendingObservations.remove(forgotten);
            announcedSelfFingerprints.remove(forgotten);
            announcedForeignFingerprints.remove(forgotten);
        }
    }

    private void seedTrackedDescriptors() {
        if (watcher == null) {
            return;
        }
        for (Path known : watcher.snapshot().keySet()) {
            Path path = known.toAbsolutePath().normalize();
            readDescriptor(path).ifPresent(descriptor -> trackedDescriptors.put(path, descriptor));
        }
    }

    private void pollWatcher(long nowNanos) {
        if (watcher == null) {
            return;
        }
        PluginJarDirectoryWatcher.PollResult result = watcher.poll(nowNanos);
        IOException failure = result.failure();
        if (failure == null) {
            lastWatcherFailure = null;
        } else {
            String message = rootMessage(failure);
            if (!message.equals(lastWatcherFailure)) {
                logger.warn("Plugin jar watcher reconciliation failed", failure);
                lastWatcherFailure = message;
            }
        }
        watcherHealthy = result.reconciliationSucceeded();
        for (PluginJarDirectoryWatcher.Signal signal : result.signals()) {
            handleJarSignal(signal.path(), nowNanos);
        }
        watchedJars = watcher.snapshot().size();
    }

    private void handleJarSignal(Path signalPath, long nowNanos) {
        Path path = signalPath.toAbsolutePath().normalize();
        FileStampProbe probe = probeFileStamp(path);
        if (probe.failure() != null) {
            unresolvedSignals.add(path);
            return;
        }
        unresolvedSignals.remove(path);
        if (probe.stamp() == null) {
            markJarMissing(path, nowNanos);
            return;
        }

        deletionQueue.cancel(path);
        long generation = ++nextGeneration;
        latestGenerations.put(path, generation);
        pendingObservations.put(path, new PendingObservation(probe.stamp(), generation, 0, STAGING_RETRY_LIMIT));
    }

    private void retryUnresolvedSignals(long nowNanos) {
        for (Path path : new ArrayList<>(unresolvedSignals)) {
            handleJarSignal(path, nowNanos);
        }
    }

    private void advancePendingObservations(long nowNanos) {
        int requiredPasses = config.fingerprintDebouncePolls();
        for (Map.Entry<Path, PendingObservation> entry : new ArrayList<>(pendingObservations.entrySet())) {
            Path path = entry.getKey();
            PendingObservation observation = entry.getValue();
            PendingObservation current = pendingObservations.get(path);
            if (current == null || current.generation() != observation.generation()) {
                continue;
            }

            FileStampProbe probe = probeFileStamp(path);
            if (probe.failure() != null) {
                continue;
            }
            if (probe.stamp() == null) {
                markJarMissing(path, nowNanos);
                continue;
            }
            if (!probe.stamp().equals(observation.stamp())) {
                long generation = ++nextGeneration;
                latestGenerations.put(path, generation);
                pendingObservations.put(path, new PendingObservation(
                        probe.stamp(), generation, 0, STAGING_RETRY_LIMIT));
                continue;
            }

            int stablePasses = observation.stablePasses() + 1;
            if (stablePasses < requiredPasses) {
                pendingObservations.put(path, new PendingObservation(
                        observation.stamp(), observation.generation(), stablePasses, observation.attemptsRemaining()));
                continue;
            }

            pendingObservations.remove(path);
            startSnapshotStage(path, observation);
        }
    }

    private void startSnapshotStage(Path path, PendingObservation observation) {
        activeStagingTasks++;
        try {
            snapshotOperations.execute(() -> {
                JarSnapshotStager.StagedJar stagedJar = null;
                Throwable failure = null;
                try {
                    stagedJar = JarSnapshotStager.stage(path, stagingDirectory, observation.generation(), List.of());
                } catch (Throwable throwable) {
                    failure = throwable;
                }
                completedStages.add(new StageCompletion(path, observation, stagedJar, failure));
            });
        } catch (RejectedExecutionException exception) {
            activeStagingTasks = Math.max(0, activeStagingTasks - 1);
            if (running && observation.attemptsRemaining() > 1) {
                pendingObservations.put(path, new PendingObservation(
                        observation.stamp(), observation.generation(), 0, observation.attemptsRemaining() - 1));
            }
        }
    }

    private void drainStageCompletions(long nowNanos) {
        StageCompletion completion;
        while ((completion = completedStages.poll()) != null) {
            activeStagingTasks = Math.max(0, activeStagingTasks - 1);
            completeSnapshotStage(completion, nowNanos);
        }
    }

    private void completeSnapshotStage(StageCompletion completion, long nowNanos) {
        Path path = completion.path();
        PendingObservation observation = completion.observation();
        Long latest = latestGenerations.get(path);
        if (latest == null || latest != observation.generation()) {
            discard(completion.stagedJar());
            return;
        }
        if (completion.failure() != null || completion.stagedJar() == null) {
            retrySnapshotStage(path, observation, completion.failure(), nowNanos);
            return;
        }

        JarSnapshotStager.StagedJar stagedJar = completion.stagedJar();
        FileStampProbe probe = probeFileStamp(path);
        if (probe.failure() != null) {
            stagedJar.delete();
            retrySnapshotStage(path, observation, probe.failure(), nowNanos);
            return;
        }
        if (probe.stamp() == null) {
            stagedJar.delete();
            markJarMissing(path, nowNanos);
            return;
        }
        if (!probe.stamp().equals(stagedJar.sourceStamp())) {
            stagedJar.delete();
            long generation = ++nextGeneration;
            latestGenerations.put(path, generation);
            pendingObservations.put(path, new PendingObservation(
                    probe.stamp(), generation, 0, STAGING_RETRY_LIMIT));
            return;
        }
        acceptStagedJar(path, stagedJar);
    }

    private void retrySnapshotStage(Path path, PendingObservation observation, Throwable failure, long nowNanos) {
        Long latest = latestGenerations.get(path);
        if (latest == null || latest != observation.generation()) {
            return;
        }
        FileStampProbe probe = probeFileStamp(path);
        if (probe.stamp() == null && probe.failure() == null) {
            markJarMissing(path, nowNanos);
            return;
        }
        if (observation.attemptsRemaining() <= 1) {
            unresolvedSignals.add(path);
            logger.warn("Plugin jar {} could not be staged after repeated attempts", path.getFileName(), failure);
            return;
        }
        pendingObservations.put(path, new PendingObservation(
                observation.stamp(), observation.generation(), 0, observation.attemptsRemaining() - 1));
    }

    private void acceptStagedJar(Path path, JarSnapshotStager.StagedJar stagedJar) {
        Optional<VelocityPluginDescriptor> descriptor = readDescriptor(stagedJar.staged());
        if (descriptor.isEmpty()) {
            announceOnce(announcedForeignFingerprints, path, stagedJar.sha256(),
                    () -> logger.debug("Ignoring {}: it carries no velocity-plugin.json", path.getFileName()));
            stagedJar.delete();
            return;
        }

        VelocityPluginDescriptor read = descriptor.get();
        String id = read.id().toLowerCase(Locale.ROOT);
        trackedDescriptors.put(path, read);
        deletionQueue.cancelPlugin(id, path);

        if (id.equals(selfId)) {
            announceOnce(announcedSelfFingerprints, path, stagedJar.sha256(),
                    () -> logger.warn("BileTools changed on disk; restart the proxy to apply"));
            stagedJar.delete();
            return;
        }
        if (stagedJar.sha256().equals(appliedFingerprints.get(path))) {
            pendingFingerprintSyncs.add(new FingerprintSync(path, stagedJar.sha256()));
            stagedJar.delete();
            submitCandidate(new AutomaticReloadQueue.Candidate(
                    id, path, stagedJar.generation(), AutomaticReloadQueue.Action.NOOP, null, false));
            return;
        }
        if (dirtyPlugins.contains(id)) {
            logger.warn("Skipping automatic reload for dirty plugin {} until a manual command succeeds", id);
            stagedJar.delete();
            return;
        }
        if (!automaticAllowed(id)) {
            logger.debug("Skipping automatic lifecycle for filtered plugin {}", id);
            stagedJar.delete();
            return;
        }

        submitCandidate(new AutomaticReloadQueue.Candidate(
                id, path, stagedJar.generation(), AutomaticReloadQueue.Action.UPSERT, stagedJar, false));
    }

    private void markJarMissing(Path path, long nowNanos) {
        pendingObservations.remove(path);
        if (deletionQueue.contains(path)) {
            return;
        }
        VelocityPluginDescriptor descriptor = trackedDescriptors.get(path);
        if (descriptor == null || descriptor.id().toLowerCase(Locale.ROOT).equals(selfId)) {
            return;
        }
        long generation = ++nextGeneration;
        latestGenerations.put(path, generation);
        deletionQueue.schedule(path, descriptor.id(), generation, nowNanos);
        logger.debug("Plugin jar removed; waiting three seconds for recreation: {}", path.getFileName());
    }

    private void expireDeletionTombstones(long nowNanos) {
        for (DeletionGraceQueue.Tombstone tombstone : deletionQueue.snapshot()) {
            FileStampProbe probe = probeFileStamp(tombstone.path());
            if (probe.failure() != null) {
                deletionQueue.cancel(tombstone.path());
                deletionQueue.schedule(tombstone.path(), tombstone.pluginName(), tombstone.generation(), nowNanos);
                continue;
            }
            if (probe.stamp() != null) {
                deletionQueue.cancel(tombstone.path());
                handleJarSignal(tombstone.path(), nowNanos);
            }
        }

        for (DeletionGraceQueue.Tombstone tombstone : deletionQueue.expire(nowNanos)) {
            String id = tombstone.pluginName().toLowerCase(Locale.ROOT);
            if (dirtyPlugins.contains(id) || !automaticAllowed(id)) {
                logger.debug("Skipping automatic unload for {}", id);
                forgetPath(tombstone.path());
                continue;
            }
            submitCandidate(new AutomaticReloadQueue.Candidate(
                    id, tombstone.path(), tombstone.generation(), AutomaticReloadQueue.Action.UNLOAD, null, false));
        }
    }

    private void startBatch(long nowNanos) {
        Optional<AutomaticReloadQueue.Batch> batch = reloadQueue.beginBatch(nowNanos);
        if (batch.isEmpty()) {
            return;
        }
        List<AutomaticReloadQueue.Candidate> ordered = orderCandidates(batch.get().candidates());
        logger.info("Automatic proxy lifecycle batch ({}): {}", ordered.size(), String.join(", ", idsOf(ordered)));

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (AutomaticReloadQueue.Candidate candidate : ordered) {
            chain = chain.thenCompose(ignored -> runCandidate(candidate));
        }
        chain.whenComplete((ignored, failure) -> finishBatch(ordered, failure));
    }

    private void finishBatch(List<AutomaticReloadQueue.Candidate> ordered, Throwable failure) {
        if (failure != null) {
            logger.error("Automatic proxy lifecycle batch did not finish cleanly", unwrap(failure));
        }
        for (AutomaticReloadQueue.Candidate candidate : ordered) {
            candidate.discardSnapshot();
        }
        reloadQueue.completeBatch(clock.getAsLong());
    }

    private CompletableFuture<Void> runCandidate(AutomaticReloadQueue.Candidate candidate) {
        if (candidate.action() == AutomaticReloadQueue.Action.NOOP) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<String> operation = candidate.action() == AutomaticReloadQueue.Action.UNLOAD
                ? dispatch(candidate.pluginName(), () -> applyAutomaticUnload(candidate))
                : dispatch(candidate.pluginName(), () -> applyAutomaticUpsert(candidate));
        CompletableFuture<Void> completed = new CompletableFuture<>();
        operation.whenComplete((message, failure) -> {
            try {
                if (failure == null) {
                    reloadsTotal.incrementAndGet();
                    lastReloadMillis = System.currentTimeMillis();
                    messages.notifyOperators(messages.success(message));
                }
            } catch (RuntimeException exception) {
                logger.error("Could not announce the lifecycle result for {}", candidate.pluginName(), exception);
            } finally {
                completed.complete(null);
            }
        });
        return completed;
    }

    private String applyAutomaticUpsert(AutomaticReloadQueue.Candidate candidate) throws HotloadException {
        String id = candidate.pluginName();
        Path origin = candidate.source();
        Path staged = candidate.stagedJar().staged();
        boolean loaded = hotloader.find(id).isPresent();
        if (loaded) {
            hotloader.reload(id, staged, origin);
        } else {
            hotloader.load(staged, origin);
        }
        appliedFingerprints.put(origin, candidate.stagedJar().sha256());
        pendingFingerprintSyncs.add(new FingerprintSync(origin, candidate.stagedJar().sha256()));
        clearDirty(id);
        return loaded ? "Reloaded " + id + "." : "Loaded " + id + ".";
    }

    private String applyAutomaticUnload(AutomaticReloadQueue.Candidate candidate) throws HotloadException {
        String id = candidate.pluginName();
        hotloader.unload(id, UnloadReason.HOT_UNLOAD);
        forgetPath(candidate.source());
        return "Unloaded " + id + " after its jar was removed.";
    }

    private List<AutomaticReloadQueue.Candidate> orderCandidates(List<AutomaticReloadQueue.Candidate> candidates) {
        Map<String, AutomaticReloadQueue.Candidate> unloads = new LinkedHashMap<>();
        Map<String, AutomaticReloadQueue.Candidate> upserts = new LinkedHashMap<>();
        for (AutomaticReloadQueue.Candidate candidate : candidates) {
            Map<String, AutomaticReloadQueue.Candidate> target =
                    candidate.action() == AutomaticReloadQueue.Action.UNLOAD ? unloads : upserts;
            target.put(candidate.pluginName().toLowerCase(Locale.ROOT), candidate);
        }

        List<String> unloadOrder = PluginDependencyOrder.order(unloads.keySet(),
                id -> dependenciesOf(unloads.get(id.toLowerCase(Locale.ROOT))), id -> List.of());
        Collections.reverse(unloadOrder);
        List<String> upsertOrder = PluginDependencyOrder.order(upserts.keySet(),
                id -> dependenciesOf(upserts.get(id.toLowerCase(Locale.ROOT))), id -> List.of());

        List<AutomaticReloadQueue.Candidate> ordered = new ArrayList<>(candidates.size());
        for (String id : unloadOrder) {
            ordered.add(unloads.get(id.toLowerCase(Locale.ROOT)));
        }
        for (String id : upsertOrder) {
            ordered.add(upserts.get(id.toLowerCase(Locale.ROOT)));
        }
        return ordered;
    }

    private Collection<String> dependenciesOf(AutomaticReloadQueue.Candidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        VelocityPluginDescriptor descriptor = trackedDescriptors.get(candidate.source());
        if (descriptor == null) {
            return List.of();
        }
        List<String> dependencies = new ArrayList<>(
                descriptor.requiredDependencies().size() + descriptor.optionalDependencies().size());
        dependencies.addAll(descriptor.requiredDependencies());
        dependencies.addAll(descriptor.optionalDependencies());
        return dependencies;
    }

    private List<String> idsOf(List<AutomaticReloadQueue.Candidate> candidates) {
        List<String> ids = new ArrayList<>(candidates.size());
        for (AutomaticReloadQueue.Candidate candidate : candidates) {
            ids.add(candidate.pluginName());
        }
        return ids;
    }

    private void submitCandidate(AutomaticReloadQueue.Candidate candidate) {
        AutomaticReloadQueue.Submission submission = reloadQueue.submit(candidate);
        if (submission.discarded() != null) {
            submission.discarded().discardSnapshot();
        }
    }

    private CompletableFuture<String> dispatch(String pluginId, LifecycleCall call) {
        CompletableFuture<String> future = new CompletableFuture<>();
        try {
            pluginOperations.execute(() -> {
                try {
                    future.complete(call.run());
                } catch (HotloadException exception) {
                    reportFailure(pluginId, exception);
                    future.completeExceptionally(exception);
                } catch (Throwable throwable) {
                    markDirty(pluginId);
                    logger.error("Unexpected failure during a lifecycle operation for {}", pluginId, throwable);
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException exception) {
            future.completeExceptionally(new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "BileTools is shutting down; " + pluginId + " was not touched.", exception));
            return future;
        }

        CompletableFuture<String> bounded = future.orTimeout(config.lifecycleTimeoutSeconds(), TimeUnit.SECONDS);
        bounded.whenComplete((message, failure) -> {
            if (unwrap(failure) instanceof TimeoutException) {
                reportTimeout(pluginId);
            }
        });
        return bounded;
    }

    private CompletableFuture<String> announce(CompletableFuture<String> operation) {
        operation.whenComplete((message, failure) -> {
            if (failure != null) {
                return;
            }
            reloadsTotal.incrementAndGet();
            lastReloadMillis = System.currentTimeMillis();
            logger.info(message);
        });
        return operation;
    }

    private void reportFailure(String pluginId, HotloadException exception) {
        if (exception.getCause() != null) {
            logger.warn("Lifecycle failure [{}] for {}: {}", exception.kind(), pluginId, exception.getMessage(),
                    exception.getCause());
        } else {
            logger.warn("Lifecycle failure [{}] for {}: {}", exception.kind(), pluginId, exception.getMessage());
        }
        switch (exception.kind()) {
            case LOAD_FAILED, UNLOAD_FAILED, HEALTH_FAILED, TIMEOUT -> markDirty(pluginId);
            default -> {
            }
        }
    }

    private void reportTimeout(String pluginId) {
        markDirty(pluginId);
        Thread thread = pluginOperationsThread.get();
        StringBuilder trace = new StringBuilder();
        if (thread != null) {
            for (StackTraceElement element : thread.getStackTrace()) {
                trace.append("\n\tat ").append(element);
            }
        }
        logger.error("Lifecycle operation for {} exceeded {} seconds; BileTools-PluginOps is at:{}",
                pluginId, config.lifecycleTimeoutSeconds(), trace);
    }

    private void markDirty(String pluginId) {
        dirtyPlugins.add(pluginId.toLowerCase(Locale.ROOT));
    }

    private void clearDirty(String pluginId) {
        dirtyPlugins.remove(pluginId.toLowerCase(Locale.ROOT));
    }

    private void forgetPath(Path source) {
        Path path = source.toAbsolutePath().normalize();
        VelocityPluginDescriptor descriptor = trackedDescriptors.remove(path);
        appliedFingerprints.remove(path);
        if (descriptor != null) {
            clearDirty(descriptor.id());
        }
        pendingForgets.add(path);
    }

    private Path resolveManualSource(String argument) throws HotloadException {
        Optional<PluginContainer> loaded = hotloader.find(argument);
        if (loaded.isPresent()) {
            Path recorded = recordedSource(loaded.get().getDescription().getId());
            if (recorded != null) {
                return recorded;
            }
        }
        Path candidate = pluginsDirectoryJar(argument);
        if (candidate != null) {
            return candidate;
        }
        throw new HotloadException(HotloadException.Kind.NOT_LOADED,
                "No plugin jar or loaded plugin matches " + argument + ".");
    }

    private String resolvePluginId(String argument) {
        Optional<PluginContainer> loaded = hotloader.find(argument);
        if (loaded.isPresent()) {
            return loaded.get().getDescription().getId().toLowerCase(Locale.ROOT);
        }
        Path candidate = pluginsDirectoryJar(argument);
        if (candidate != null) {
            Optional<VelocityPluginDescriptor> descriptor = readDescriptor(candidate);
            if (descriptor.isPresent()) {
                return descriptor.get().id().toLowerCase(Locale.ROOT);
            }
        }
        String lower = argument.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") ? lower.substring(0, lower.length() - ".jar".length()) : lower;
    }

    private Path pluginsDirectoryJar(String argument) {
        String fileName = argument.toLowerCase(Locale.ROOT).endsWith(".jar") ? argument : argument + ".jar";
        Path candidate = pluginsDirectory.resolve(fileName);
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private Path recordedSource(String id) {
        return hotloader.sourceOf(id).filter(Files::isRegularFile).orElse(null);
    }

    private void recordApplied(Path source) {
        try {
            String fingerprint = JarSnapshotStager.fingerprint(source);
            appliedFingerprints.put(source, fingerprint);
            pendingFingerprintSyncs.add(new FingerprintSync(source, fingerprint));
        } catch (IOException exception) {
            logger.debug("Could not fingerprint {} after a manual command", source.getFileName(), exception);
        }
    }

    private boolean automaticAllowed(String id) {
        List<String> only = config.watcherOnly();
        if (!only.isEmpty() && !containsIgnoreCase(only, id)) {
            return false;
        }
        return !containsIgnoreCase(config.watcherIgnore(), id);
    }

    private boolean containsIgnoreCase(List<String> values, String id) {
        for (String value : values) {
            if (value.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    private void announceOnce(Map<Path, String> announced, Path path, String fingerprint, Runnable announcement) {
        String previous = announced.put(path, fingerprint);
        if (!fingerprint.equals(previous)) {
            announcement.run();
        }
    }

    private Optional<VelocityPluginDescriptor> readDescriptor(Path jar) {
        try {
            return descriptorReader.apply(jar);
        } catch (RuntimeException exception) {
            logger.debug("Could not read a proxy descriptor from {}", jar.getFileName(), exception);
            return Optional.empty();
        }
    }

    private FileStampProbe probeFileStamp(Path path) {
        try {
            JarSnapshotStager.FileStamp stamp = JarSnapshotStager.FileStamp.read(path);
            readFailures.remove(path);
            return new FileStampProbe(stamp, null);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            readFailures.remove(path);
            return new FileStampProbe(null, null);
        } catch (IOException exception) {
            String message = rootMessage(exception);
            if (!message.equals(readFailures.put(path, message))) {
                logger.warn("Cannot inspect plugin jar {}; keeping its current lifecycle state",
                        path.getFileName(), exception);
            }
            return new FileStampProbe(null, exception);
        }
    }

    private String resolveSelfId() {
        try {
            Optional<PluginContainer> container = proxy.getPluginManager().fromInstance(selfInstance);
            if (container.isPresent()) {
                return container.get().getDescription().getId().toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException exception) {
            logger.debug("Could not resolve the BileTools plugin container", exception);
        }
        return DEFAULT_SELF_ID;
    }

    private void discard(JarSnapshotStager.StagedJar stagedJar) {
        if (stagedJar != null) {
            stagedJar.delete();
        }
    }

    private void shutdown(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ThreadFactory operationsThreadFactory(String name, AtomicReference<Thread> holder) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            holder.set(thread);
            return thread;
        };
    }

    private Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface LifecycleCall {
        String run() throws HotloadException;
    }

    private record PendingObservation(JarSnapshotStager.FileStamp stamp,
                                      long generation,
                                      int stablePasses,
                                      int attemptsRemaining) {
    }

    private record StageCompletion(Path path,
                                   PendingObservation observation,
                                   JarSnapshotStager.StagedJar stagedJar,
                                   Throwable failure) {
    }

    private record FileStampProbe(JarSnapshotStager.FileStamp stamp, IOException failure) {
    }

    private record FingerprintSync(Path source, String fingerprint) {
    }

    public record OrchestratorSnapshot(int watchedJars,
                                       Set<String> dirtyPlugins,
                                       long reloadsTotal,
                                       long lastReloadMillis,
                                       boolean watcherHealthy) {
        public OrchestratorSnapshot {
            dirtyPlugins = Set.copyOf(Objects.requireNonNull(dirtyPlugins, "dirtyPlugins"));
        }
    }
}
