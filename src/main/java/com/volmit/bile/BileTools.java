package com.volmit.bile;

import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
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
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String ROOT_COMMAND = "biletools";
    private static final String ROOT_PERMISSION = "bile.use";
    private static final int HOT_DROP_RETRY_LIMIT = 18;
    private static final long HOT_DROP_INITIAL_DELAY_TICKS = 5L;
    private static final long HOT_DROP_RETRY_DELAY_TICKS = 10L;
    private static final long PLUGIN_OPERATION_TIMEOUT_SECONDS = 120L;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SlaveBileServer srv;
    public static BileTools bile;
    private HashMap<File, Long> mod;
    private HashMap<File, Long> las;
    private HashMap<File, String> sig;
    private HashMap<File, String> trackedPluginNames;
    private File folder;
    private File backoff;
    public String tag;
    private Sound sx;
    private int cd = 10;
    private volatile boolean tickerActive;
    private volatile DirectorRuntimeEngine director;
    private volatile DirectorVisualCommand helpRoot;
    private final Set<String> queuedOperationKeys = ConcurrentHashMap.newKeySet();
    private final ExecutorService pluginOperationExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BileTools-PluginOps");
        thread.setDaemon(true);
        return thread;
    });
    public static BileConfig cfg;

    public static void streamFile(File f, String address, int port, String password) throws IOException {
        Socket s = new Socket(address, port);
        DataOutputStream dos = new DataOutputStream(s.getOutputStream());
        dos.writeUTF(password);
        dos.writeUTF(f.getName());

        FileInputStream fin = new FileInputStream(f);
        byte[] buffer = new byte[8192];
        int read;

        while ((read = fin.read(buffer)) != -1) {
            dos.write(buffer, 0, read);
        }

        fin.close();
        dos.flush();
        s.close();
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
        folder = getDataFolder().getParentFile();
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
        tickerActive = false;

        queuedOperationKeys.clear();
        pluginOperationExecutor.shutdownNow();

        FoliaScheduler.cancelTasks(this);
        try {
            Bukkit.getScheduler().cancelTasks(this);
        } catch (UnsupportedOperationException | IllegalPluginAccessException ignored) {
        }

        if (srv != null && srv.isAlive()) {
            srv.interrupt();

            try {
                srv.join();
                this.getLogger().info("Bile Slave Server shut down.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
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
                    executePluginLifecycle("hot drop " + file.getName(), () -> BileUtils.load(file));
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

        for (File i : files) {
            if (i.getName().toLowerCase().endsWith(".jar") && i.isFile()) {
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

                    trackFileState(i);
                    trackPluginName(i);

                    if (cd == 0) {
                        getLogger().info("Scheduling hot drop for " + i.getName());
                        scheduleHotDrop(i);
                    }
                }

                if (mod.get(i) != i.length() || las.get(i) != i.lastModified()) {
                    String previousSignature = sig.get(i);
                    trackFileState(i);
                    trackPluginName(i);
                    String currentSignature = sig.get(i);

                    if (previousSignature != null
                            && currentSignature != null
                            && previousSignature.equals(currentSignature)) {
                        continue;
                    }

                    for (Plugin j : Bukkit.getServer().getPluginManager().getPlugins()) {
                        File pluginFile = BileUtils.getPluginFile(j);
                        if (pluginFile != null && pluginFile.getName().equals(i.getName())) {
                            trackedPluginNames.put(i, j.getName());
                            getLogger().log(Level.INFO, "File change detected: " + i.getName());
                            getLogger().log(Level.INFO, "Identified Plugin: " + j.getName() + " <-> " + i.getName());
                            getLogger().log(Level.INFO, "Reloading: " + j.getName());

                            if (cfg.isRemoteMasterEnabled()
                                    && cfg.hasRemoteDeploySignature(j.getName())) {
                                queueRemoteDeployReload(i, j.getName());
                            } else {
                                queuePluginReload(j.getName());
                            }

                            break;
                        }
                    }
                }
            }
        }

        Set<File> removed = new LinkedHashSet<>(mod.keySet());
        for (File i : files) {
            if (i.getName().toLowerCase().endsWith(".jar") && i.isFile()) {
                removed.remove(i);
            }
        }

        for (File file : removed) {
            handleRemovedTrackedFile(file);
        }
    }

    private void queuePluginReload(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
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
                getLogger().info("Detected update for " + targetPlugin.getName() + ", skipping automatic self-reload.");
                return;
            }

            try {
                executePluginLifecycle("reload " + pluginName, () -> BileUtils.reload(targetPlugin));
                notifyBileUsers(tag + "Reloaded " + ChatColor.WHITE + pluginName, true);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "Failed to reload " + pluginName, e);
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
            }
        });
    }

    private void queuePluginUnload(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
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

            try {
                executePluginLifecycle("unload " + pluginName, () -> BileUtils.unload(targetPlugin));
                notifyBileUsers(tag + "Unloaded " + ChatColor.WHITE + pluginName, false);
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

        String key = "remote-reload:" + pluginName.toLowerCase(Locale.ROOT);
        enqueuePluginOperation(key, () -> {
            for (String target : cfg.getRemoteMasterDeployTargets()) {
                String[] split = target.split(":", 3);
                if (split.length < 3) {
                    getLogger().warning("Invalid remote deploy target format: " + target);
                    continue;
                }

                try {
                    streamFile(sourceFile, split[0], Integer.parseInt(split[1]), split[2]);
                } catch (NumberFormatException e) {
                    getLogger().warning("Invalid port in remote deploy target: " + target);
                } catch (UnknownHostException e) {
                    getLogger().warning("Invalid host in remote deploy target: " + target);
                } catch (IOException e) {
                    getLogger().warning("Failed remote deploy to " + target + ": " + e.getMessage());
                }
            }

            notifyBileUsers(
                    tag + "Deployed " + ChatColor.WHITE + pluginName + ChatColor.GRAY + " to "
                            + cfg.getRemoteMasterDeployTargets().size() + " remote server(s)",
                    false
            );

            Plugin targetPlugin = BileUtils.getPluginByName(pluginName);
            if (targetPlugin == null) {
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
                return;
            }

            if (targetPlugin == this) {
                getLogger().info("Detected update for " + targetPlugin.getName() + ", skipping automatic self-reload.");
                return;
            }

            try {
                executePluginLifecycle("reload " + pluginName + " after remote deploy", () -> BileUtils.reload(targetPlugin));
                notifyBileUsers(tag + "Reloaded " + ChatColor.WHITE + pluginName, true);
            } catch (Throwable e) {
                getLogger().log(Level.SEVERE, "Failed to reload " + pluginName + " after remote deploy", e);
                notifyBileUsers(tag + "Failed to Reload " + ChatColor.RED + pluginName, false);
            }
        });
    }

    private void notifyBileUsers(String message, boolean playSound) {
        runGlobal(() -> {
            for (Player k : Bukkit.getOnlinePlayers()) {
                if (k.hasPermission(ROOT_PERMISSION)) {
                    k.sendMessage(message);
                    if (playSound) {
                        k.playSound(k.getLocation(), sx, 1f, 1.9f);
                    }
                }
            }
        });
    }

    private void sendCommandMessage(CommandSender sender, String message) {
        if (sender == null || message == null) {
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

    private void executePluginLifecycle(String operationName, ThrowingRunnable operation) throws Throwable {
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
            operation.run();
            return;
        }

        try {
            completion.get(PLUGIN_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for plugin operation: " + operationName, e);
        } catch (TimeoutException e) {
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
            scheduleTicker(1L);
        }, safeDelay)) {
            tickerActive = false;
            getLogger().warning("Failed to schedule BileTools ticker task.");
        }
    }

    private boolean runGlobal(Runnable runnable) {
        if (runnable == null || !isEnabled()) {
            return false;
        }

        Runnable guarded = () -> {
            if (isEnabled()) {
                runnable.run();
            }
        };

        if (FoliaScheduler.runGlobal(this, guarded)) {
            return true;
        }

        try {
            Bukkit.getScheduler().runTask(this, guarded);
            return true;
        } catch (IllegalPluginAccessException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    private boolean runGlobalLater(Runnable runnable, long delayTicks) {
        if (delayTicks <= 0) {
            return runGlobal(runnable);
        }

        if (runnable == null || !isEnabled()) {
            return false;
        }

        Runnable guarded = () -> {
            if (isEnabled()) {
                runnable.run();
            }
        };

        long safeDelay = Math.max(1L, delayTicks);
        if (FoliaScheduler.runGlobal(this, guarded, safeDelay)) {
            return true;
        }

        try {
            Bukkit.getScheduler().runTaskLater(this, guarded, safeDelay);
            return true;
        } catch (IllegalPluginAccessException | UnsupportedOperationException ignored) {
            return false;
        }
    }

    private boolean runAsync(Runnable runnable) {
        if (runnable == null || !isEnabled()) {
            return false;
        }

        Runnable guarded = () -> {
            if (isEnabled()) {
                runnable.run();
            }
        };

        if (FoliaScheduler.runAsync(this, guarded)) {
            return true;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(this, guarded);
            return true;
        } catch (IllegalPluginAccessException | UnsupportedOperationException ignored) {
            return false;
        }
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
        if (file == null) {
            return;
        }

        mod.put(file, file.length());
        las.put(file, file.lastModified());
        sig.put(file, fingerprint(file));
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

                executePluginLifecycle("load " + pluginName, () -> BileUtils.load(pluginFile));
                Plugin loaded = BileUtils.getPluginByName(pluginName);
                String resolvedName = loaded == null ? pluginName : loaded.getName();
                sendCommandMessage(sender, tag + "Loaded " + ChatColor.WHITE + resolvedName + ChatColor.GRAY + " from " + ChatColor.WHITE + pluginFile.getName());
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
                executePluginLifecycle("unload " + pluginName, () -> BileUtils.unload(plugin));
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
                File sourceFile = BileUtils.getPluginFile(plugin);
                executePluginLifecycle("reload " + pluginName, () -> BileUtils.reload(plugin));
                String fileName = sourceFile == null ? (pluginName + ".jar") : sourceFile.getName();
                sendCommandMessage(sender, tag + "Reloaded " + ChatColor.WHITE + name + ChatColor.GRAY + " (" + ChatColor.WHITE + fileName + ChatColor.GRAY + ")");
            } catch (Throwable e) {
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
                executePluginLifecycle("uninstall " + pluginName, () -> BileUtils.delete(pluginFile));

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
                executePluginLifecycle("install " + pluginName, () -> BileUtils.load(out));
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
