package com.volmit.bile;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand;
import art.arcane.volmlib.integration.ReloadAware;
import com.volmit.bile.command.BileFancyMenu;
import com.volmit.bile.command.CommandBile;
import com.volmit.bile.config.BileConfig;
import net.md_5.bungee.api.ChatColor;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor, TabCompleter, ReloadAware {
    private static final String ROOT_COMMAND = "biletools";
    private static final String ROOT_PERMISSION = "bile.use";
    private static final int HOT_DROP_RETRY_LIMIT = 18;
    private static final long HOT_DROP_INITIAL_DELAY_TICKS = 5L;
    private static final long HOT_DROP_RETRY_DELAY_TICKS = 10L;
    private static final long PLUGIN_OPERATION_TIMEOUT_SECONDS = 120L;
    private static final long REMOTE_DEPLOY_TIMEOUT_SECONDS = 90L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SlaveBileServer srv;
    public static BileTools bile;
    private HashMap<File, Long> mod;
    private HashMap<File, Long> las;
    private HashMap<File, String> sig;
    private HashMap<File, String> trackedPluginNames;
    private final Map<File, PendingFingerprint> pendingFingerprints = new ConcurrentHashMap<>();
    private final Set<String> dirtyPlugins = ConcurrentHashMap.newKeySet();
    private final Set<String> coalescedReloadNames = ConcurrentHashMap.newKeySet();
    private final Map<String, File> coalescedRemoteSources = new ConcurrentHashMap<>();
    private final AtomicBoolean coalesceFlushScheduled = new AtomicBoolean(false);
    private File folder;
    private File backoff;
    public String tag;
    private Sound sx;
    private int cd = 10;
    private volatile boolean tickerActive;
    private volatile boolean watcherBusy;
    private volatile DirectorRuntimeEngine director;
    private volatile DirectorVisualCommand helpRoot;
    private final AtomicBoolean selfReloadQueued = new AtomicBoolean(false);
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
    private final ExecutorService fingerprintExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BileTools-Fingerprint");
        thread.setDaemon(true);
        return thread;
    });
    public static BileConfig cfg;

    public static void streamFile(File f, String address, int port, String password) throws IOException {
        int timeoutMs = cfg == null ? 15_000 : cfg.getRemoteSocketTimeoutMs();
        long maxBytes = cfg == null ? 256L * 1024L * 1024L : cfg.getRemoteMaxTransferBytes();
        RemoteDeployProtocol.streamFile(f, address, port, password, timeoutMs, maxBytes);
    }

    private record PendingFingerprint(long length, long lastModified, int stableTicks) {
    }

    private void readTheConfig() throws Exception {
        File f = new File(getDataFolder(), "config.yml");
        cfg = BileConfig.load(f);
    }

    @Override
    public void onEnable() {
        cfg = BileConfig.defaults();
        try {
            readTheConfig();
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Unable to read the config...", e);
        }

        SplashScreen.print(this);
        getLogger().info("Runtime platform: " + ServerPlatform.summary());
        if (ServerPlatform.isFoliaFamily()) {
            getLogger().info("Folia/Canvas detected: using GlobalRegionScheduler; classic Bukkit scheduler is avoided.");
            getLogger().warning("Hot-reload of third-party plugins on Folia/Canvas is best-effort; plugins without folia-supported may still break.");
        } else if (!ServerPlatform.isPaperFamily()) {
            getLogger().info("Spigot-compatible mode: Paper force-load paths disabled; paper-plugin.yml jars use plugin.yml shims.");
        }

        if (cfg.isRemoteSlaveEnabled()) {
            getLogger().info("Starting Remote Slave Server on *:" + cfg.getRemoteSlavePort());

            try {
                srv = new SlaveBileServer();
                srv.start();
                getLogger().info("Remote Slave Server online!");
            } catch (Throwable e) {
                getLogger().warning("Starting Remote Slave Server on *:" + cfg.getRemoteSlavePort());
                e.printStackTrace();
            }
        }

        cd = 10;
        bile = this;
        tag = ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY;
        mod = new HashMap<>();
        las = new HashMap<>();
        sig = new HashMap<>();
        trackedPluginNames = new HashMap<>();
        folder = BileUtils.getPluginsFolder();
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
        fingerprintExecutor.shutdownNow();

        PlatformTasks.cancelPluginTasks(this);
    }

    @Override
    public void onPreUnload(ReloadAware.PreUnloadReason reason) {
        getLogger().info("BileTools pre-unload hook fired (" + reason + "). Freezing watcher + slave before unload.");
        freezeForUnload();
    }

    private void freezeForUnload() {
        tickerActive = false;
        queuedOperationKeys.clear();
        pendingFingerprints.clear();
        coalescedReloadNames.clear();
        coalescedRemoteSources.clear();
        coalesceFlushScheduled.set(false);
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
        trackFileState(f);
    }

    private void scheduleHotDrop(File file) {
        scheduleHotDrop(file, HOT_DROP_RETRY_LIMIT, HOT_DROP_INITIAL_DELAY_TICKS);
    }

    private void scheduleHotDrop(File file, int attemptsRemaining, long delayTicks) {
        runGlobalLater(() -> attemptHotDrop(file, attemptsRemaining), delayTicks);
    }

    private void attemptHotDrop(File file, int attemptsRemaining) {
        if (file == null || !file.exists() || !file.isFile()) {
            return;
        }

        try {
            if (!isReadablePluginJar(file)) {
                if (attemptsRemaining > 0) {
                    getLogger().info("Hot drop waiting for completed jar write: " + file.getName() + " (" + attemptsRemaining + " retries left)");
                    scheduleHotDrop(file, attemptsRemaining - 1, HOT_DROP_RETRY_DELAY_TICKS);
                    return;
                }

                throw new IOException("Jar is not readable after waiting for copy completion: " + file.getName());
            }
            String operationKey = "hotdrop:" + file.getAbsolutePath().toLowerCase(Locale.ROOT);
            enqueuePluginOperation(operationKey, () -> {
                try {
                    getLogger().info("Hot dropping " + file.getName());
                    String resolvedName = null;
                    try {
                        resolvedName = BileUtils.getPluginName(file);
                    } catch (Throwable ignored) {
                    }
                    executePluginLifecycle(resolvedName, "hot drop " + file.getName(), () -> BileUtils.load(file));
                    if (resolvedName != null) {
                        clearPluginDirty(resolvedName);
                    }
                    getLogger().info("Hot dropped " + file.getName() + " successfully");
                    notifyBileUsers(tag + "Hot Dropped " + ChatColor.WHITE + file.getName(), true);
                } catch (Throwable e) {
                    if (attemptsRemaining > 0 && isTransientJarState(e)) {
                        getLogger().info("Hot drop deferred for " + file.getName() + ": " + rootMessage(e) + " (" + attemptsRemaining + " retries left)");
                        runGlobal(() -> scheduleHotDrop(file, attemptsRemaining - 1, HOT_DROP_RETRY_DELAY_TICKS));
                        return;
                    }

                    getLogger().log(Level.SEVERE, "Failed to hot drop " + file.getName(), e);
                    notifyBileUsers(tag + "Failed to hot drop " + ChatColor.RED + file.getName(), false);
                }
            });
        } catch (Throwable e) {
            if (attemptsRemaining > 0 && isTransientJarState(e)) {
                getLogger().info("Hot drop deferred for " + file.getName() + ": " + rootMessage(e) + " (" + attemptsRemaining + " retries left)");
                scheduleHotDrop(file, attemptsRemaining - 1, HOT_DROP_RETRY_DELAY_TICKS);
                return;
            }

            getLogger().log(Level.SEVERE, "Failed to hot drop " + file.getName(), e);

            for (Player k : Bukkit.getOnlinePlayers()) {
                if (k.hasPermission("bile.use")) {
                    k.sendMessage(tag + "Failed to hot drop " + ChatColor.RED + file.getName());
                }
            }
        }
    }

    private boolean isReadablePluginJar(File file) {
        try (ZipFile zipFile = new ZipFile(file)) {
            return zipFile.getEntry("plugin.yml") != null || zipFile.getEntry("paper-plugin.yml") != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean isTransientJarState(Throwable throwable) {
        Throwable root = rootCause(throwable);
        if (root instanceof ZipException) {
            return true;
        }

        String message = root.getMessage();
        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("zip end header not found")
                || lower.contains("zip file is empty")
                || lower.contains("error in opening zip file")
                || lower.contains("no such file");
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
        if (cd > 0) {
            cd--;
        }

        File[] files = folder.listFiles();
        if (files == null) {
            return;
        }

        boolean pendingWork = !pendingFingerprints.isEmpty();
        int debounceTicks = cfg == null ? 8 : cfg.getWatcherFingerprintDebounceTicks();

        for (File i : files) {
            if (!i.getName().toLowerCase(Locale.ROOT).endsWith(".jar") || !i.isFile()) {
                continue;
            }

            // Ignore in-progress remote receives
            if (i.getName().toLowerCase(Locale.ROOT).endsWith(".jar.part")) {
                continue;
            }

            if (!mod.containsKey(i)) {
                getLogger().log(Level.INFO, "Now Tracking: " + i.getName());

                if (cfg.isArchivePlugins()) {
                    Plugin trackedPlugin = BileUtils.getPlugin(i);
                    if (trackedPlugin != null) {
                        String trackedPluginName = trackedPlugin.getName();
                        String trackedPluginVersion = trackedPlugin.getDescription().getVersion();
                        File trackedPluginFile = BileUtils.getPluginFile(trackedPlugin);
                        runAsync(() -> backupPluginFile(trackedPluginFile, trackedPluginName, trackedPluginVersion));
                    }
                }

                trackFileMetadata(i);
                trackPluginName(i);

                if (cd == 0) {
                    String hotDropName = resolvePluginName(i);
                    if (hotDropName != null && !isAutoLifecycleAllowed(hotDropName)) {
                        getLogger().info("Skipping hot drop for ignored/filtered plugin " + hotDropName + " (" + i.getName() + ")");
                    } else {
                        getLogger().info("Scheduling hot drop for " + i.getName());
                        scheduleHotDrop(i);
                    }
                }
                continue;
            }

            long length = i.length();
            long lastModified = i.lastModified();
            Long trackedLength = mod.get(i);
            Long trackedModified = las.get(i);

            if (trackedLength == null || trackedModified == null
                    || trackedLength != length || trackedModified != lastModified) {
                mod.put(i, length);
                las.put(i, lastModified);
                BileUtils.invalidateJarMeta(i);
                // Reset debounce window whenever size/mtime moves.
                pendingFingerprints.put(i, new PendingFingerprint(length, lastModified, 0));
                pendingWork = true;
            }
        }

        for (Map.Entry<File, PendingFingerprint> entry : new ArrayList<>(pendingFingerprints.entrySet())) {
            File file = entry.getKey();
            PendingFingerprint pending = entry.getValue();
            if (!file.exists() || !file.isFile()) {
                pendingFingerprints.remove(file);
                continue;
            }

            long length = file.length();
            long lastModified = file.lastModified();
            if (length != pending.length() || lastModified != pending.lastModified()) {
                // Will be reset by metadata loop next pass; keep active polling.
                pendingWork = true;
                continue;
            }

            int nextStable = pending.stableTicks() + 1;
            if (nextStable < debounceTicks) {
                pendingFingerprints.put(file, new PendingFingerprint(length, lastModified, nextStable));
                pendingWork = true;
                continue;
            }

            pendingFingerprints.remove(file);
            queueFingerprintCheck(file, length, lastModified);
            pendingWork = true;
        }

        Set<File> removed = new LinkedHashSet<>(mod.keySet());
        for (File i : files) {
            if (i.getName().toLowerCase(Locale.ROOT).endsWith(".jar") && i.isFile()
                    && !i.getName().toLowerCase(Locale.ROOT).endsWith(".jar.part")) {
                removed.remove(i);
            }
        }

        for (File file : removed) {
            handleRemovedTrackedFile(file);
        }

        watcherBusy = pendingWork
                || !pendingFingerprints.isEmpty()
                || !coalescedReloadNames.isEmpty()
                || coalesceFlushScheduled.get();
    }

    private void queueFingerprintCheck(File file, long length, long lastModified) {
        String operationKey = "fingerprint:" + file.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (!queuedOperationKeys.add(operationKey)) {
            return;
        }

        try {
            fingerprintExecutor.execute(() -> {
                try {
                    if (!isEnabled() || !file.exists()) {
                        return;
                    }

                    if (file.length() != length || file.lastModified() != lastModified) {
                        // still changing; re-queue via next tick metadata mismatch
                        return;
                    }

                    String previousSignature = sig.get(file);
                    String currentSignature = fingerprint(file);
                    trackFileMetadata(file);
                    if (currentSignature != null) {
                        sig.put(file, currentSignature);
                    }
                    trackPluginName(file);

                    if (previousSignature != null
                            && currentSignature != null
                            && previousSignature.equals(currentSignature)) {
                        return;
                    }

                    String pluginName = trackedPluginNames.get(file);
                    if (pluginName == null || pluginName.trim().isEmpty()) {
                        Plugin matched = BileUtils.getPlugin(file);
                        if (matched != null) {
                            pluginName = matched.getName();
                            trackedPluginNames.put(file, pluginName);
                        }
                    }

                    if (pluginName == null) {
                        for (Plugin plugin : Bukkit.getServer().getPluginManager().getPlugins()) {
                            File pluginFile = BileUtils.getPluginFile(plugin);
                            if (pluginFile != null && pluginFile.getName().equals(file.getName())) {
                                pluginName = plugin.getName();
                                trackedPluginNames.put(file, pluginName);
                                break;
                            }
                        }
                    }

                    if (pluginName == null || pluginName.trim().isEmpty()) {
                        getLogger().info("Content change for untracked jar " + file.getName() + " (no loaded plugin match)");
                        return;
                    }

                    if (isPluginDirty(pluginName)) {
                        getLogger().warning("Skipping reload for dirty plugin " + pluginName + " after prior lifecycle timeout");
                        return;
                    }

                    if (!isAutoLifecycleAllowed(pluginName)) {
                        getLogger().info("Skipping auto-reload for ignored/filtered plugin " + pluginName);
                        return;
                    }

                    final String resolvedName = pluginName;
                    getLogger().log(Level.INFO, "File change detected: " + file.getName());
                    getLogger().log(Level.INFO, "Identified Plugin: " + resolvedName + " <-> " + file.getName());

                    if (cfg.isRemoteMasterEnabled() && cfg.hasRemoteDeploySignature(resolvedName)) {
                        scheduleCoalescedReload(resolvedName, file, true);
                    } else {
                        scheduleCoalescedReload(resolvedName, file, false);
                    }
                } catch (Throwable e) {
                    getLogger().log(Level.WARNING, "Fingerprint check failed for " + file.getName(), e);
                } finally {
                    queuedOperationKeys.remove(operationKey);
                }
            });
        } catch (RejectedExecutionException e) {
            queuedOperationKeys.remove(operationKey);
        }
    }

    private boolean isAutoLifecycleAllowed(String pluginName) {
        if (cfg == null) {
            return true;
        }
        return cfg.isAutoReloadAllowed(pluginName);
    }

    /**
     * Batches nearby jar change events into one ordered reload flush so multi-module
     * Gradle exports (plugin + soft-deps) do not thrash.
     */
    private void scheduleCoalescedReload(String pluginName, File sourceFile, boolean remoteDeploy) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }

        String key = pluginName.toLowerCase(Locale.ROOT);
        coalescedReloadNames.add(key);
        if (remoteDeploy && sourceFile != null) {
            coalescedRemoteSources.put(key, sourceFile);
        }

        int window = cfg == null ? 10 : cfg.getWatcherCoalesceWindowTicks();
        if (window <= 0) {
            flushCoalescedReloads();
            return;
        }

        if (coalesceFlushScheduled.compareAndSet(false, true)) {
            if (!runGlobalLater(() -> {
                coalesceFlushScheduled.set(false);
                flushCoalescedReloads();
            }, window)) {
                coalesceFlushScheduled.set(false);
                flushCoalescedReloads();
            }
        }
    }

    private void flushCoalescedReloads() {
        if (coalescedReloadNames.isEmpty()) {
            return;
        }

        List<String> batch = new ArrayList<>(coalescedReloadNames);
        Map<String, File> remoteSources = new HashMap<>(coalescedRemoteSources);
        coalescedReloadNames.clear();
        coalescedRemoteSources.clear();

        batch.sort(this::compareReloadOrder);
        getLogger().info("Coalesced reload batch (" + batch.size() + "): " + String.join(", ", batch));

        for (String nameKey : batch) {
            String displayName = resolveCanonicalPluginName(nameKey);
            File remoteSource = remoteSources.get(nameKey);
            if (remoteSource != null && cfg != null && cfg.isRemoteMasterEnabled()
                    && cfg.hasRemoteDeploySignature(displayName)) {
                queueRemoteDeployReload(remoteSource, displayName);
            } else {
                queuePluginReload(displayName);
            }
        }
    }

    private String resolveCanonicalPluginName(String nameKey) {
        Plugin plugin = BileUtils.getPluginByName(nameKey);
        if (plugin != null) {
            return plugin.getName();
        }
        return nameKey;
    }

    private int compareReloadOrder(String aKey, String bKey) {
        Plugin a = BileUtils.getPluginByName(aKey);
        Plugin b = BileUtils.getPluginByName(bKey);
        if (a != null && b != null) {
            if (pluginDependsOn(a, b)) {
                return 1; // a depends on b -> reload b first
            }
            if (pluginDependsOn(b, a)) {
                return -1;
            }
        }
        return aKey.compareToIgnoreCase(bKey);
    }

    private boolean pluginDependsOn(Plugin plugin, Plugin dependency) {
        if (plugin == null || dependency == null) {
            return false;
        }
        String depName = dependency.getName();
        return plugin.getDescription().getDepend().contains(depName)
                || plugin.getDescription().getSoftDepend().contains(depName);
    }

    private void queuePluginReload(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }

        if (isPluginDirty(pluginName)) {
            getLogger().warning("Refusing reload for dirty plugin " + pluginName);
            return;
        }

        String key = "reload:" + pluginName.toLowerCase(Locale.ROOT);
        enqueuePluginOperation(key, () -> {
            Plugin targetPlugin = BileUtils.getPluginByName(pluginName);
            if (targetPlugin == null) {
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
                return;
            }

            if (targetPlugin == this) {
                queueSelfReload("file update");
                return;
            }

            long startNs = System.nanoTime();
            try {
                executePluginLifecycle(pluginName, "reload " + pluginName, () -> BileUtils.reload(targetPlugin));
                clearPluginDirty(pluginName);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                notifyBileUsers(tag + "Reloaded " + ChatColor.WHITE + pluginName
                        + ChatColor.GRAY + " (" + totalMs + "ms)", true);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "Failed to reload " + pluginName, e);
                markPluginDirty(pluginName, "health or lifecycle failure: " + rootMessage(e));
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
            }
        });
    }

    private void queuePluginUnload(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }

        if (!isAutoLifecycleAllowed(pluginName)) {
            getLogger().info("Skipping auto-unload for ignored/filtered plugin " + pluginName);
            return;
        }

        String key = "unload:" + pluginName.toLowerCase(Locale.ROOT);
        enqueuePluginOperation(key, () -> {
            Plugin targetPlugin = BileUtils.getPluginByName(pluginName);
            if (targetPlugin == null) {
                return;
            }

            if (targetPlugin == this) {
                getLogger().info("Detected removal for " + targetPlugin.getName() + ", skipping automatic self-unload.");
                return;
            }

            long startNs = System.nanoTime();
            try {
                executePluginLifecycle(pluginName, "unload " + pluginName, () -> BileUtils.unload(targetPlugin));
                clearPluginDirty(pluginName);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                notifyBileUsers(tag + "Unloaded " + ChatColor.WHITE + pluginName
                        + ChatColor.GRAY + " (" + totalMs + "ms)", false);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "Failed to unload " + pluginName + " after file removal", e);
                notifyBileUsers(tag + "Failed to Unload " + ChatColor.RED + pluginName, false);
            }
        });
    }

    private void queueRemoteDeployReload(File sourceFile, String pluginName) {
        if (sourceFile == null || pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }

        if (isPluginDirty(pluginName)) {
            getLogger().warning("Refusing remote deploy reload for dirty plugin " + pluginName);
            return;
        }

        String key = "remote-reload:" + pluginName.toLowerCase(Locale.ROOT);
        enqueuePluginOperation(key, () -> {
            deployToRemoteTargets(sourceFile, pluginName);

            Plugin targetPlugin = BileUtils.getPluginByName(pluginName);
            if (targetPlugin == null) {
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
                return;
            }

            if (targetPlugin == this) {
                queueSelfReload("remote deploy");
                return;
            }

            long startNs = System.nanoTime();
            try {
                executePluginLifecycle(pluginName, "reload " + pluginName + " after remote deploy", () -> BileUtils.reload(targetPlugin));
                clearPluginDirty(pluginName);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                notifyBileUsers(tag + "Reloaded " + ChatColor.WHITE + pluginName
                        + ChatColor.GRAY + " (" + totalMs + "ms)", true);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "Failed to reload " + pluginName + " after remote deploy", e);
                markPluginDirty(pluginName, "health or lifecycle failure: " + rootMessage(e));
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
            }
        });
    }

    private void deployToRemoteTargets(File sourceFile, String pluginName) {
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
                    streamFile(sourceFile, host, port, password);
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

        notifyBileUsers(
                tag + "Deployed " + ChatColor.WHITE + pluginName + ChatColor.GRAY + " to "
                        + targets.size() + " remote server(s)",
                false
        );
    }

    private void notifyBileUsers(String message, boolean playSound) {
        runGlobal(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission(ROOT_PERMISSION)) {
                    continue;
                }

                // Folia/Canvas: entity-thread for location/sound; message is still safe via entity task.
                PlatformTasks.runForPlayer(this, player, () -> {
                    player.sendMessage(message);
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

    private void sendCommandMessage(CommandSender sender, String message) {
        if (sender == null || message == null) {
            return;
        }

        if (sender instanceof Player player) {
            if (!PlatformTasks.runForPlayer(this, player, () -> player.sendMessage(message))) {
                player.sendMessage(message);
            }
            return;
        }

        if (!runGlobal(() -> sender.sendMessage(message))) {
            sender.sendMessage(message);
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
            notifyBileUsers(tag + ChatColor.RED + pluginName + ChatColor.GRAY + " marked dirty after lifecycle failure. Auto reloads paused for it.", false);
        }
    }

    private void clearPluginDirty(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return;
        }
        dirtyPlugins.remove(pluginName.toLowerCase(Locale.ROOT));
    }

    private void queueSelfReload(String source) {
        if (!selfReloadQueued.compareAndSet(false, true)) {
            return;
        }

        String context = (source == null || source.trim().isEmpty()) ? "reload request" : source.trim();
        String pluginName = getName();

        notifyBileUsers(tag + "Reloading " + ChatColor.WHITE + pluginName + ChatColor.GRAY + " (" + context + ")", false);

        if (!runGlobalLater(() -> performSelfReload(pluginName, context), 1L)) {
            selfReloadQueued.set(false);
            getLogger().warning("Failed to schedule self-reload for " + pluginName + " (" + context + ")");
            notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
        }
    }

    private void performSelfReload(String pluginName, String context) {
        try {
            BileUtils.reload(this);
        } catch (Throwable e) {
            selfReloadQueued.set(false);
            getLogger().log(Level.SEVERE, "Failed to self-reload " + pluginName + " (" + context + ")", e);
            notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
        }
    }

    private void executePluginLifecycle(String operationName, ThrowingRunnable operation) throws Throwable {
        executePluginLifecycle(null, operationName, operation);
    }

    private void executePluginLifecycle(String pluginName, String operationName, ThrowingRunnable operation) throws Throwable {
        if (operation == null || !isEnabled()) {
            return;
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
            // Never run plugin lifecycle off the global/main thread on Folia/Canvas.
            if (ServerPlatform.isFoliaFamily()) {
                throw new IllegalStateException("Unable to schedule plugin operation on Folia/Canvas global region: " + operationName);
            }

            // Spigot/Paper: if already on primary thread, run inline; otherwise fail loudly.
            if (Bukkit.isPrimaryThread()) {
                operation.run();
                return;
            }

            throw new IllegalStateException("Unable to schedule plugin operation on server main thread: " + operationName);
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

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
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

            onTick();
            long nextDelay = watcherBusy
                    ? (cfg == null ? 5L : cfg.getWatcherActivePollTicks())
                    : (cfg == null ? 20L : cfg.getWatcherIdlePollTicks());
            scheduleTicker(nextDelay);
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

    private void trackFileState(File file) {
        trackFileMetadata(file);
        if (file != null) {
            String signature = fingerprint(file);
            if (signature != null) {
                sig.put(file, signature);
            }
        }
    }

    private void trackFileMetadata(File file) {
        if (file == null) {
            return;
        }

        mod.put(file, file.length());
        las.put(file, file.lastModified());
    }

    private void trackPluginName(File file) {
        if (file == null) {
            return;
        }

        String pluginName = resolvePluginName(file);
        if (pluginName != null && !pluginName.trim().isEmpty()) {
            trackedPluginNames.put(file, pluginName);
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

    private void handleRemovedTrackedFile(File file) {
        if (file == null) {
            return;
        }

        mod.remove(file);
        las.remove(file);
        sig.remove(file);
        pendingFingerprints.remove(file);
        BileUtils.invalidateJarMeta(file);
        String trackedPluginName = trackedPluginNames.remove(file);

        getLogger().info("File removed: " + file.getName());
        if (trackedPluginName != null && !trackedPluginName.trim().isEmpty()) {
            getLogger().info("Unloading removed plugin: " + trackedPluginName);
            queuePluginUnload(trackedPluginName);
        }
    }

    private String fingerprint(File file) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }

        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }

            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException ignored) {
            return null;
        }
    }

    private String toHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }

        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[(i * 2) + 1] = HEX[value & 0x0F];
        }
        return new String(out);
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
                    null,
                    buildDirectorContexts(),
                    null,
                    null,
                    null
            );
            return director;
        }
    }

    public DirectorVisualCommand getHelpRoot() {
        DirectorVisualCommand local = helpRoot;
        if (local != null) {
            return local;
        }

        synchronized (this) {
            if (helpRoot != null) {
                return helpRoot;
            }

            helpRoot = DirectorVisualCommand.createRoot(getDirector());
            return helpRoot;
        }
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
            sender.sendMessage(tag + "You need " + ROOT_PERMISSION + " or OP.");
            return true;
        }

        if (BileFancyMenu.sendIfHelpRequested(sender, getHelpRoot(), args)) {
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
            sender.sendMessage(tag + "Unknown command \"" + String.join(" ", args) + "\".");
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
        sendCommandMessage(sender, tag + "Queued load for " + ChatColor.WHITE + pluginName + ChatColor.GRAY + ".");
        enqueuePluginOperation("cmd-load:" + pluginName, () -> {
            try {
                File pluginFile = BileUtils.getPluginFile(pluginName);
                if (pluginFile == null) {
                    sendCommandMessage(sender, tag + "Couldn't find \"" + pluginName + "\".");
                    return;
                }

                long startNs = System.nanoTime();
                executePluginLifecycle(pluginName, "load " + pluginName, () -> BileUtils.load(pluginFile));
                Plugin loaded = BileUtils.getPluginByName(pluginName);
                String resolvedName = loaded == null ? pluginName : loaded.getName();
                clearPluginDirty(resolvedName);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                sendCommandMessage(sender, tag + "Loaded " + ChatColor.WHITE + resolvedName + ChatColor.GRAY + " from "
                        + ChatColor.WHITE + pluginFile.getName() + ChatColor.GRAY + " (" + totalMs + "ms)");
            } catch (Throwable e) {
                sendCommandMessage(sender, tag + "Couldn't load \"" + pluginName + "\".");
                getLogger().log(Level.SEVERE, "Failed to load plugin " + pluginName, e);
            }
        });
    }

    public void unloadPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, tag + "Queued unload for " + ChatColor.WHITE + pluginName + ChatColor.GRAY + ".");
        enqueuePluginOperation("cmd-unload:" + pluginName, () -> {
            try {
                Plugin plugin = BileUtils.getPluginByName(pluginName);
                if (plugin == null) {
                    sendCommandMessage(sender, tag + "Couldn't find \"" + pluginName + "\".");
                    return;
                }

                String name = plugin.getName();
                File sourceFile = BileUtils.getPluginFile(plugin);
                executePluginLifecycle(name, "unload " + pluginName, () -> BileUtils.unload(plugin));
                clearPluginDirty(name);
                String fileName = sourceFile == null ? (pluginName + ".jar") : sourceFile.getName();
                sendCommandMessage(sender, tag + "Unloaded " + ChatColor.WHITE + name + ChatColor.GRAY + " (" + ChatColor.WHITE + fileName + ChatColor.GRAY + ")");
            } catch (Throwable e) {
                sendCommandMessage(sender, tag + "Couldn't unload \"" + pluginName + "\".");
                getLogger().log(Level.SEVERE, "Failed to unload plugin " + pluginName, e);
            }
        });
    }

    public void reloadPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, tag + "Queued reload for " + ChatColor.WHITE + pluginName + ChatColor.GRAY + ".");
        enqueuePluginOperation("cmd-reload:" + pluginName, () -> {
            try {
                Plugin plugin = BileUtils.getPluginByName(pluginName);
                if (plugin == null) {
                    sendCommandMessage(sender, tag + "Couldn't find \"" + pluginName + "\".");
                    return;
                }

                String name = plugin.getName();
                if (plugin == this) {
                    queueSelfReload("command");
                    sendCommandMessage(sender, tag + "Reloading " + ChatColor.WHITE + name + ChatColor.GRAY + ".");
                    return;
                }

                File sourceFile = BileUtils.getPluginFile(plugin);
                long startNs = System.nanoTime();
                executePluginLifecycle(name, "reload " + pluginName, () -> BileUtils.reload(plugin));
                clearPluginDirty(name);
                long totalMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
                String fileName = sourceFile == null ? (pluginName + ".jar") : sourceFile.getName();
                sendCommandMessage(sender, tag + "Reloaded " + ChatColor.WHITE + name + ChatColor.GRAY + " ("
                        + ChatColor.WHITE + fileName + ChatColor.GRAY + ", " + totalMs + "ms)");
            } catch (Throwable e) {
                markPluginDirty(pluginName, "manual reload failure: " + rootMessage(e));
                sendCommandMessage(sender, tag + "Couldn't reload \"" + pluginName + "\".");
                getLogger().log(Level.SEVERE, "Failed to reload plugin " + pluginName, e);
            }
        });
    }

    public void uninstallPlugin(CommandSender sender, String pluginName) {
        sendCommandMessage(sender, tag + "Queued uninstall for " + ChatColor.WHITE + pluginName + ChatColor.GRAY + ".");
        enqueuePluginOperation("cmd-uninstall:" + pluginName, () -> {
            try {
                File pluginFile = BileUtils.getPluginFile(pluginName);
                if (pluginFile == null) {
                    sendCommandMessage(sender, tag + "Couldn't find \"" + pluginName + "\".");
                    return;
                }

                String name = BileUtils.getPluginName(pluginFile);
                executePluginLifecycle(name, "uninstall " + pluginName, () -> BileUtils.delete(pluginFile));
                clearPluginDirty(name);

                sendCommandMessage(sender, tag + "Uninstalled " + ChatColor.WHITE + name + ChatColor.GRAY + " from " + ChatColor.WHITE + pluginFile.getName());
                if (pluginFile.exists()) {
                    sendCommandMessage(sender, tag + "But it looks like we can't delete it. You may need to delete " + ChatColor.RED + pluginFile.getName() + ChatColor.GRAY + " before installing it again.");
                }
            } catch (Throwable e) {
                sendCommandMessage(sender, tag + "Couldn't uninstall \"" + pluginName + "\".");
                getLogger().log(Level.SEVERE, "Failed to uninstall plugin " + pluginName, e);
            }
        });
    }

    public void installLibraryPlugin(CommandSender sender, String pluginName, String version) {
        File libraryPlugin = new File(new File(getDataFolder(), "library"), pluginName);
        if (!libraryPlugin.exists() || !libraryPlugin.isDirectory()) {
            sender.sendMessage(tag + "Couldn't find \"" + pluginName + "\" in library.");
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
            sender.sendMessage(tag + "Couldn't find version \"" + version + "\" for \"" + pluginName + "\".");
            return;
        }

        sendCommandMessage(sender, tag + "Queued library install for " + ChatColor.WHITE + pluginName + ChatColor.GRAY + ".");
        File selectedVersion = selected;
        enqueuePluginOperation("cmd-install:" + pluginName, () -> {
            try {
                File out = new File(BileUtils.getPluginsFolder(), libraryPlugin.getName() + "-" + selectedVersion.getName());
                BileUtils.copy(selectedVersion, out);
                executePluginLifecycle(pluginName, "install " + pluginName, () -> BileUtils.load(out));
                clearPluginDirty(pluginName);
                sendCommandMessage(sender, tag + "Installed " + ChatColor.WHITE + out.getName() + ChatColor.GRAY + " from library.");
            } catch (Throwable e) {
                sendCommandMessage(sender, tag + "Couldn't install \"" + pluginName + "\".");
                getLogger().log(Level.SEVERE, "Failed to install library plugin " + pluginName + "@" + version, e);
            }
        });
    }

    public void listLibrary(CommandSender sender) {
        File library = new File(getDataFolder(), "library");
        File[] plugins = library.listFiles();
        if (plugins == null || plugins.length == 0) {
            sender.sendMessage(tag + "Library is empty.");
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
                sender.sendMessage(tag + pluginDir.getName() + " " + ChatColor.GREEN + "(" + installedVersion + " installed) " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
            } else {
                sender.sendMessage(tag + pluginDir.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
            }
        }
    }

    public void listLibrary(CommandSender sender, String pluginName) {
        File pluginDir = new File(new File(getDataFolder(), "library"), pluginName);
        if (!pluginDir.exists() || !pluginDir.isDirectory()) {
            sender.sendMessage(tag + "Couldn't find " + pluginName + " in library.");
            return;
        }

        File latest = findLatestLibraryVersion(pluginDir);
        File[] versions = pluginDir.listFiles();
        if (versions != null) {
            for (File version : versions) {
                if (version != null && version.isFile() && version.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    sender.sendMessage(tag + version.getName().replace(".jar", ""));
                }
            }
        }

        if (latest != null) {
            sender.sendMessage(tag + pluginDir.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
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
                sender.sendMessage(message);
            }
        }
    }
}
