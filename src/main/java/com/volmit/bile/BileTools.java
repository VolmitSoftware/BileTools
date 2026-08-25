package com.volmit.bile;

import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentLog;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.volmit.bile.command.BileFancyMenu;
import com.volmit.bile.command.CommandBile;
import com.volmit.bile.config.BileConfig;
import com.volmit.bile.localization.BileLocalization;
import com.volmit.bile.localization.BileMessages;
import com.volmit.bile.watch.AutomaticReloadCompletionHandoff;
import com.volmit.bile.watch.AutomaticReloadQueue;
import com.volmit.bile.watch.DeletionGraceQueue;
import com.volmit.bile.watch.JarSnapshotStager;
import com.volmit.bile.watch.PluginDependencyOrder;
import com.volmit.bile.watch.PluginJarDirectoryWatcher;
import com.volmit.bile.watch.WatcherStateHandoff;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, ReloadAware {
    private static final String ROOT_COMMAND = "biletools";
    private static final String ROOT_PERMISSION = "bile.use";
    private static final int STAGING_RETRY_LIMIT = 18;
    private static final long AUTOMATIC_RELOAD_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long DELETION_GRACE_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long WATCH_RECONCILIATION_NANOS = TimeUnit.MILLISECONDS.toNanos(2500L);
    private static final long STAGING_STALE_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final long WATCHER_HANDOFF_MAX_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10L);
    private static final String AUTOMATIC_RELOAD_COMPLETION_FILE = "watcher-handoff-completion.lock";
    private static final long PLUGIN_OPERATION_TIMEOUT_SECONDS = 120L;
    private static final long REMOTE_DEPLOY_TIMEOUT_SECONDS = 90L;
    // bstats.org plugin id
    private static final int BSTATS_PLUGIN_ID = 33192;
    private static final Logger FALLBACK_LOGGER = Logger.getLogger("BileTools");
    private static final String LOG_DISCRIMINATOR = ComponentLog.discriminator("BileTools", "&a");

    private volatile SlaveBileServer srv;
    private volatile Metrics metrics;
    public static BileTools bile;
    private BileToolsIntegrationService integrationService;
    private final AtomicLong reloadsTotal = new AtomicLong();
    private volatile long lastReloadMs;
    private final Map<Path, PendingObservation> pendingObservations = new HashMap<>();
    private final Map<Path, PendingObservation> activeStageObservations = new HashMap<>();
    private final Map<Path, Long> latestGenerations = new HashMap<>();
    private final Map<Path, String> trackedPluginNames = new HashMap<>();
    private final Map<Path, Map<String, Path>> pendingIdentityReplacements = new HashMap<>();
    private final Map<Path, String> appliedFingerprints = new HashMap<>();
    private final Map<Path, String> fileReadFailures = new HashMap<>();
    private final Set<Path> unresolvedJarSignals = new HashSet<>();
    private final DeletionGraceQueue deletionTombstones = new DeletionGraceQueue(DELETION_GRACE_NANOS);
    private final ConcurrentLinkedQueue<StageCompletion> completedStages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<AutomaticBatchCompletion> completedAutomaticBatches = new ConcurrentLinkedQueue<>();
    private final Set<String> dirtyPlugins = ConcurrentHashMap.newKeySet();
    private final AutomaticReloadQueue automaticReloadQueue = new AutomaticReloadQueue(AUTOMATIC_RELOAD_INTERVAL_NANOS);
    private File folder;
    private File backoff;
    private Sound sx;
    private Path stagingDirectory;
    private Path watcherHandoffFile;
    private Path automaticReloadCompletionFile;
    private PluginJarDirectoryWatcher pluginJarWatcher;
    private long restoredAutomaticBatchDelayNanos;
    private String automaticReloadCompletionFailure;
    private long nextWatchGeneration;
    private volatile int watchedJarCount;
    private int activeStagingTasks;
    private volatile boolean tickerActive;
    private volatile boolean watcherBusy;
    private volatile boolean acceptingWatcherCompletions;
    private String lastWatcherFailure;
    private volatile DirectorRuntimeEngine director;
    private BileLocalization localization;
    private final AtomicBoolean selfReloadQueued = new AtomicBoolean(false);
    private volatile AutomaticReloadQueue.Candidate remoteSelfReloadInProgress;
    private final Set<String> queuedOperationKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService pluginOperationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BileTools-PluginOps");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService remoteDeployExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "BileTools-RemoteDeploy");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService snapshotExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BileTools-Snapshot");
        thread.setDaemon(true);
        return thread;
    });
    public static BileConfig cfg;

    static void debug(Supplier<String> messageSupplier) {
        Logger logger = operatorLogger();
        if (logger.isLoggable(Level.FINE)) {
            ComponentLog.log(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, Level.FINE,
                    ComponentText.literal(messageSupplier.get()), null);
        }
    }

    static void info(String message) {
        ComponentLog.log(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, Level.INFO,
                ComponentText.literal(message), null);
    }

    static void warn(String message) {
        ComponentLog.log(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, Level.WARNING,
                ComponentText.literal(message), null);
    }

    static void warn(String message, Throwable throwable) {
        ComponentLog.log(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, Level.WARNING,
                ComponentText.literal(message), throwable);
    }

    static void severe(String message, Throwable throwable) {
        ComponentLog.log(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, Level.SEVERE,
                ComponentText.literal(message), throwable);
    }

    static void logLegacy(Level level, String message, Throwable throwable) {
        ComponentLog.logLegacy(bile, FALLBACK_LOGGER, LOG_DISCRIMINATOR, level, message, throwable);
    }

    private static Logger operatorLogger() {
        BileTools active = bile;
        if (active == null) {
            return FALLBACK_LOGGER;
        }
        Logger logger = active.getLogger();
        return logger == null ? FALLBACK_LOGGER : logger;
    }

    public static void streamFile(File f, String address, int port, String password) throws IOException {
        streamFile(f, f == null ? null : f.getName(), address, port, password);
    }

    private static void streamFile(File file,
                                   String transferFileName,
                                   String address,
                                   int port,
                                   String password) throws IOException {
        int timeoutMs = cfg == null ? 15_000 : cfg.getRemoteSocketTimeoutMs();
        long maxBytes = cfg == null ? 256L * 1024L * 1024L : cfg.getRemoteMaxTransferBytes();
        RemoteDeployProtocol.streamFile(
                file, transferFileName, address, port, password, timeoutMs, maxBytes);
    }

    private void readTheConfig() throws Exception {
        File f = new File(getDataFolder(), "biletools.yml");
        cfg = BileConfig.load(f);
    }

    @Override
    public void onEnable() {
        preloadSelfHostedArchive();
        cfg = BileConfig.defaults();
        try {
            readTheConfig();
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Unable to read the config...", e);
        }

        bile = this;
        localization = new BileLocalization(getDataFolder(), getLogger(), cfg.getLanguage());
        SplashScreen.print(this);
        getLogger().info("Runtime platform: " + ServerPlatform.summary());
        if (ServerPlatform.isFoliaFamily()) {
            getLogger().info("Folia/Canvas detected: using GlobalRegionScheduler; classic Bukkit scheduler is avoided.");
            getLogger().warning("Hot-reload on Folia/Canvas requires an authored plugin.yml with folia-supported: true.");
        } else if (!ServerPlatform.isPaperFamily()) {
            getLogger().info("Spigot-compatible mode: paper-plugin.yml-only jars are rejected; dual-descriptor jars load through plugin.yml.");
        }

        if (cfg.isRemoteSlaveEnabled()) {
            getLogger().info("Starting Remote Slave Server on *:" + cfg.getRemoteSlavePort());

            try {
                srv = new SlaveBileServer();
                srv.start();
                getLogger().info("Remote Slave Server online!");
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE,
                        "Failed to start Remote Slave Server on *:" + cfg.getRemoteSlavePort(), e);
            }
        }

        BileUtils.recoverRuntimePluginFiles();
        folder = BileUtils.getPluginsFolder();
        stagingDirectory = new File(getDataFolder(), "watcher-stage").toPath();
        watcherHandoffFile = new File(getDataFolder(), "watcher-handoff.bin").toPath();
        automaticReloadCompletionFile = watcherHandoffFile.resolveSibling(AUTOMATIC_RELOAD_COMPLETION_FILE);
        initializePluginWatcher();
        backoff = new File(getDataFolder(), "backoff");
        backoff.mkdirs();
        PluginCommand bileCommand = getCommand(ROOT_COMMAND);
        if (bileCommand != null) {
            bileCommand.setExecutor(this);
            bileCommand.setTabCompleter(this);
            getDirector();
        } else {
            getLogger().warning("Could not register /" + ROOT_COMMAND + " command executor");
        }
        Bukkit.getPluginManager().registerEvents(this, this);

        sx = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;

        tickerActive = true;
        scheduleTicker(10L);

        integrationService = new BileToolsIntegrationService(this);
        integrationService.register();

        setupMetrics();
        applyRestoredAutomaticBatchDelay();
    }

    private void preloadSelfHostedArchive() {
        PluginArchivePreloader.PreloadReport report;
        try {
            report = PluginArchivePreloader.preload(getFile().toPath(), getClass().getClassLoader());
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException(
                    "Cannot preload the BileTools startup archive before enabling file watching", exception);
        }

        for (PluginArchivePreloader.ClassLoadFailure failure : report.requiredFailures()) {
            getLogger().log(Level.SEVERE,
                    "Could not preload required BileTools class " + failure.className(), failure.cause());
        }
        if (!report.requiredFailures().isEmpty()) {
            throw new IllegalStateException("Cannot safely self-host BileTools because "
                    + report.requiredFailures().size() + " required classes could not be preloaded");
        }
        for (PluginArchivePreloader.ClassLoadFailure failure : report.optionalFailures()) {
            getLogger().log(Level.WARNING,
                    "Optional archive class could not be preloaded: " + failure.className(), failure.cause());
        }
        getLogger().info("Preloaded " + report.loadedClasses().size() + " of "
                + report.discoveredClasses().size() + " startup archive classes for in-place self-hosting");
    }

    private void initializePluginWatcher() {
        acceptingWatcherCompletions = true;
        restoredAutomaticBatchDelayNanos = 0L;
        automaticReloadCompletionFailure = null;
        cleanupStagingDirectory();
        boolean handoffFilePresent = Files.isRegularFile(watcherHandoffFile);
        boolean handoffFailed = false;
        WatcherStateHandoff.Snapshot handoff = null;
        try {
            handoff = WatcherStateHandoff.readAndDelete(
                    watcherHandoffFile, folder.toPath(), WATCHER_HANDOFF_MAX_AGE_MILLIS);
        } catch (IOException | SecurityException exception) {
            handoffFailed = true;
            getLogger().log(Level.SEVERE,
                    "Could not restore the watcher state across BileTools reload", exception);
        }
        boolean handoffExpected = handoff != null || (handoffFilePresent && handoffFailed);

        pluginJarWatcher = new PluginJarDirectoryWatcher(folder.toPath(), WATCH_RECONCILIATION_NANOS);
        if (handoff != null) {
            Map<Path, JarSnapshotStager.FileStamp> restoredBaseline = new LinkedHashMap<>();
            for (WatcherStateHandoff.Entry entry : handoff.entries().values()) {
                if (entry.stamp() != null) {
                    restoredBaseline.put(entry.path(), entry.stamp());
                }
            }
            pluginJarWatcher.restoreBaseline(restoredBaseline);
        }
        try {
            pluginJarWatcher.start(System.nanoTime());
        } catch (IOException exception) {
            getLogger().log(Level.WARNING,
                    "Could not completely initialize plugin jar watching; periodic reconciliation will retry", exception);
        }

        Map<Path, JarSnapshotStager.FileStamp> currentFiles = pluginJarWatcher.snapshot();
        Map<Path, WatcherStateHandoff.Entry> previousFiles = handoff == null
                ? Map.of()
                : handoff.entries();
        if (handoff != null) {
            restoredAutomaticBatchDelayNanos = Math.max(0L, Math.min(
                    AUTOMATIC_RELOAD_INTERVAL_NANOS,
                    handoff.remainingAutomaticBatchNanos()));
        }
        if ((handoff != null && handoff.awaitAutomaticReloadCompletion())
                || Files.exists(automaticReloadCompletionFile)) {
            restoredAutomaticBatchDelayNanos = 0L;
            automaticReloadQueue.awaitReloadCompletion();
        }
        long nowNanos = System.nanoTime();
        for (Path path : currentFiles.keySet()) {
            File file = path.toFile();
            debug(() -> "Tracking plugin jar " + file.getName() + ".");
            WatcherStateHandoff.Entry previous = previousFiles.get(path);
            restoreIdentityReplacement(path, previous);
            String pluginName = previous != null && !previous.pluginName().isEmpty()
                    ? previous.pluginName()
                    : resolvePluginName(file);
            if (pluginName != null) {
                trackedPluginNames.put(path, pluginName);
            }
            if (previous == null || !previous.pending()) {
                archiveInitiallyTrackedPlugin(file);
            }
            if (!handoffExpected) {
                baselineAppliedFingerprint(path, nowNanos);
            } else if (previous != null && previous.pending()) {
                restoreAppliedFingerprint(path, previous);
                handleJarSignal(path, nowNanos);
            } else if (handoffFailed || previous == null) {
                if (!getName().equalsIgnoreCase(pluginName)) {
                    restoreAppliedFingerprint(path, previous);
                    handleJarSignal(path, nowNanos);
                } else {
                    baselineAppliedFingerprint(path, nowNanos);
                }
            } else if (!restoreHandoffEntry(path, currentFiles.get(path), previous)) {
                handleJarSignal(path, nowNanos);
            }
        }

        for (WatcherStateHandoff.Entry previous : previousFiles.values()) {
            Path path = previous.path();
            if (currentFiles.containsKey(path) || previous.pluginName().isEmpty()) {
                continue;
            }
            trackedPluginNames.put(path, previous.pluginName());
            restoreIdentityReplacement(path, previous);
            restoreAppliedFingerprint(path, previous);
            markJarMissing(path, nowNanos);
        }
        watchedJarCount = pluginJarWatcher.snapshot().size();
    }

    private void applyRestoredAutomaticBatchDelay() {
        long restoredDelayNanos = restoredAutomaticBatchDelayNanos;
        restoredAutomaticBatchDelayNanos = 0L;
        if (restoredDelayNanos <= 0L) {
            return;
        }
        automaticReloadQueue.deferBatchesUntil(saturatingAdd(System.nanoTime(), restoredDelayNanos));
    }

    private void baselineAppliedFingerprint(Path path, long nowNanos) {
        try {
            String fingerprint = JarSnapshotStager.fingerprint(path);
            appliedFingerprints.put(path, fingerprint);
            pluginJarWatcher.synchronizeFingerprint(path, fingerprint);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING,
                    "Could not establish the initial watcher fingerprint for " + path.getFileName(), exception);
            handleJarSignal(path, nowNanos);
        }
    }

    private boolean restoreHandoffEntry(Path path,
                                        JarSnapshotStager.FileStamp currentStamp,
                                        WatcherStateHandoff.Entry previous) {
        restoreAppliedFingerprint(path, previous);
        try {
            String currentFingerprint = JarSnapshotStager.fingerprint(path);
            if (!previous.appliedFingerprint().isEmpty()) {
                return previous.appliedFingerprint().equals(currentFingerprint);
            }
            if (previous.stamp() != null && previous.stamp().equals(currentStamp)) {
                appliedFingerprints.put(path, currentFingerprint);
                return true;
            }
            return false;
        } catch (IOException exception) {
            getLogger().log(Level.WARNING,
                    "Could not verify the restored watcher fingerprint for " + path.getFileName(), exception);
            return false;
        }
    }

    private void restoreAppliedFingerprint(Path path, WatcherStateHandoff.Entry previous) {
        if (previous != null && !previous.appliedFingerprint().isEmpty()) {
            appliedFingerprints.put(path, previous.appliedFingerprint());
            pluginJarWatcher.synchronizeFingerprint(path, previous.appliedFingerprint());
        }
    }

    private void restoreIdentityReplacement(Path path, WatcherStateHandoff.Entry previous) {
        if (previous == null || previous.replacements().isEmpty()) {
            return;
        }
        Map<String, Path> replacements = new LinkedHashMap<>();
        for (WatcherStateHandoff.Replacement replacement : previous.replacements()) {
            replacements.put(replacement.pluginName(), replacement.sourcePath());
        }
        pendingIdentityReplacements.put(path, replacements);
    }

    private void cleanupStagingDirectory() {
        try {
            Files.createDirectories(stagingDirectory);
            long staleBeforeMillis = System.currentTimeMillis() - STAGING_STALE_AGE_MILLIS;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(stagingDirectory)) {
                for (Path path : stream) {
                    if (Files.isRegularFile(path)
                            && Files.getLastModifiedTime(path).toMillis() <= staleBeforeMillis) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        } catch (IOException exception) {
            getLogger().log(Level.WARNING, "Could not clean the watcher staging directory", exception);
        }
    }

    private void archiveInitiallyTrackedPlugin(File file) {
        if (cfg == null || !cfg.isArchivePlugins()) {
            return;
        }
        Plugin trackedPlugin = BileUtils.getPlugin(file);
        if (trackedPlugin == null) {
            return;
        }

        String trackedPluginName = trackedPlugin.getName();
        String trackedPluginVersion = trackedPlugin.getDescription().getVersion();
        File trackedPluginFile = BileUtils.getPluginFile(trackedPlugin);
        if (trackedPluginFile == null || !trackedPluginFile.isFile()) {
            return;
        }
        try {
            PluginDescriptionFile sourceDescription = BileUtils.getPluginDescription(trackedPluginFile);
            if (!archiveSourceMatches(trackedPlugin.getDescription(), sourceDescription)) {
                getLogger().warning("Skipping initial archive for " + trackedPluginName
                        + " because its source jar identity does not match the loaded plugin");
                return;
            }
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING,
                    "Could not verify the initial archive source for " + trackedPluginName, throwable);
            return;
        }
        runAsync(() -> backupPluginFile(trackedPluginFile, trackedPluginName, trackedPluginVersion));
    }

    static boolean archiveSourceMatches(PluginDescriptionFile loadedDescription,
                                        PluginDescriptionFile sourceDescription) {
        return loadedDescription != null
                && sourceDescription != null
                && loadedDescription.getName().equalsIgnoreCase(sourceDescription.getName())
                && loadedDescription.getVersion().equals(sourceDescription.getVersion());
    }

    private void setupMetrics() {
        if (BSTATS_PLUGIN_ID <= 0 || !cfg.isMetrics()) {
            return;
        }

        try {
            Metrics active = new Metrics(this, BSTATS_PLUGIN_ID);
            // Charts run on the bStats daemon thread: every accessor below reads an atomic,
            // a concurrent set, a volatile, or a plain int snapshot. Null skips a cycle.
            active.addCustomChart(new SingleLineChart("watched_jars", this::watchedJarCount));
            active.addCustomChart(new SingleLineChart("reloads_total", () -> (int) Math.min(Integer.MAX_VALUE, reloadsTotal())));
            active.addCustomChart(new SingleLineChart("dirty_plugins", this::dirtyPluginCount));
            active.addCustomChart(new SimplePie("server_platform", () -> {
                ServerPlatform.Family family = ServerPlatform.family();
                return family == null ? null : family.name();
            }));
            active.addCustomChart(new SimplePie("remote_slave", () -> String.valueOf(remoteSlaveOnline())));
            metrics = active;
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Failed to initialize BileTools metrics", e);
        }
    }

    public int watchedJarCount() {
        return watchedJarCount;
    }

    public int dirtyPluginCount() {
        return dirtyPlugins.size();
    }

    public long reloadsTotal() {
        return reloadsTotal.get();
    }

    public long lastReloadMs() {
        return lastReloadMs;
    }

    public boolean remoteSlaveOnline() {
        SlaveBileServer server = srv;
        return server != null && server.isServerSocketOpen();
    }

    private void recordReloadSuccess(long totalMs) {
        reloadsTotal.incrementAndGet();
        lastReloadMs = totalMs;
    }

    public boolean isBackoff(Player p) {
        return new File(backoff, p.getUniqueId().toString()).exists();
    }

    public void toggleBackoff(Player p) {
        if (new File(backoff, p.getUniqueId().toString()).exists()) {
            new File(backoff, p.getUniqueId().toString()).delete();
        } else {
            new File(backoff, p.getUniqueId().toString()).mkdirs();
        }
    }

    @Override
    public void onDisable() {
        freezeForUnload();
        pluginOperationExecutor.shutdownNow();
        remoteDeployExecutor.shutdownNow();
        snapshotExecutor.shutdownNow();

        PlatformTasks.cancelPluginTasks(this);
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        getLogger().info("BileTools pre-unload hook fired (" + reason + "). Freezing watcher + slave before unload.");
        freezeForUnload();
    }

    private void freezeForUnload() {
        Metrics activeMetrics = metrics;
        metrics = null;
        if (activeMetrics != null) {
            try {
                activeMetrics.shutdown();
            } catch (Throwable e) {
                getLogger().log(Level.WARNING, "Error during bStats shutdown", e);
            }
        }

        if (integrationService != null) {
            integrationService.unregister();
            integrationService = null;
        }

        if (localization != null) {
            localization.close();
        }

        tickerActive = false;
        acceptingWatcherCompletions = false;
        queuedOperationKeys.clear();
        pendingObservations.clear();
        activeStageObservations.clear();
        latestGenerations.clear();
        trackedPluginNames.clear();
        pendingIdentityReplacements.clear();
        appliedFingerprints.clear();
        fileReadFailures.clear();
        unresolvedJarSignals.clear();
        deletionTombstones.clear();
        for (AutomaticReloadQueue.Candidate candidate : automaticReloadQueue.clear()) {
            candidate.discardSnapshot();
        }
        StageCompletion stageCompletion;
        while ((stageCompletion = completedStages.poll()) != null) {
            if (stageCompletion.stagedJar() != null) {
                stageCompletion.stagedJar().delete();
            }
        }
        AutomaticBatchCompletion batchCompletion;
        while ((batchCompletion = completedAutomaticBatches.poll()) != null) {
            for (AutomaticReloadQueue.Candidate candidate : batchCompletion.selfReloads()) {
                candidate.discardSnapshot();
            }
        }
        PluginJarDirectoryWatcher watcher = pluginJarWatcher;
        pluginJarWatcher = null;
        if (watcher != null) {
            watcher.close();
        }
        watchedJarCount = 0;
        activeStagingTasks = 0;
        watcherBusy = false;

        if (srv != null) {
            srv.shutdown();
            try {
                srv.join(5000L);
                getLogger().info("Bile Slave Server shut down.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            srv = null;
        }
    }

    public void reset(File f) {
        PluginJarDirectoryWatcher watcher = pluginJarWatcher;
        if (f != null && watcher != null) {
            watcher.synchronize(f.toPath());
            watchedJarCount = watcher.snapshot().size();
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;

        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }

        return current == null ? throwable : current;
    }

    private String rootMessage(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : message;
    }

    public void onTick() {
        localization.update();
        long nowNanos = System.nanoTime();
        pollPluginWatcher(nowNanos, false);
        retryUnresolvedJarSignals(nowNanos);
        advancePendingObservations(nowNanos);
        drainStageCompletions(nowNanos);
        expireDeletionTombstones(nowNanos);
        drainAutomaticBatchCompletions();
        observeAutomaticReloadCompletion(nowNanos);
        startAutomaticBatch(nowNanos);
        watcherBusy = !pendingObservations.isEmpty()
                || !unresolvedJarSignals.isEmpty()
                || activeStagingTasks > 0
                || !completedStages.isEmpty()
                || !deletionTombstones.isEmpty()
                || automaticReloadQueue.hasWork()
                || !completedAutomaticBatches.isEmpty();
    }

    private boolean pollPluginWatcher(long nowNanos, boolean forceReconciliation) {
        PluginJarDirectoryWatcher watcher = pluginJarWatcher;
        if (watcher == null) {
            return false;
        }

        PluginJarDirectoryWatcher.PollResult result = forceReconciliation
                ? watcher.reconcileNow(nowNanos)
                : watcher.poll(nowNanos);
        IOException failure = result.failure();
        if (failure == null) {
            lastWatcherFailure = null;
        } else {
            String failureMessage = rootMessage(failure);
            if (!failureMessage.equals(lastWatcherFailure)) {
                getLogger().log(Level.WARNING, "Plugin jar watcher reconciliation failed", failure);
                lastWatcherFailure = failureMessage;
            }
        }

        for (PluginJarDirectoryWatcher.Signal signal : result.signals()) {
            handleJarSignal(signal.path(), nowNanos);
        }
        watchedJarCount = watcher.snapshot().size();
        return result.reconciliationSucceeded();
    }

    private void handleJarSignal(Path signalPath, long nowNanos) {
        Path path = signalPath.toAbsolutePath().normalize();
        FileStampProbe probe = probeFileStamp(path);
        if (probe.failure() != null) {
            unresolvedJarSignals.add(path);
            return;
        }
        unresolvedJarSignals.remove(path);
        JarSnapshotStager.FileStamp stamp = probe.stamp();
        if (stamp == null) {
            markJarMissing(path, nowNanos);
            return;
        }

        deletionTombstones.cancel(path);
        boolean newlyTracked = !latestGenerations.containsKey(path) && !trackedPluginNames.containsKey(path);
        long generation = ++nextWatchGeneration;
        latestGenerations.put(path, generation);
        pendingObservations.put(path, new PendingObservation(stamp, generation, 0, STAGING_RETRY_LIMIT));
        BileUtils.invalidateJarMeta(path.toFile());
        if (newlyTracked) {
            debug(() -> "Tracking plugin jar " + path.getFileName() + ".");
        }
    }

    private void retryUnresolvedJarSignals(long nowNanos) {
        for (Path path : new ArrayList<>(unresolvedJarSignals)) {
            handleJarSignal(path, nowNanos);
        }
    }

    private FileStampProbe probeFileStamp(Path path) {
        try {
            JarSnapshotStager.FileStamp stamp = JarSnapshotStager.FileStamp.read(path);
            fileReadFailures.remove(path);
            return new FileStampProbe(stamp, null);
        } catch (NoSuchFileException | NotDirectoryException exception) {
            fileReadFailures.remove(path);
            return new FileStampProbe(null, null);
        } catch (IOException | SecurityException exception) {
            IOException failure = exception instanceof IOException ioException
                    ? ioException
                    : new IOException("Cannot inspect plugin jar " + path.getFileName(), exception);
            String message = rootMessage(failure);
            String previous = fileReadFailures.put(path, message);
            if (!message.equals(previous)) {
                getLogger().log(Level.WARNING, "Cannot inspect plugin jar; keeping its current lifecycle state: "
                        + path.getFileName(), failure);
            }
            return new FileStampProbe(null, failure);
        }
    }

    private void advancePendingObservations(long nowNanos) {
        int stablePassesRequired = Math.max(1, cfg == null ? 8 : cfg.getWatcherFingerprintDebounceTicks());
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
            JarSnapshotStager.FileStamp currentStamp = probe.stamp();
            if (currentStamp == null) {
                markJarMissing(path, nowNanos);
                continue;
            }
            if (!currentStamp.equals(observation.stamp())) {
                long generation = ++nextWatchGeneration;
                latestGenerations.put(path, generation);
                pendingObservations.put(path, new PendingObservation(
                        currentStamp, generation, 0, STAGING_RETRY_LIMIT));
                continue;
            }

            int stablePasses = observation.stablePasses() + 1;
            if (stablePasses < stablePassesRequired) {
                pendingObservations.put(path, new PendingObservation(
                        observation.stamp(), observation.generation(), stablePasses, observation.attemptsRemaining()));
                continue;
            }

            pendingObservations.remove(path);
            startSnapshotStage(path, observation);
        }
    }

    private void startSnapshotStage(Path path, PendingObservation observation) {
        activeStageObservations.put(path, observation);
        activeStagingTasks++;
        try {
            snapshotExecutor.execute(() -> {
                JarSnapshotStager.StagedJar stagedJar = null;
                Throwable failure = null;
                try {
                    stagedJar = JarSnapshotStager.stage(path, stagingDirectory, observation.generation());
                } catch (Throwable throwable) {
                    failure = throwable;
                }
                publishStageCompletion(new StageCompletion(path, observation, stagedJar, failure));
            });
        } catch (RejectedExecutionException exception) {
            activeStagingTasks = Math.max(0, activeStagingTasks - 1);
            activeStageObservations.remove(path, observation);
            if (tickerActive && observation.attemptsRemaining() > 1) {
                pendingObservations.put(path, new PendingObservation(
                        observation.stamp(), observation.generation(), 0, observation.attemptsRemaining() - 1));
            } else if (tickerActive) {
                unresolvedJarSignals.add(path);
            }
        }
    }

    private void publishStageCompletion(StageCompletion completion) {
        if (!acceptingWatcherCompletions) {
            if (completion.stagedJar() != null) {
                completion.stagedJar().delete();
            }
            return;
        }

        completedStages.add(completion);
        if (!acceptingWatcherCompletions && completedStages.remove(completion) && completion.stagedJar() != null) {
            completion.stagedJar().delete();
        }
    }

    private void drainStageCompletions(long nowNanos) {
        StageCompletion completion;
        while ((completion = completedStages.poll()) != null) {
            activeStagingTasks = Math.max(0, activeStagingTasks - 1);
            activeStageObservations.remove(completion.path(), completion.observation());
            completeSnapshotStage(completion, nowNanos);
        }
    }

    private void completeSnapshotStage(StageCompletion completion, long nowNanos) {
        Path path = completion.path();
        PendingObservation observation = completion.observation();
        Long latestGeneration = latestGenerations.get(path);
        if (latestGeneration == null || latestGeneration != observation.generation()) {
            if (completion.stagedJar() != null) {
                completion.stagedJar().delete();
            }
            return;
        }

        if (completion.failure() != null) {
            retrySnapshotStage(path, observation, completion.failure(), nowNanos);
            return;
        }

        JarSnapshotStager.StagedJar stagedJar = completion.stagedJar();
        if (stagedJar == null) {
            retrySnapshotStage(path, observation,
                    new IOException("Snapshot staging completed without a result"), nowNanos);
            return;
        }

        FileStampProbe probe = probeFileStamp(path);
        if (probe.failure() != null) {
            stagedJar.delete();
            retrySnapshotStage(path, observation, probe.failure(), nowNanos);
            return;
        }
        JarSnapshotStager.FileStamp currentStamp = probe.stamp();
        if (currentStamp == null) {
            stagedJar.delete();
            markJarMissing(path, nowNanos);
            return;
        }
        if (!currentStamp.equals(stagedJar.sourceStamp())) {
            stagedJar.delete();
            long generation = ++nextWatchGeneration;
            latestGenerations.put(path, generation);
            pendingObservations.put(path, new PendingObservation(
                    currentStamp, generation, 0, STAGING_RETRY_LIMIT));
            return;
        }

        String pluginName;
        try {
            pluginName = BileUtils.getPluginName(stagedJar.staged().toFile());
        } catch (Throwable throwable) {
            stagedJar.delete();
            retrySnapshotStage(path, observation, throwable, nowNanos);
            return;
        }
        if (pluginName == null || pluginName.trim().isEmpty()) {
            stagedJar.delete();
            retrySnapshotStage(path, observation,
                    new IOException("Plugin descriptor has no name"), nowNanos);
            return;
        }

        acceptStagedJar(path, pluginName, stagedJar);
    }

    private void retrySnapshotStage(Path path,
                                    PendingObservation observation,
                                    Throwable failure,
                                    long nowNanos) {
        Long latestGeneration = latestGenerations.get(path);
        if (latestGeneration == null || latestGeneration != observation.generation()) {
            return;
        }

        FileStampProbe probe = probeFileStamp(path);
        if (probe.failure() != null) {
            if (observation.attemptsRemaining() <= 1) {
                unresolvedJarSignals.add(path);
                getLogger().log(Level.WARNING,
                        "Plugin jar remained unreadable after staging retries: " + path.getFileName(), probe.failure());
                return;
            }
            pendingObservations.put(path, new PendingObservation(
                    observation.stamp(), observation.generation(), 0, observation.attemptsRemaining() - 1));
            return;
        }
        JarSnapshotStager.FileStamp currentStamp = probe.stamp();
        if (currentStamp == null) {
            markJarMissing(path, nowNanos);
            return;
        }
        if (observation.attemptsRemaining() <= 1) {
            unresolvedJarSignals.add(path);
            getLogger().log(Level.WARNING,
                    "Plugin jar remained unreadable after staging retries: " + path.getFileName(), failure);
            return;
        }

        pendingObservations.put(path, new PendingObservation(
                currentStamp, observation.generation(), 0, observation.attemptsRemaining() - 1));
    }

    private void acceptStagedJar(Path path,
                                 String pluginName,
                                 JarSnapshotStager.StagedJar stagedJar) {
        cancelDeletionTombstones(pluginName, path);
        String previousPluginName = trackedPluginNames.put(path, pluginName);
        if (previousPluginName != null && !previousPluginName.equalsIgnoreCase(pluginName)) {
            putIdentityReplacement(path, previousPluginName, path);
        }
        correlateProvidedIdentityReplacement(path, pluginName, stagedJar);

        String appliedFingerprint = appliedFingerprints.get(path);
        if (stagedJar.sha256().equals(appliedFingerprint)) {
            removeIdentityReplacement(path, pluginName);
            stagedJar.delete();
            submitAutomaticCandidate(new AutomaticReloadQueue.Candidate(
                    pluginName,
                    path,
                    stagedJar.generation(),
                    AutomaticReloadQueue.Action.NOOP,
                    null,
                    false));
            return;
        }

        if (isPluginDirty(pluginName)) {
            getLogger().warning("Skipping reload for dirty plugin " + pluginName + " after prior lifecycle failure");
            stagedJar.delete();
            return;
        }
        if (!isAutoLifecycleAllowed(pluginName)) {
            debug(() -> "Skipping automatic lifecycle for ignored/filtered plugin " + pluginName + ".");
            stagedJar.delete();
            return;
        }

        boolean remoteDeploy = cfg != null && cfg.isRemoteMasterEnabled()
                && cfg.hasRemoteDeploySignature(pluginName);
        debug(() -> "Queued automatic update for " + pluginName + " from " + path.getFileName() + ".");
        submitAutomaticCandidate(new AutomaticReloadQueue.Candidate(
                pluginName,
                path,
                stagedJar.generation(),
                AutomaticReloadQueue.Action.UPSERT,
                stagedJar,
                remoteDeploy));
    }

    private void correlateProvidedIdentityReplacement(Path path,
                                                       String pluginName,
                                                       JarSnapshotStager.StagedJar stagedJar) {
        List<String> providedNames;
        try {
            providedNames = BileUtils.getPluginDescription(stagedJar.staged().toFile()).getProvides();
        } catch (Throwable throwable) {
            getLogger().log(Level.WARNING,
                    "Could not inspect provided identities for " + pluginName, throwable);
            return;
        }

        Map<String, ReplacementMatch> matches = new LinkedHashMap<>();
        for (String providedName : providedNames) {
            for (ReplacementMatch match : findTrackedIdentities(providedName, path)) {
                matches.putIfAbsent(match.pluginName().toLowerCase(Locale.ROOT), match);
            }
        }
        if (matches.isEmpty()) {
            return;
        }

        for (ReplacementMatch match : matches.values()) {
            if (match.previousReplacementTarget() != null) {
                removeIdentityReplacement(match.previousReplacementTarget(), match.pluginName());
                latestGenerations.remove(match.previousReplacementTarget());
                pendingObservations.remove(match.previousReplacementTarget());
                unresolvedJarSignals.remove(match.previousReplacementTarget());
            }
            putIdentityReplacement(path, match.pluginName(), match.sourcePath());
        }
        if (matches.size() == 1) {
            deletionTombstones.cancel(matches.values().iterator().next().sourcePath());
        }
    }

    private List<ReplacementMatch> findTrackedIdentities(String pluginName, Path retainedPath) {
        List<ReplacementMatch> matches = new ArrayList<>();
        for (Map.Entry<Path, String> entry : trackedPluginNames.entrySet()) {
            Path path = entry.getKey();
            if (path.equals(retainedPath) || !entry.getValue().equalsIgnoreCase(pluginName)) {
                continue;
            }
            Map<String, Path> effectiveIdentities = effectiveTrackedIdentities(
                    pendingIdentityReplacements.get(path), entry.getValue(), path);
            for (Map.Entry<String, Path> identity : effectiveIdentities.entrySet()) {
                Path previousReplacementTarget = pendingIdentityReplacements.containsKey(path) ? path : null;
                matches.add(new ReplacementMatch(
                        identity.getKey(), identity.getValue(), previousReplacementTarget));
            }
        }
        for (Map.Entry<Path, Map<String, Path>> entry : pendingIdentityReplacements.entrySet()) {
            if (entry.getKey().equals(retainedPath)) {
                continue;
            }
            for (Map.Entry<String, Path> replacement : entry.getValue().entrySet()) {
                if (replacement.getKey().equalsIgnoreCase(pluginName)) {
                    matches.add(new ReplacementMatch(
                            replacement.getKey(), replacement.getValue(), entry.getKey()));
                }
            }
        }
        return List.copyOf(matches);
    }

    static Map<String, Path> effectiveTrackedIdentities(
            Map<String, Path> pendingReplacements,
            String trackedPluginName,
            Path trackedSource) {
        if (pendingReplacements != null && !pendingReplacements.isEmpty()) {
            return Map.copyOf(pendingReplacements);
        }
        return Map.of(trackedPluginName, trackedSource);
    }

    private void abandonTrackedSource(Path path) {
        pendingObservations.remove(path);
        unresolvedJarSignals.remove(path);
        latestGenerations.remove(path);
        trackedPluginNames.remove(path);
        pendingIdentityReplacements.remove(path);
        appliedFingerprints.remove(path);
        fileReadFailures.remove(path);
        deletionTombstones.cancel(path);
    }

    private Map<String, Path> identityReplacements(Path path) {
        Map<String, Path> replacements = pendingIdentityReplacements.get(path);
        return replacements == null ? Map.of() : replacements;
    }

    private void putIdentityReplacement(Path targetPath, String pluginName, Path sourcePath) {
        Map<String, Path> replacements = pendingIdentityReplacements.computeIfAbsent(
                targetPath, ignored -> new LinkedHashMap<>());
        String existingName = null;
        for (String candidateName : replacements.keySet()) {
            if (candidateName.equalsIgnoreCase(pluginName)) {
                existingName = candidateName;
                break;
            }
        }
        replacements.put(existingName == null ? pluginName : existingName, sourcePath);
    }

    private void removeIdentityReplacement(Path targetPath, String pluginName) {
        Map<String, Path> replacements = pendingIdentityReplacements.get(targetPath);
        if (replacements == null) {
            return;
        }
        String matchedName = null;
        for (String candidateName : replacements.keySet()) {
            if (candidateName.equalsIgnoreCase(pluginName)) {
                matchedName = candidateName;
                break;
            }
        }
        if (matchedName != null) {
            replacements.remove(matchedName);
        }
        if (replacements.isEmpty()) {
            pendingIdentityReplacements.remove(targetPath);
        }
    }

    private List<Path> replacementTargetsForSource(Path sourcePath) {
        return replacementTargetsForSource(pendingIdentityReplacements, sourcePath);
    }

    static List<Path> replacementTargetsForSource(
            Map<Path, Map<String, Path>> replacementsByTarget,
            Path sourcePath) {
        List<Path> targets = new ArrayList<>();
        for (Map.Entry<Path, Map<String, Path>> entry : replacementsByTarget.entrySet()) {
            if (entry.getKey().equals(sourcePath)) {
                continue;
            }
            for (Path replacementSource : entry.getValue().values()) {
                if (replacementSource.equals(sourcePath)) {
                    targets.add(entry.getKey());
                    break;
                }
            }
        }
        return List.copyOf(targets);
    }

    private void cancelDeletionTombstones(String pluginName, Path currentPath) {
        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.cancelPlugin(pluginName, currentPath)) {
            trackedPluginNames.remove(tombstone.path());
            latestGenerations.remove(tombstone.path());
            appliedFingerprints.remove(tombstone.path());
        }
        for (Map.Entry<Path, String> entry : new ArrayList<>(trackedPluginNames.entrySet())) {
            Path trackedPath = entry.getKey();
            if (trackedPath.equals(currentPath)
                    || !entry.getValue().equalsIgnoreCase(pluginName)) {
                continue;
            }
            FileStampProbe probe = probeFileStamp(trackedPath);
            if (probe.failure() != null || probe.stamp() != null) {
                continue;
            }
            trackedPluginNames.remove(trackedPath);
            latestGenerations.remove(trackedPath);
            appliedFingerprints.remove(trackedPath);
        }
    }

    private void markJarMissing(Path path, long nowNanos) {
        pendingObservations.remove(path);
        if (deletionTombstones.contains(path)) {
            return;
        }

        long generation = ++nextWatchGeneration;
        latestGenerations.put(path, generation);
        List<Path> replacementTargets = replacementTargetsForSource(path);
        if (!replacementTargets.isEmpty()) {
            for (Path replacementTarget : replacementTargets) {
                handleJarSignal(replacementTarget, nowNanos);
            }
            return;
        }

        Map<String, Path> replacements = identityReplacements(path);
        if (replacements.size() > 1) {
            return;
        }
        String pluginName = replacements.isEmpty()
                ? trackedPluginNames.get(path)
                : replacements.keySet().iterator().next();
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }

        deletionTombstones.schedule(path, pluginName, generation, nowNanos);
        debug(() -> "Plugin jar removed; waiting three seconds for recreation: " + path.getFileName());
    }

    private void expireDeletionTombstones(long nowNanos) {
        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.snapshot()) {
            Path path = tombstone.path();
            FileStampProbe probe = probeFileStamp(path);
            if (probe.failure() != null) {
                deletionTombstones.cancel(path);
                deletionTombstones.schedule(
                        path, tombstone.pluginName(), tombstone.generation(), nowNanos);
                continue;
            }
            if (probe.stamp() != null) {
                deletionTombstones.cancel(path);
                handleJarSignal(path, nowNanos);
            }
        }

        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.expire(nowNanos)) {
            Path path = tombstone.path();
            BileUtils.invalidateJarMeta(path.toFile());
            if (pluginUsesDifferentSource(tombstone.pluginName(), path)) {
                trackedPluginNames.remove(path);
                latestGenerations.remove(path, tombstone.generation());
                appliedFingerprints.remove(path);
                continue;
            }
            if (!isAutoLifecycleAllowed(tombstone.pluginName())) {
                trackedPluginNames.remove(path);
                latestGenerations.remove(path, tombstone.generation());
                appliedFingerprints.remove(path);
                debug(() -> "Skipping auto-unload for ignored/filtered plugin " + tombstone.pluginName() + ".");
                continue;
            }

            debug(() -> "Queued automatic unload after deletion grace for " + tombstone.pluginName() + ".");
            submitAutomaticCandidate(new AutomaticReloadQueue.Candidate(
                    tombstone.pluginName(),
                    path,
                    tombstone.generation(),
                    AutomaticReloadQueue.Action.UNLOAD,
                    null,
                    false));
        }
    }

    private boolean submitAutomaticCandidate(AutomaticReloadQueue.Candidate candidate) {
        AutomaticReloadQueue.Submission submission = automaticReloadQueue.submit(candidate);
        if (submission.discarded() != null) {
            submission.discarded().discardSnapshot();
        }
        return submission.accepted();
    }

    private boolean isAutoLifecycleAllowed(String pluginName) {
        if (cfg == null) {
            return true;
        }
        return cfg.isAutoReloadAllowed(pluginName);
    }

    private void drainAutomaticBatchCompletions() {
        AutomaticBatchCompletion completion;
        while ((completion = completedAutomaticBatches.poll()) != null) {
            if (automaticReloadQueue.isBatchInFlight()) {
                automaticReloadQueue.completeBatch(completion.completedNanos());
            }
            for (AutomaticReloadQueue.Candidate candidate : completion.selfReloads()) {
                if (isAutomaticCandidateCurrent(candidate)) {
                    if (selfReloadQueued.get() || hasAutomaticWorkBeyond(candidate)) {
                        submitAutomaticCandidate(candidate);
                    } else if (candidate.remoteDeploy()) {
                        deployBeforeSnapshotSelfReload(candidate);
                    } else {
                        queueSnapshotSelfReload(candidate);
                    }
                } else {
                    candidate.discardSnapshot();
                }
            }
        }
    }

    private void deployBeforeSnapshotSelfReload(AutomaticReloadQueue.Candidate candidate) {
        JarSnapshotStager.StagedJar stagedJar = candidate.stagedJar();
        if (stagedJar == null) {
            return;
        }
        remoteSelfReloadInProgress = candidate;
        try {
            pluginOperationExecutor.execute(() -> {
                try {
                    deployToRemoteTargets(
                            stagedJar.staged().toFile(),
                            candidate.source().getFileName().toString(),
                            candidate.pluginName());
                } catch (Throwable throwable) {
                    getLogger().log(Level.WARNING,
                            "Remote deploy failed before automatic self-reload", throwable);
                }
                if (!runGlobal(() -> {
                    remoteSelfReloadInProgress = null;
                    if (isAutomaticCandidateCurrent(candidate)) {
                        queueSnapshotSelfReload(candidate);
                    } else {
                        candidate.discardSnapshot();
                    }
                })) {
                    remoteSelfReloadInProgress = null;
                    submitAutomaticCandidate(candidate);
                    getLogger().warning("The server refused the automatic self-reload handoff after remote deploy; "
                            + "the exact staged update remains queued");
                }
            });
        } catch (RejectedExecutionException exception) {
            remoteSelfReloadInProgress = null;
            submitAutomaticCandidate(candidate);
            getLogger().log(Level.WARNING,
                    "Could not queue remote deploy before automatic self-reload; the exact staged update remains queued",
                    exception);
        }
    }

    private void startAutomaticBatch(long nowNanos) {
        if (selfReloadQueued.get()) {
            return;
        }
        Optional<AutomaticReloadQueue.Batch> batchResult = automaticReloadQueue.beginBatch(nowNanos);
        if (batchResult.isEmpty()) {
            return;
        }

        AutomaticReloadQueue.Batch batch = batchResult.get();
        List<AutomaticReloadQueue.Candidate> ordered = orderAutomaticCandidates(batch.candidates());
        getLogger().info("Automatic lifecycle batch (" + ordered.size() + "): "
                + String.join(", ", candidateNames(ordered)));
        String ownPluginName = getName();
        try {
            pluginOperationExecutor.execute(() -> runAutomaticBatch(ordered, ownPluginName));
        } catch (RejectedExecutionException exception) {
            automaticReloadQueue.completeBatch(System.nanoTime());
            for (AutomaticReloadQueue.Candidate candidate : ordered) {
                submitAutomaticCandidate(candidate);
            }
            if (tickerActive) {
                getLogger().log(Level.SEVERE, "Rejected automatic lifecycle batch", exception);
            }
        }
    }

    private List<AutomaticReloadQueue.Candidate> orderAutomaticCandidates(
            List<AutomaticReloadQueue.Candidate> candidates) {
        Map<String, AutomaticReloadQueue.Candidate> unloads = new LinkedHashMap<>();
        Map<String, AutomaticReloadQueue.Candidate> upserts = new LinkedHashMap<>();
        for (AutomaticReloadQueue.Candidate candidate : candidates) {
            Map<String, AutomaticReloadQueue.Candidate> target = candidate.action() == AutomaticReloadQueue.Action.UNLOAD
                    ? unloads
                    : upserts;
            target.put(candidate.pluginName().toLowerCase(Locale.ROOT), candidate);
        }

        List<String> unloadOrder = PluginDependencyOrder.order(
                candidateNames(unloads.values()),
                pluginName -> automaticDependencies(unloads.get(pluginName.toLowerCase(Locale.ROOT))),
                pluginName -> automaticProvidedNames(unloads.get(pluginName.toLowerCase(Locale.ROOT))));
        Collections.reverse(unloadOrder);
        List<String> upsertOrder = PluginDependencyOrder.order(
                candidateNames(upserts.values()),
                pluginName -> automaticDependencies(upserts.get(pluginName.toLowerCase(Locale.ROOT))),
                pluginName -> automaticProvidedNames(upserts.get(pluginName.toLowerCase(Locale.ROOT))));

        List<AutomaticReloadQueue.Candidate> ordered = new ArrayList<>(candidates.size());
        for (String pluginName : unloadOrder) {
            ordered.add(unloads.get(pluginName.toLowerCase(Locale.ROOT)));
        }
        for (String pluginName : upsertOrder) {
            ordered.add(upserts.get(pluginName.toLowerCase(Locale.ROOT)));
        }
        return ordered;
    }

    private List<String> candidateNames(Iterable<AutomaticReloadQueue.Candidate> candidates) {
        List<String> names = new ArrayList<>();
        for (AutomaticReloadQueue.Candidate candidate : candidates) {
            names.add(candidate.pluginName());
        }
        return names;
    }

    private List<String> automaticDependencies(AutomaticReloadQueue.Candidate candidate) {
        if (candidate == null) {
            return List.of();
        }

        if (candidate.stagedJar() != null) {
            try {
                List<String> dependencies = new ArrayList<>(
                        BileUtils.getDependencies(candidate.stagedJar().staged().toFile()));
                dependencies.addAll(BileUtils.getSoftDependencies(candidate.stagedJar().staged().toFile()));
                return dependencies;
            } catch (Throwable exception) {
                getLogger().log(Level.WARNING,
                        "Could not read staged dependencies for " + candidate.pluginName(), exception);
            }
        }

        Plugin plugin = BileUtils.getPluginByExactName(candidate.pluginName());
        if (plugin == null) {
            return List.of();
        }
        List<String> dependencies = new ArrayList<>(plugin.getDescription().getDepend());
        dependencies.addAll(plugin.getDescription().getSoftDepend());
        return dependencies;
    }

    private List<String> automaticProvidedNames(AutomaticReloadQueue.Candidate candidate) {
        if (candidate == null) {
            return List.of();
        }
        if (candidate.stagedJar() != null) {
            try {
                return BileUtils.getPluginDescription(candidate.stagedJar().staged().toFile()).getProvides();
            } catch (Throwable exception) {
                getLogger().log(Level.WARNING,
                        "Could not read staged provided names for " + candidate.pluginName(), exception);
            }
        }

        Plugin plugin = BileUtils.getPluginByExactName(candidate.pluginName());
        return plugin == null ? List.of() : plugin.getDescription().getProvides();
    }

    private void runAutomaticBatch(List<AutomaticReloadQueue.Candidate> candidates, String ownPluginName) {
        List<AutomaticReloadQueue.Candidate> selfReloads = new ArrayList<>();
        Map<String, AutomaticReloadQueue.Candidate> snapshotCandidates = automaticSnapshotCandidates(candidates);
        try {
            for (AutomaticReloadQueue.Candidate candidate : candidates) {
                if (candidate.action() == AutomaticReloadQueue.Action.UPSERT
                        && candidate.pluginName().equalsIgnoreCase(ownPluginName)) {
                    selfReloads.add(candidate);
                    continue;
                }

                if (candidate.action() == AutomaticReloadQueue.Action.UNLOAD) {
                    runAutomaticUnload(candidate, ownPluginName);
                } else if (candidate.action() == AutomaticReloadQueue.Action.UPSERT) {
                    runAutomaticUpsert(candidate, snapshotCandidates);
                }
            }
        } finally {
            for (AutomaticReloadQueue.Candidate candidate : candidates) {
                if (!selfReloads.contains(candidate)) {
                    candidate.discardSnapshot();
                }
            }
            publishAutomaticBatchCompletion(new AutomaticBatchCompletion(selfReloads, System.nanoTime()));
        }
    }

    private Map<String, AutomaticReloadQueue.Candidate> automaticSnapshotCandidates(
            List<AutomaticReloadQueue.Candidate> candidates) {
        Map<String, AutomaticReloadQueue.Candidate> snapshots = new LinkedHashMap<>();
        for (AutomaticReloadQueue.Candidate candidate : candidates) {
            if (candidate.action() == AutomaticReloadQueue.Action.UPSERT && candidate.stagedJar() != null) {
                snapshots.put(candidate.pluginName().toLowerCase(Locale.ROOT), candidate);
            }
        }
        return snapshots;
    }

    private Map<String, BileUtils.SnapshotLoadSource> currentAutomaticSnapshotSources(
            Map<String, AutomaticReloadQueue.Candidate> candidates) {
        Map<String, BileUtils.SnapshotLoadSource> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, AutomaticReloadQueue.Candidate> entry : candidates.entrySet()) {
            AutomaticReloadQueue.Candidate candidate = entry.getValue();
            if (!isAutomaticCandidateCurrent(candidate)) {
                continue;
            }
            JarSnapshotStager.StagedJar stagedJar = candidate.stagedJar();
            if (stagedJar == null || !stagedJar.staged().toFile().isFile()) {
                FileStampProbe missingSnapshotProbe = probeFileStamp(candidate.source());
                if (missingSnapshotProbe.failure() == null && missingSnapshotProbe.stamp() != null) {
                    pendingObservations.put(candidate.source(), new PendingObservation(
                            missingSnapshotProbe.stamp(),
                            candidate.generation(),
                            0,
                            STAGING_RETRY_LIMIT));
                }
                continue;
            }
            FileStampProbe probe = probeFileStamp(candidate.source());
            if (probe.failure() != null) {
                pendingObservations.put(candidate.source(), new PendingObservation(
                        candidate.stagedJar().sourceStamp(), candidate.generation(), 0, STAGING_RETRY_LIMIT));
                continue;
            }
            if (probe.stamp() == null) {
                markJarMissing(candidate.source(), System.nanoTime());
                continue;
            }
            if (!probe.stamp().equals(candidate.stagedJar().sourceStamp())) {
                handleJarSignal(candidate.source(), System.nanoTime());
                continue;
            }
            snapshots.put(entry.getKey(), new BileUtils.SnapshotLoadSource(
                    candidate.pluginName(),
                    stagedJar.staged().toFile(),
                    candidate.source().toFile()));
        }
        return snapshots;
    }

    private Set<String> protectedAutomaticSnapshotPlugins(
            Map<String, AutomaticReloadQueue.Candidate> snapshotCandidates) {
        Set<String> protectedPlugins = new HashSet<>(snapshotCandidates.keySet());
        for (Map<String, Path> replacements : pendingIdentityReplacements.values()) {
            for (String replacementPluginName : replacements.keySet()) {
                protectedPlugins.add(replacementPluginName.toLowerCase(Locale.ROOT));
            }
        }
        for (Path path : unresolvedJarSignals) {
            addTrackedPluginName(protectedPlugins, path);
        }
        for (Path path : pendingObservations.keySet()) {
            addTrackedPluginName(protectedPlugins, path);
        }
        for (Path path : activeStageObservations.keySet()) {
            addTrackedPluginName(protectedPlugins, path);
        }
        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.snapshot()) {
            protectedPlugins.add(tombstone.pluginName().toLowerCase(Locale.ROOT));
        }
        for (String pluginName : automaticReloadQueue.pendingPluginNames()) {
            protectedPlugins.add(pluginName.toLowerCase(Locale.ROOT));
        }
        return protectedPlugins;
    }

    private void addTrackedPluginName(Set<String> pluginNames, Path path) {
        String pluginName = trackedPluginNames.get(path);
        if (pluginName != null && !pluginName.trim().isEmpty()) {
            pluginNames.add(pluginName.toLowerCase(Locale.ROOT));
        }
    }

    private void runAutomaticUnload(AutomaticReloadQueue.Candidate candidate, String ownPluginName) {
        String pluginName = candidate.pluginName();
        if (pluginName.equalsIgnoreCase(ownPluginName)) {
            debug(() -> "Detected BileTools removal; skipping automatic self-unload for " + pluginName + ".");
            return;
        }

        AtomicBoolean unloaded = new AtomicBoolean(false);
        AtomicBoolean superseded = new AtomicBoolean(false);
        long startNanos = System.nanoTime();
        try {
            executePluginLifecycle(pluginName, "unload " + pluginName, () -> {
                if (!isAutomaticCandidateCurrent(candidate)) {
                    superseded.set(true);
                    return;
                }
                long nowNanos = System.nanoTime();
                FileStampProbe probe = probeFileStamp(candidate.source());
                if (probe.failure() != null) {
                    deletionTombstones.schedule(
                            candidate.source(), candidate.pluginName(), candidate.generation(), nowNanos);
                    superseded.set(true);
                    return;
                }
                if (probe.stamp() != null) {
                    handleJarSignal(candidate.source(), nowNanos);
                    superseded.set(true);
                    return;
                }
                Plugin targetPlugin = BileUtils.getPluginByExactName(pluginName);
                if (targetPlugin != null && pluginUsesDifferentSource(targetPlugin, candidate.source())) {
                    clearTrackedSource(candidate);
                    superseded.set(true);
                    return;
                }
                if (targetPlugin != null) {
                    BileUtils.unload(targetPlugin);
                    unloaded.set(true);
                }
                clearTrackedSource(candidate);
            });
            if (superseded.get()) {
                return;
            }
            if (!unloaded.get()) {
                return;
            }
            clearPluginDirty(pluginName);
            long totalMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            notifyBileUsers(localization.text(
                    BileMessages.UNLOAD_SUCCESS,
                    MessageArgs.builder()
                            .untrusted("plugin", pluginName)
                            .untrusted("milliseconds", totalMs)
                            .build()
            ), false);
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "Failed to unload " + pluginName + " after file removal", throwable);
            notifyBileUsers(localization.text(
                    BileMessages.UNLOAD_FAILED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private boolean pluginUsesDifferentSource(String pluginName, Path expectedSource) {
        return pluginUsesDifferentSource(BileUtils.getPluginByExactName(pluginName), expectedSource);
    }

    private boolean pluginUsesDifferentSource(Plugin plugin, Path expectedSource) {
        if (plugin == null) {
            return false;
        }
        File loadedSource = BileUtils.getPluginFile(plugin);
        if (loadedSource == null) {
            return false;
        }
        try {
            return !loadedSource.getCanonicalFile().equals(expectedSource.toFile().getCanonicalFile());
        } catch (IOException exception) {
            return !loadedSource.getAbsolutePath().equalsIgnoreCase(expectedSource.toFile().getAbsolutePath());
        }
    }

    private void runAutomaticUpsert(
            AutomaticReloadQueue.Candidate candidate,
            Map<String, AutomaticReloadQueue.Candidate> snapshotCandidates) {
        String pluginName = candidate.pluginName();
        JarSnapshotStager.StagedJar stagedJar = candidate.stagedJar();
        if (stagedJar == null || isPluginDirty(pluginName)) {
            return;
        }

        long startNanos = System.nanoTime();
        AtomicBoolean superseded = new AtomicBoolean(false);
        AtomicBoolean unchanged = new AtomicBoolean(false);
        try {
            executePluginLifecycle(pluginName, "automatic update " + pluginName, () -> {
                if (!isAutomaticCandidateCurrent(candidate)) {
                    superseded.set(true);
                    return;
                }
                FileStampProbe probe = probeFileStamp(candidate.source());
                if (probe.failure() != null) {
                    pendingObservations.put(candidate.source(), new PendingObservation(
                            stagedJar.sourceStamp(), candidate.generation(), 0, STAGING_RETRY_LIMIT));
                    superseded.set(true);
                    return;
                }
                JarSnapshotStager.FileStamp currentStamp = probe.stamp();
                if (currentStamp == null) {
                    markJarMissing(candidate.source(), System.nanoTime());
                    superseded.set(true);
                    return;
                }
                if (!currentStamp.equals(stagedJar.sourceStamp())) {
                    handleJarSignal(candidate.source(), System.nanoTime());
                    superseded.set(true);
                    return;
                }
                if (stagedJar.sha256().equals(appliedFingerprints.get(candidate.source()))) {
                    unchanged.set(true);
                    return;
                }
                Map<String, Path> replacements = identityReplacements(candidate.source());
                Plugin targetPlugin = BileUtils.getPluginByExactName(pluginName);
                if (replacements.size() > 1) {
                    throw new BileUtils.RestartRequiredException(
                            "Cannot hot-replace multiple loaded plugin identities with " + pluginName
                                    + "; a full server restart is required");
                }
                Map.Entry<String, Path> replacement = replacements.isEmpty()
                        ? null
                        : replacements.entrySet().iterator().next();
                String replacedPluginName = replacement == null ? null : replacement.getKey();
                if (replacement != null && !replacedPluginName.equalsIgnoreCase(pluginName)) {
                    if (isPluginDirty(replacedPluginName)) {
                        getLogger().warning("Skipping automatic identity replacement for dirty plugin "
                                + replacedPluginName);
                        superseded.set(true);
                        return;
                    }
                    if (!isAutoLifecycleAllowed(replacedPluginName)) {
                        throw new BileUtils.RestartRequiredException(
                                "Cannot hot-replace ignored/filtered plugin " + replacedPluginName
                                        + " with " + pluginName + "; a full server restart is required");
                    }
                    if (replacedPluginName.equalsIgnoreCase(getName())) {
                        throw new BileUtils.RestartRequiredException(
                                "Cannot change the BileTools plugin identity during a self-managed reload; a full server restart is required");
                    }
                    if (targetPlugin != null) {
                        throw new BileUtils.RestartRequiredException(
                                "Cannot hot-replace " + replacedPluginName + " with " + pluginName
                                        + " because " + pluginName + " is already loaded; a full server restart is required");
                    }
                    Path replacedSource = replacement.getValue();
                    if (!replacedSource.equals(candidate.source())) {
                        FileStampProbe replacedSourceProbe = probeFileStamp(replacedSource);
                        if (replacedSourceProbe.failure() != null) {
                            unresolvedJarSignals.add(replacedSource);
                            superseded.set(true);
                            return;
                        }
                        if (replacedSourceProbe.stamp() != null) {
                            getLogger().info("Waiting to hot-replace " + replacedPluginName
                                    + " until its prior source is removed: " + replacedSource.getFileName());
                            superseded.set(true);
                            return;
                        }
                    }
                    if (hasCompetingIdentityReplacement(candidate.source(), replacedPluginName)) {
                        throw new BileUtils.RestartRequiredException(
                                "Cannot hot-replace " + replacedPluginName
                                        + " from multiple plugin jars; a full server restart is required");
                    }
                    Plugin replacedPlugin = BileUtils.getPluginByExactName(replacedPluginName);
                    if (replacedPlugin == null) {
                        BileUtils.loadFromSnapshot(stagedJar.staged().toFile(), candidate.source().toFile());
                        appliedFingerprints.put(candidate.source(), stagedJar.sha256());
                    } else {
                        Set<String> appliedSnapshots = BileUtils.replaceProvidedIdentityFromSnapshot(
                                replacedPlugin,
                                stagedJar.staged().toFile(),
                                candidate.source().toFile(),
                                currentAutomaticSnapshotSources(snapshotCandidates),
                                protectedAutomaticSnapshotPlugins(snapshotCandidates));
                        recordAppliedSnapshots(appliedSnapshots, snapshotCandidates);
                    }
                    clearPluginDirty(replacedPluginName);
                    completeIdentityReplacement(candidate.source(), replacements);
                } else if (targetPlugin == null) {
                    BileUtils.loadFromSnapshot(stagedJar.staged().toFile(), candidate.source().toFile());
                    appliedFingerprints.put(candidate.source(), stagedJar.sha256());
                } else {
                    try {
                        Set<String> appliedSnapshots = BileUtils.reloadFromSnapshot(
                                targetPlugin,
                                stagedJar.staged().toFile(),
                                candidate.source().toFile(),
                                currentAutomaticSnapshotSources(snapshotCandidates),
                                protectedAutomaticSnapshotPlugins(snapshotCandidates));
                        recordAppliedSnapshots(appliedSnapshots, snapshotCandidates);
                        removeIdentityReplacement(candidate.source(), pluginName);
                    } catch (BileUtils.SnapshotUnavailableException exception) {
                        pendingObservations.put(candidate.source(), new PendingObservation(
                                stagedJar.sourceStamp(), candidate.generation(), 0, STAGING_RETRY_LIMIT));
                        superseded.set(true);
                    }
                }
            });
            if (superseded.get()) {
                return;
            }
            if (unchanged.get()) {
                if (candidate.remoteDeploy()) {
                    deployToRemoteTargets(
                            stagedJar.staged().toFile(), candidate.source().getFileName().toString(), pluginName);
                }
                return;
            }
            clearPluginDirty(pluginName);
            if (candidate.remoteDeploy()) {
                deployToRemoteTargets(
                        stagedJar.staged().toFile(), candidate.source().getFileName().toString(), pluginName);
            }
            long totalMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            recordReloadSuccess(totalMs);
            notifyBileUsers(localization.text(
                    BileMessages.RELOAD_SUCCESS,
                    MessageArgs.builder()
                            .untrusted("plugin", pluginName)
                            .untrusted("milliseconds", totalMs)
                            .build()
            ), true);
        } catch (BileUtils.RestartRequiredException exception) {
            getLogger().warning(exception.getMessage());
            notifyBileUsers(localization.text(
                    BileMessages.RESTART_REQUIRED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        } catch (Throwable throwable) {
            getLogger().log(Level.SEVERE, "Failed automatic update for " + pluginName, throwable);
            markPluginDirty(pluginName, "health or lifecycle failure: " + rootMessage(throwable));
            notifyBileUsers(localization.text(
                    BileMessages.RELOAD_FAILED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private void recordAppliedSnapshots(
            Set<String> appliedSnapshotNames,
            Map<String, AutomaticReloadQueue.Candidate> snapshotCandidates) {
        for (String appliedSnapshotName : appliedSnapshotNames) {
            AutomaticReloadQueue.Candidate applied = snapshotCandidates.get(
                    appliedSnapshotName.toLowerCase(Locale.ROOT));
            if (applied != null && applied.stagedJar() != null) {
                appliedFingerprints.put(applied.source(), applied.stagedJar().sha256());
            }
        }
    }

    private void completeIdentityReplacement(Path targetPath, Map<String, Path> replacements) {
        Set<Path> replacedSources = new LinkedHashSet<>(replacements.values());
        pendingIdentityReplacements.remove(targetPath);
        for (Path replacedSource : replacedSources) {
            if (!replacedSource.equals(targetPath)) {
                abandonTrackedSource(replacedSource);
            }
        }
    }

    private void publishAutomaticBatchCompletion(AutomaticBatchCompletion completion) {
        if (!acceptingWatcherCompletions) {
            discardSelfReloads(completion);
            return;
        }

        completedAutomaticBatches.add(completion);
        if (!acceptingWatcherCompletions && completedAutomaticBatches.remove(completion)) {
            discardSelfReloads(completion);
        }
    }

    private void discardSelfReloads(AutomaticBatchCompletion completion) {
        for (AutomaticReloadQueue.Candidate candidate : completion.selfReloads()) {
            candidate.discardSnapshot();
        }
    }

    private boolean isAutomaticCandidateCurrent(AutomaticReloadQueue.Candidate candidate) {
        Long latestGeneration = latestGenerations.get(candidate.source());
        return latestGeneration != null
                && latestGeneration == candidate.generation()
                && (candidate.action() != AutomaticReloadQueue.Action.UNLOAD
                || !hasPendingIdentityReplacement(candidate.pluginName(), candidate.source()))
                && !automaticReloadQueue.hasPendingReplacement(candidate);
    }

    private boolean hasPendingIdentityReplacement(String pluginName, Path excludedSource) {
        for (Map.Entry<Path, Map<String, Path>> entry : pendingIdentityReplacements.entrySet()) {
            if (entry.getKey().equals(excludedSource)) {
                continue;
            }
            for (String replacementPluginName : entry.getValue().keySet()) {
                if (replacementPluginName.equalsIgnoreCase(pluginName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasCompetingIdentityReplacement(Path source, String replacedPluginName) {
        for (Map.Entry<Path, Map<String, Path>> entry : pendingIdentityReplacements.entrySet()) {
            if (entry.getKey().equals(source)) {
                continue;
            }
            for (String replacementPluginName : entry.getValue().keySet()) {
                if (replacementPluginName.equalsIgnoreCase(replacedPluginName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void clearTrackedSource(AutomaticReloadQueue.Candidate candidate) {
        String trackedPluginName = trackedPluginNames.get(candidate.source());
        boolean matchesTracked = trackedPluginName != null
                && trackedPluginName.equalsIgnoreCase(candidate.pluginName());
        boolean matchesReplacement = false;
        for (String replacedPluginName : identityReplacements(candidate.source()).keySet()) {
            if (replacedPluginName.equalsIgnoreCase(candidate.pluginName())) {
                matchesReplacement = true;
                break;
            }
        }
        if (trackedPluginName != null && !matchesTracked && !matchesReplacement) {
            return;
        }
        trackedPluginNames.remove(candidate.source());
        pendingIdentityReplacements.remove(candidate.source());
        latestGenerations.remove(candidate.source(), candidate.generation());
        appliedFingerprints.remove(candidate.source());
    }

    private void deployToRemoteTargets(File sourceFile, String transferFileName, String pluginName) {
        List<String> targets = cfg.getRemoteMasterDeployTargets();
        if (targets.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String target : targets) {
            String[] split = target.split(":", 3);
            if (split.length < 3) {
                getLogger().warning("Invalid remote deploy target format: " + target);
                continue;
            }

            String host = split[0];
            String password = split[2];
            int port;
            try {
                port = Integer.parseInt(split[1]);
            } catch (NumberFormatException e) {
                getLogger().warning("Invalid port in remote deploy target: " + target);
                continue;
            }

            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    streamFile(sourceFile, transferFileName, host, port, password);
                } catch (UnknownHostException e) {
                    getLogger().warning("Invalid host in remote deploy target: " + target);
                } catch (IOException e) {
                    getLogger().warning("Failed remote deploy to " + target + ": " + e.getMessage());
                }
            }, remoteDeployExecutor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(REMOTE_DEPLOY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            getLogger().warning("Remote deploy timed out after " + REMOTE_DEPLOY_TIMEOUT_SECONDS + "s for " + pluginName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getLogger().warning("Remote deploy interrupted for " + pluginName);
        } catch (ExecutionException e) {
            getLogger().warning("Remote deploy failed for " + pluginName + ": " + e.getMessage());
        }

        notifyBileUsers(localization.text(
                BileMessages.REMOTE_DEPLOYED,
                MessageArgs.builder()
                        .untrusted("plugin", pluginName)
                        .untrusted("count", targets.size())
                        .build()
        ), false);
    }

    private void notifyBileUsers(ComponentText message, boolean playSound) {
        runGlobal(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission(ROOT_PERMISSION)) {
                    continue;
                }

                // Folia/Canvas: entity-thread for location/sound; message is still safe via entity task.
                PlatformTasks.runForPlayer(this, player, () -> {
                    ComponentMessenger.send(player, message);
                    if (playSound) {
                        try {
                            player.playSound(player.getLocation(), sx, 1f, 1.9f);
                        } catch (Throwable ignored) {
                            // Folia can reject off-region entity access; message already sent.
                        }
                    }
                });
            }
        });
    }

    private void sendCommandMessage(CommandSender sender, ComponentText message) {
        if (sender == null || message == null) {
            return;
        }

        if (sender instanceof Player player) {
            if (!PlatformTasks.runForPlayer(this, player, () -> ComponentMessenger.send(player, message))) {
                ComponentMessenger.send(player, message);
            }
            return;
        }

        if (!runGlobal(() -> ComponentMessenger.send(sender, message))) {
            ComponentMessenger.send(sender, message);
        }
    }

    private void enqueuePluginOperation(String key, Runnable operation) {
        if (operation == null) {
            return;
        }

        String normalizedKey = key == null ? null : key.toLowerCase(Locale.ROOT);
        if (normalizedKey != null && !queuedOperationKeys.add(normalizedKey)) {
            return;
        }

        try {
            pluginOperationExecutor.execute(() -> {
                try {
                    if (isEnabled()) {
                        operation.run();
                    }
                } catch (Throwable e) {
                    getLogger().log(Level.SEVERE, "Queued plugin operation failed", e);
                } finally {
                    if (normalizedKey != null) {
                        queuedOperationKeys.remove(normalizedKey);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            if (normalizedKey != null) {
                queuedOperationKeys.remove(normalizedKey);
            }

            if (isEnabled()) {
                getLogger().log(Level.SEVERE, "Rejected plugin operation task", e);
            }
        }
    }

    private boolean isPluginDirty(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return false;
        }
        return dirtyPlugins.contains(pluginName.toLowerCase(Locale.ROOT));
    }

    private void markPluginDirty(String pluginName, String reason) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }
        String key = pluginName.toLowerCase(Locale.ROOT);
        if (dirtyPlugins.add(key)) {
            getLogger().severe("Marked plugin dirty: " + pluginName + " (" + reason + "). Further auto-ops blocked until a successful manual lifecycle or clear.");
            notifyBileUsers(localization.text(
                    BileMessages.DIRTY_PLUGIN_PAUSED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private void clearPluginDirty(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }
        dirtyPlugins.remove(pluginName.toLowerCase(Locale.ROOT));
    }

    private void queueCommandSelfReload(CommandSender sender, String pluginName) {
        ComponentText message = localization.text(
                BileMessages.RELOADING,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        );
        Runnable acknowledgeAndQueue = () -> {
            ComponentMessenger.send(sender, message);
            queueSelfReload("command");
        };

        if (sender instanceof Player player) {
            if (!PlatformTasks.runForPlayer(this, player, acknowledgeAndQueue)) {
                acknowledgeAndQueue.run();
            }
            return;
        }

        if (!runGlobal(acknowledgeAndQueue)) {
            acknowledgeAndQueue.run();
        }
    }

    private void queueSelfReload(String source) {
        if (!selfReloadQueued.compareAndSet(false, true)) {
            return;
        }

        String context = (source == null || source.trim().isEmpty()) ? "reload request" : source.trim();
        String pluginName = getName();

        notifyBileUsers(localization.text(
                BileMessages.RELOADING_CONTEXT,
                MessageArgs.builder()
                        .untrusted("plugin", pluginName)
                        .untrusted("context", context)
                        .build()
        ), false);

        if (!runGlobalLater(() -> performSelfReload(pluginName, context), 1L)) {
            selfReloadQueued.set(false);
            getLogger().warning("Failed to schedule self-reload for " + pluginName + " (" + context + ")");
            notifyBileUsers(localization.text(
                    BileMessages.RELOAD_FAILED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private void queueSnapshotSelfReload(AutomaticReloadQueue.Candidate candidate) {
        JarSnapshotStager.StagedJar stagedJar = candidate.stagedJar();
        if (stagedJar == null) {
            return;
        }
        if (hasAutomaticWorkBeyond(candidate)) {
            submitAutomaticCandidate(candidate);
            return;
        }
        if (!selfReloadQueued.compareAndSet(false, true)) {
            submitAutomaticCandidate(candidate);
            return;
        }

        String pluginName = candidate.pluginName();
        notifyBileUsers(localization.text(
                BileMessages.RELOADING_CONTEXT,
                MessageArgs.builder()
                        .untrusted("plugin", pluginName)
                        .untrusted("context", "automatic file update")
                        .build()
        ), false);

        if (!runGlobalLater(() -> performSnapshotSelfReload(candidate), 1L)) {
            selfReloadQueued.set(false);
            submitAutomaticCandidate(candidate);
            getLogger().warning("Failed to schedule automatic self-reload for " + pluginName
                    + "; the exact staged update remains queued");
            notifyBileUsers(localization.text(
                    BileMessages.RELOAD_FAILED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private boolean hasAutomaticWorkBeyond(AutomaticReloadQueue.Candidate candidate) {
        if (automaticReloadQueue.isBatchInFlight()) {
            return true;
        }
        Path selfSource = candidate.source();
        for (Path path : pendingObservations.keySet()) {
            if (!path.equals(selfSource)) {
                return true;
            }
        }
        for (Path path : activeStageObservations.keySet()) {
            if (!path.equals(selfSource)) {
                return true;
            }
        }
        for (StageCompletion completion : completedStages) {
            if (!completion.path().equals(selfSource)) {
                return true;
            }
        }
        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.snapshot()) {
            if (!tombstone.path().equals(selfSource)) {
                return true;
            }
        }
        for (String pluginName : automaticReloadQueue.pendingPluginNames()) {
            if (!pluginName.equalsIgnoreCase(candidate.pluginName())) {
                return true;
            }
        }
        return false;
    }

    private void performSnapshotSelfReload(AutomaticReloadQueue.Candidate candidate) {
        JarSnapshotStager.StagedJar stagedJar = candidate.stagedJar();
        long nowNanos = System.nanoTime();
        boolean reconciliationSucceeded;
        try {
            reconciliationSucceeded = pollPluginWatcher(nowNanos, true);
        } catch (Throwable throwable) {
            selfReloadQueued.set(false);
            submitAutomaticCandidate(candidate);
            getLogger().log(Level.SEVERE,
                    "Could not reconcile plugin jars before automatic self-reload", throwable);
            return;
        }
        if (!reconciliationSucceeded) {
            selfReloadQueued.set(false);
            submitAutomaticCandidate(candidate);
            return;
        }
        if (!isAutomaticCandidateCurrent(candidate)) {
            selfReloadQueued.set(false);
            stagedJar.delete();
            return;
        }
        if (hasAutomaticWorkBeyond(candidate)) {
            selfReloadQueued.set(false);
            submitAutomaticCandidate(candidate);
            return;
        }

        FileStampProbe probe = probeFileStamp(candidate.source());
        if (probe.failure() != null) {
            selfReloadQueued.set(false);
            stagedJar.delete();
            pendingObservations.put(candidate.source(), new PendingObservation(
                    stagedJar.sourceStamp(), candidate.generation(), 0, STAGING_RETRY_LIMIT));
            return;
        }

        JarSnapshotStager.FileStamp currentStamp = probe.stamp();
        if (currentStamp == null || !currentStamp.equals(stagedJar.sourceStamp())) {
            selfReloadQueued.set(false);
            stagedJar.delete();
            if (currentStamp == null) {
                markJarMissing(candidate.source(), System.nanoTime());
            } else {
                handleJarSignal(candidate.source(), System.nanoTime());
            }
            return;
        }

        String previousFingerprint = appliedFingerprints.put(candidate.source(), stagedJar.sha256());
        AutomaticReloadCompletionHandoff completionHandoff = null;
        try {
            completionHandoff = AutomaticReloadCompletionHandoff.begin(automaticReloadCompletionFile);
            persistWatcherHandoff(automaticReloadQueue.completionCooldownNanos(), true);
        } catch (IOException | SecurityException exception) {
            closeAutomaticReloadCompletionHandoff(completionHandoff);
            restoreAppliedFingerprint(candidate.source(), previousFingerprint);
            selfReloadQueued.set(false);
            submitAutomaticCandidate(candidate);
            getLogger().log(Level.SEVERE,
                    "Could not preserve watcher state before automatic self-reload", exception);
            return;
        }

        boolean requeued = false;
        Throwable reloadFailure = null;
        try {
            try {
                BileUtils.reloadFromSnapshot(
                        this, stagedJar.staged().toFile(), candidate.source().toFile());
            } catch (Throwable throwable) {
                reloadFailure = throwable;
            } finally {
                closeAutomaticReloadCompletionHandoff(completionHandoff);
            }
            if (reloadFailure != null) {
                selfReloadQueued.set(false);
                if (BileTools.bile == this && tickerActive) {
                    restoreAppliedFingerprint(candidate.source(), previousFingerprint);
                    requeued = submitAutomaticCandidate(candidate);
                    deleteWatcherHandoffAfterFailedReload();
                }
                getLogger().log(Level.SEVERE,
                        "Failed automatic self-reload for " + candidate.pluginName(), reloadFailure);
                notifyBileUsers(localization.text(
                        BileMessages.RELOAD_FAILED,
                        MessageArgs.builder().untrusted("plugin", candidate.pluginName()).build()
                ), false);
            }
        } finally {
            if (!requeued) {
                stagedJar.delete();
            }
        }
    }

    private void performSelfReload(String pluginName, String context) {
        drainAutomaticBatchCompletions();
        observeAutomaticReloadCompletion(System.nanoTime());
        if (automaticReloadQueue.isBatchInFlight()) {
            if (!runGlobalLater(() -> performSelfReload(pluginName, context), 1L)) {
                selfReloadQueued.set(false);
                getLogger().warning("Failed to defer self-reload until the active automatic batch completed");
            }
            return;
        }
        try {
            persistWatcherHandoff();
            BileUtils.reload(this);
        } catch (Throwable e) {
            selfReloadQueued.set(false);
            deleteWatcherHandoffAfterFailedReload();
            getLogger().log(Level.SEVERE, "Failed to self-reload " + pluginName + " (" + context + ")", e);
            notifyBileUsers(localization.text(
                    BileMessages.RELOAD_FAILED,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ), false);
        }
    }

    private void persistWatcherHandoff() throws IOException {
        persistWatcherHandoff(remainingAutomaticBatchNanos(), false);
    }

    private void persistWatcherHandoff(long automaticBatchDelayNanos,
                                       boolean awaitAutomaticReloadCompletion) throws IOException {
        PluginJarDirectoryWatcher watcher = pluginJarWatcher;
        if (watcher == null) {
            throw new IOException("Plugin jar watcher is not active");
        }

        Map<Path, JarSnapshotStager.FileStamp> knownFiles = watcher.snapshot();
        Set<Path> paths = new LinkedHashSet<>(knownFiles.keySet());
        paths.addAll(trackedPluginNames.keySet());
        paths.addAll(pendingIdentityReplacements.keySet());
        Set<Path> pendingPaths = new HashSet<>();
        pendingPaths.addAll(pendingIdentityReplacements.keySet());
        pendingPaths.addAll(unresolvedJarSignals);
        pendingPaths.addAll(pendingObservations.keySet());
        pendingPaths.addAll(activeStageObservations.keySet());
        for (StageCompletion completion : completedStages) {
            pendingPaths.add(completion.path());
        }
        for (DeletionGraceQueue.Tombstone tombstone : deletionTombstones.snapshot()) {
            pendingPaths.add(tombstone.path());
        }
        pendingPaths.addAll(automaticReloadQueue.pendingSources());
        AutomaticReloadQueue.Candidate remoteSelfReload = remoteSelfReloadInProgress;
        if (remoteSelfReload != null) {
            pendingPaths.add(remoteSelfReload.source());
        }
        for (AutomaticBatchCompletion completion : completedAutomaticBatches) {
            for (AutomaticReloadQueue.Candidate selfReload : completion.selfReloads()) {
                pendingPaths.add(selfReload.source());
            }
        }
        paths.addAll(pendingPaths);

        List<WatcherStateHandoff.Entry> entries = new ArrayList<>(paths.size());
        for (Path path : paths) {
            List<WatcherStateHandoff.Replacement> replacements = new ArrayList<>();
            for (Map.Entry<String, Path> replacement : identityReplacements(path).entrySet()) {
                replacements.add(new WatcherStateHandoff.Replacement(
                        replacement.getKey(), replacement.getValue()));
            }
            entries.add(new WatcherStateHandoff.Entry(
                    path,
                    trackedPluginNames.get(path),
                    replacements,
                    knownFiles.get(path),
                    appliedFingerprints.get(path),
                    pendingPaths.contains(path)));
        }
        WatcherStateHandoff.write(
                watcherHandoffFile,
                folder.toPath(),
                Math.max(0L, Math.min(AUTOMATIC_RELOAD_INTERVAL_NANOS, automaticBatchDelayNanos)),
                awaitAutomaticReloadCompletion,
                entries);
    }

    private void observeAutomaticReloadCompletion(long nowNanos) {
        if (!automaticReloadQueue.isAwaitingReloadCompletion()) {
            return;
        }
        try {
            if (!AutomaticReloadCompletionHandoff.completionObserved(automaticReloadCompletionFile)) {
                automaticReloadCompletionFailure = null;
                return;
            }
            automaticReloadCompletionFailure = null;
            automaticReloadQueue.completeReloadHandoff(nowNanos);
            try {
                AutomaticReloadCompletionHandoff.deleteMarker(automaticReloadCompletionFile);
            } catch (IOException exception) {
                getLogger().log(Level.WARNING,
                        "Automatic self-reload completion was observed, but its marker could not be deleted",
                        exception);
            }
        } catch (IOException exception) {
            String failure = rootMessage(exception);
            if (!failure.equals(automaticReloadCompletionFailure)) {
                automaticReloadCompletionFailure = failure;
                getLogger().log(Level.SEVERE,
                        "Could not observe automatic self-reload completion; the reload queue remains paused",
                        exception);
            }
        }
    }

    private void closeAutomaticReloadCompletionHandoff(AutomaticReloadCompletionHandoff completionHandoff) {
        if (completionHandoff == null) {
            return;
        }
        try {
            completionHandoff.close();
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not release the automatic self-reload completion handoff", exception);
        }
    }

    private void deleteWatcherHandoffAfterFailedReload() {
        if (BileTools.bile != this || !tickerActive) {
            return;
        }
        try {
            WatcherStateHandoff.delete(watcherHandoffFile);
        } catch (IOException exception) {
            getLogger().log(Level.WARNING,
                    "Could not delete watcher state after the self-reload failed before unload", exception);
        }
    }

    private long remainingAutomaticBatchNanos() {
        return automaticReloadQueue.remainingBatchDelay(System.nanoTime());
    }

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private void restoreAppliedFingerprint(Path path, String previousFingerprint) {
        if (previousFingerprint == null || previousFingerprint.isEmpty()) {
            appliedFingerprints.remove(path);
        } else {
            appliedFingerprints.put(path, previousFingerprint);
        }
    }

    private void executePluginLifecycle(String pluginName, String operationName, ThrowingRunnable operation) throws Throwable {
        if (operation == null) {
            throw new IllegalArgumentException("Plugin lifecycle operation must not be null");
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        boolean scheduled = runGlobal(() -> {
            try {
                operation.run();
                completion.complete(null);
            } catch (Throwable t) {
                completion.completeExceptionally(t);
            }
        });

        if (!scheduled) {
            throw new IllegalStateException("Unable to schedule plugin operation on the authoritative server thread: "
                    + operationName);
        }

        try {
            completion.get(PLUGIN_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (pluginName != null) {
                markPluginDirty(pluginName, "interrupted: " + operationName);
            }
            throw new IllegalStateException("Interrupted while waiting for plugin operation: " + operationName, e);
        } catch (TimeoutException e) {
            if (pluginName != null) {
                markPluginDirty(pluginName, "timeout: " + operationName);
            }
            throw new IllegalStateException("Timed out while waiting for plugin operation: " + operationName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause != null) {
                throw cause;
            }

            throw e;
        }
    }

    private void scheduleTicker(long delayTicks) {
        if (!tickerActive || !isEnabled()) {
            return;
        }

        long safeDelay = Math.max(1L, delayTicks);
        if (!runGlobalLater(() -> {
            if (!tickerActive || !isEnabled()) {
                return;
            }

            try {
                onTick();
            } catch (Throwable throwable) {
                getLogger().log(Level.SEVERE, "BileTools ticker failed; the next polling cycle will retry", throwable);
            } finally {
                if (tickerActive && isEnabled()) {
                    long nextDelay = watcherBusy
                            ? (cfg == null ? 5L : cfg.getWatcherActivePollTicks())
                            : (cfg == null ? 20L : cfg.getWatcherIdlePollTicks());
                    scheduleTicker(nextDelay);
                }
            }
        }, safeDelay)) {
            tickerActive = false;
            getLogger().warning("Failed to schedule BileTools ticker task.");
        }
    }

    private boolean runGlobal(Runnable runnable) {
        return PlatformTasks.runGlobal(this, runnable);
    }

    private boolean runGlobalLater(Runnable runnable, long delayTicks) {
        return PlatformTasks.runGlobal(this, runnable, delayTicks);
    }

    private boolean runAsync(Runnable runnable) {
        return PlatformTasks.runAsync(this, runnable);
    }

    private void backupPluginFile(File sourceFile, String pluginName, String pluginVersion) {
        if (sourceFile == null || pluginName == null || pluginVersion == null) {
            return;
        }

        try {
            BileUtils.copy(sourceFile, new File(BileUtils.getBackupLocation(pluginName), pluginVersion + ".jar"));
            getLogger().info("Backed up " + pluginName + " " + pluginVersion);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Failed to back up " + pluginName + " " + pluginVersion, e);
        }
    }

    private String resolvePluginName(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try {
            Plugin loaded = BileUtils.getPlugin(file);
            if (loaded != null) {
                return loaded.getName();
            }
        } catch (Throwable ignored) {
        }

        try {
            return BileUtils.getPluginName(file);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public DirectorRuntimeEngine getDirector() {
        DirectorRuntimeEngine local = director;
        if (local != null) {
            return local;
        }

        synchronized (this) {
            if (director != null) {
                return director;
            }

            director = DirectorEngineFactory.create(
                    new CommandBile(this),
                    DirectorEngineOptions.builder()
                            .contexts(buildDirectorContexts())
                            .textResolver(localization.directorResolver())
                            .build()
            );
            return director;
        }
    }

    public BileLocalization getLocalization() {
        return localization;
    }

    private DirectorContextRegistry buildDirectorContexts() {
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender) {
                return sender.sender();
            }

            return null;
        });

        contexts.register(Player.class, (invocation, map) -> {
            if (invocation.getSender() instanceof BukkitDirectorSender sender && sender.sender() instanceof Player player) {
                return player;
            }

            return null;
        });

        return contexts;
    }

    private DirectorExecutionResult runDirector(CommandSender sender, String label, String[] args) {
        try {
            return getDirector().execute(new DirectorInvocation(new BukkitDirectorSender(sender), label, Arrays.asList(args)));
        } catch (Throwable e) {
            getLogger().log(Level.SEVERE, "Director command execution failed", e);
            return DirectorExecutionResult.notHandled();
        }
    }

    private List<String> runDirectorTab(CommandSender sender, String alias, String[] args) {
        try {
            return getDirector().tabComplete(new DirectorInvocation(new BukkitDirectorSender(sender), alias, Arrays.asList(args)));
        } catch (Throwable e) {
            getLogger().log(Level.WARNING, "Director tab completion failed", e);
            return List.of();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!isRootCommand(command)) {
            return false;
        }

        if (!sender.hasPermission(ROOT_PERMISSION)) {
            ComponentMessenger.send(sender, localization.text(
                    BileMessages.PERMISSION_DENIED,
                    MessageArgs.builder().untrusted("permission", ROOT_PERMISSION).build()
            ));
            return true;
        }

        if (BileFancyMenu.sendIfHelpRequested(sender, getDirector(), args, localization.directorResolver())) {
            BileFancyMenu.playSuccessSound(sender);
            return true;
        }

        DirectorExecutionResult result = runDirector(sender, label, args);
        if (result.isSuccess()) {
            BileFancyMenu.playSuccessSound(sender);
            return true;
        }

        BileFancyMenu.playFailureSound(sender);
        if (result.getMessage() == null || result.getMessage().trim().isEmpty()) {
            ComponentMessenger.send(sender, localization.text(
                    BileMessages.UNKNOWN_COMMAND,
                    MessageArgs.builder().untrusted("command", String.join(" ", args)).build()
            ));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ROOT_PERMISSION)) {
            return List.of();
        }

        if (!isRootCommand(command)) {
            return List.of();
        }

        List<String> suggestions = runDirectorTab(sender, alias, args);
        BileFancyMenu.playTabSound(sender);
        return suggestions;
    }

    public void loadPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, localization.text(
                BileMessages.LOAD_QUEUED,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        ));
        enqueuePluginOperation("cmd-load:" + pluginName, () -> {
            try {
                File pluginFile = BileUtils.getPluginFile(pluginName);
                if (pluginFile == null) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.PLUGIN_NOT_FOUND,
                            MessageArgs.builder().untrusted("plugin", pluginName).build()
                    ));
                    return;
                }

                long startNs = System.nanoTime();
                executePluginLifecycle(pluginName, "load " + pluginName, () -> BileUtils.load(pluginFile));
                Plugin loaded = BileUtils.getPluginByName(pluginName);
                String resolvedName = loaded == null ? pluginName : loaded.getName();
                clearPluginDirty(resolvedName);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                sendCommandMessage(sender, localization.text(
                        BileMessages.LOAD_SUCCESS,
                        MessageArgs.builder()
                                .untrusted("plugin", resolvedName)
                                .untrusted("file", pluginFile.getName())
                                .untrusted("milliseconds", totalMs)
                                .build()
                ));
            } catch (BileUtils.RestartRequiredException e) {
                getLogger().warning(e.getMessage());
                sendCommandMessage(sender, localization.text(
                        BileMessages.RESTART_REQUIRED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
            } catch (Throwable e) {
                sendCommandMessage(sender, localization.text(
                        BileMessages.LOAD_FAILED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
                getLogger().log(Level.SEVERE, "Failed to load plugin " + pluginName, e);
            }
        });
    }

    public void unloadPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, localization.text(
                BileMessages.UNLOAD_QUEUED,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        ));
        enqueuePluginOperation("cmd-unload:" + pluginName, () -> {
            try {
                Plugin plugin = BileUtils.getPluginByName(pluginName);
                if (plugin == null) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.PLUGIN_NOT_FOUND,
                            MessageArgs.builder().untrusted("plugin", pluginName).build()
                    ));
                    return;
                }

                String name = plugin.getName();
                File sourceFile = BileUtils.getPluginFile(plugin);
                executePluginLifecycle(name, "unload " + pluginName, () -> BileUtils.unload(plugin));
                clearPluginDirty(name);
                String fileName = sourceFile == null ? (pluginName + ".jar") : sourceFile.getName();
                sendCommandMessage(sender, localization.text(
                        BileMessages.UNLOAD_COMMAND_SUCCESS,
                        MessageArgs.builder()
                                .untrusted("plugin", name)
                                .untrusted("file", fileName)
                                .build()
                ));
            } catch (Throwable e) {
                sendCommandMessage(sender, localization.text(
                        BileMessages.UNLOAD_COMMAND_FAILED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
                getLogger().log(Level.SEVERE, "Failed to unload plugin " + pluginName, e);
            }
        });
    }

    public void reloadPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, localization.text(
                BileMessages.RELOAD_QUEUED,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        ));
        enqueuePluginOperation("cmd-reload:" + pluginName, () -> {
            try {
                Plugin plugin = BileUtils.getPluginByName(pluginName);
                if (plugin == null) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.PLUGIN_NOT_FOUND,
                            MessageArgs.builder().untrusted("plugin", pluginName).build()
                    ));
                    return;
                }

                String name = plugin.getName();
                if (plugin == this) {
                    queueCommandSelfReload(sender, name);
                    return;
                }

                File sourceFile = BileUtils.getPluginFile(plugin);
                long startNs = System.nanoTime();
                executePluginLifecycle(name, "reload " + pluginName, () -> BileUtils.reload(plugin));
                clearPluginDirty(name);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                recordReloadSuccess(totalMs);
                String fileName = sourceFile == null ? (pluginName + ".jar") : sourceFile.getName();
                sendCommandMessage(sender, localization.text(
                        BileMessages.RELOAD_COMMAND_SUCCESS,
                        MessageArgs.builder()
                                .untrusted("plugin", name)
                                .untrusted("file", fileName)
                                .untrusted("milliseconds", totalMs)
                                .build()
                ));
            } catch (BileUtils.RestartRequiredException e) {
                getLogger().warning(e.getMessage());
                sendCommandMessage(sender, localization.text(
                        BileMessages.RESTART_REQUIRED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
            } catch (Throwable e) {
                markPluginDirty(pluginName, "manual reload failure: " + rootMessage(e));
                sendCommandMessage(sender, localization.text(
                        BileMessages.RELOAD_COMMAND_FAILED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
                getLogger().log(Level.SEVERE, "Failed to reload plugin " + pluginName, e);
            }
        });
    }

    public void uninstallPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, localization.text(
                BileMessages.UNINSTALL_QUEUED,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        ));
        enqueuePluginOperation("cmd-uninstall:" + pluginName, () -> {
            try {
                File pluginFile = BileUtils.getPluginFile(pluginName);
                if (pluginFile == null) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.PLUGIN_NOT_FOUND,
                            MessageArgs.builder().untrusted("plugin", pluginName).build()
                    ));
                    return;
                }

                String name = BileUtils.getPluginName(pluginFile);
                executePluginLifecycle(name, "uninstall " + pluginName, () -> BileUtils.delete(pluginFile));
                clearPluginDirty(name);

                sendCommandMessage(sender, localization.text(
                        BileMessages.UNINSTALL_SUCCESS,
                        MessageArgs.builder()
                                .untrusted("plugin", name)
                                .untrusted("file", pluginFile.getName())
                                .build()
                ));
                if (pluginFile.exists()) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.UNINSTALL_DELETE_FAILED,
                            MessageArgs.builder().untrusted("file", pluginFile.getName()).build()
                    ));
                }
            } catch (Throwable e) {
                sendCommandMessage(sender, localization.text(
                        BileMessages.UNINSTALL_FAILED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
                getLogger().log(Level.SEVERE, "Failed to uninstall plugin " + pluginName, e);
            }
        });
    }

    public void installLibraryPlugin(CommandSender sender, String pluginName, String version) {
        File libraryPlugin = new File(new File(getDataFolder(), "library"), pluginName);
        if (!libraryPlugin.exists() || !libraryPlugin.isDirectory()) {
            sendCommandMessage(sender, localization.text(
                    BileMessages.LIBRARY_PLUGIN_NOT_FOUND,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ));
            return;
        }

        File selected = null;
        if (version == null || version.trim().isEmpty() || version.equalsIgnoreCase("latest")) {
            selected = findLatestLibraryVersion(libraryPlugin);
        } else {
            File[] entries = libraryPlugin.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (entry != null && entry.isFile() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        String v = entry.getName().replace(".jar", "");
                        if (v.equalsIgnoreCase(version.trim())) {
                            selected = entry;
                            break;
                        }
                    }
                }
            }
        }

        if (selected == null) {
            sendCommandMessage(sender, localization.text(
                    BileMessages.LIBRARY_VERSION_NOT_FOUND,
                    MessageArgs.builder()
                            .untrusted("version", version)
                            .untrusted("plugin", pluginName)
                            .build()
            ));
            return;
        }

        sendCommandMessage(sender, localization.text(
                BileMessages.LIBRARY_INSTALL_QUEUED,
                MessageArgs.builder().untrusted("plugin", pluginName).build()
        ));
        File selectedVersion = selected;
        enqueuePluginOperation("cmd-install:" + pluginName, () -> {
            try {
                File out = new File(BileUtils.getPluginsFolder(), libraryPlugin.getName() + "-" + selectedVersion.getName());
                BileUtils.copy(selectedVersion, out);
                executePluginLifecycle(pluginName, "install " + pluginName, () -> BileUtils.load(out));
                clearPluginDirty(pluginName);
                sendCommandMessage(sender, localization.text(
                        BileMessages.LIBRARY_INSTALL_SUCCESS,
                        MessageArgs.builder().untrusted("file", out.getName()).build()
                ));
            } catch (BileUtils.RestartRequiredException e) {
                getLogger().warning(e.getMessage());
                sendCommandMessage(sender, localization.text(
                        BileMessages.LIBRARY_INSTALL_RESTART_REQUIRED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
            } catch (Throwable e) {
                sendCommandMessage(sender, localization.text(
                        BileMessages.LIBRARY_INSTALL_FAILED,
                        MessageArgs.builder().untrusted("plugin", pluginName).build()
                ));
                getLogger().log(Level.SEVERE, "Failed to install library plugin " + pluginName + "@" + version, e);
            }
        });
    }

    public void listLibrary(CommandSender sender) {
        File library = new File(getDataFolder(), "library");
        File[] plugins = library.listFiles();
        if (plugins == null || plugins.length == 0) {
            sendCommandMessage(sender, localization.text(BileMessages.LIBRARY_EMPTY));
            return;
        }

        for (File pluginDir : plugins) {
            if (pluginDir == null || !pluginDir.isDirectory()) {
                continue;
            }

            File latest = findLatestLibraryVersion(pluginDir);
            if (latest == null) {
                continue;
            }

            boolean installed = false;
            String installedVersion = null;
            File pluginsFolder = BileUtils.getPluginsFolder();
            File[] installedPlugins = pluginsFolder == null ? null : pluginsFolder.listFiles();
            if (installedPlugins != null) {
                for (File file : installedPlugins) {
                    if (file == null || !file.isFile()) {
                        continue;
                    }

                    try {
                        if (BileUtils.isPluginJar(file) && pluginDir.getName().equalsIgnoreCase(BileUtils.getPluginName(file))) {
                            installedVersion = BileUtils.getPluginVersion(file);
                            installed = true;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            if (installed) {
                sendCommandMessage(sender, localization.text(
                        BileMessages.LIBRARY_INSTALLED_ENTRY,
                        MessageArgs.builder()
                                .untrusted("plugin", pluginDir.getName())
                                .untrusted("installedVersion", installedVersion)
                                .untrusted("latestVersion", latest.getName().replace(".jar", ""))
                                .build()
                ));
            } else {
                sendCommandMessage(sender, localization.text(
                        BileMessages.LIBRARY_ENTRY,
                        MessageArgs.builder()
                                .untrusted("plugin", pluginDir.getName())
                                .untrusted("latestVersion", latest.getName().replace(".jar", ""))
                                .build()
                ));
            }
        }
    }

    public void listLibrary(CommandSender sender, String pluginName) {
        File pluginDir = new File(new File(getDataFolder(), "library"), pluginName);
        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            sendCommandMessage(sender, localization.text(
                    BileMessages.LIBRARY_PLUGIN_NOT_FOUND,
                    MessageArgs.builder().untrusted("plugin", pluginName).build()
            ));
            return;
        }

        File latest = findLatestLibraryVersion(pluginDir);
        File[] versions = pluginDir.listFiles();
        if (versions != null) {
            for (File version : versions) {
                if (version != null && version.isFile() && version.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    sendCommandMessage(sender, localization.text(
                            BileMessages.LIBRARY_VERSION_ENTRY,
                            MessageArgs.builder().untrusted("version", version.getName().replace(".jar", "")).build()
                    ));
                }
            }
        }

        if (latest != null) {
            sendCommandMessage(sender, localization.text(
                    BileMessages.LIBRARY_LATEST_ENTRY,
                    MessageArgs.builder()
                            .untrusted("plugin", pluginDir.getName())
                            .untrusted("version", latest.getName().replace(".jar", ""))
                            .build()
            ));
        }
    }

    private File findLatestLibraryVersion(File pluginLibraryFolder) {
        if (pluginLibraryFolder == null || !pluginLibraryFolder.exists() || !pluginLibraryFolder.isDirectory()) {
            return null;
        }

        long highest = Long.MIN_VALUE;
        File latest = null;
        File[] entries = pluginLibraryFolder.listFiles();
        if (entries == null) {
            return null;
        }

        for (File jar : entries) {
            if (jar == null || !jar.isFile() || !jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                continue;
            }

            long score = scoreVersion(jar.getName().replace(".jar", ""));
            if (score > highest) {
                highest = score;
                latest = jar;
            }
        }

        return latest;
    }

    private long scoreVersion(String version) {
        List<Integer> digits = new ArrayList<>();
        for (char c : version.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.add(Integer.parseInt(String.valueOf(c)));
            }
        }

        Collections.reverse(digits);
        long score = 0;
        for (int i = 0; i < digits.size(); i++) {
            score += (long) Math.pow(digits.get(i), (i + 2));
        }

        return score;
    }

    private boolean isRootCommand(Command command) {
        String name = command.getName();
        return name.equalsIgnoreCase(ROOT_COMMAND);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
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

    private record ReplacementMatch(String pluginName, Path sourcePath, Path previousReplacementTarget) {
    }

    private record AutomaticBatchCompletion(List<AutomaticReloadQueue.Candidate> selfReloads,
                                            long completedNanos) {
        private AutomaticBatchCompletion {
            selfReloads = List.copyOf(selfReloads);
        }
    }

    private record BukkitDirectorSender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return sender instanceof Player;
        }

        @Override
        public void sendMessage(String message) {
            if (message != null && !message.trim().isEmpty()) {
                ComponentMessenger.sendLiteral(sender, message);
            }
        }
    }
}
