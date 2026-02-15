package com.volmit.bile;

import art.arcane.volmlib.util.director.compat.DirectorDecreeEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorExecutionResult;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand;
import com.volmit.bile.command.BileFancyMenu;
import com.volmit.bile.command.CommandBile;
import com.volmit.volume.cluster.DataCluster;
import com.volmit.volume.lang.collections.GList;
import com.volmit.volume.lang.collections.YAMLClusterPort;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final String ROOT_COMMAND = "biletools";
    private static final String ROOT_PERMISSION = "bile.use";
    private static final int HOT_DROP_RETRY_LIMIT = 18;
    private static final long HOT_DROP_INITIAL_DELAY_TICKS = 5L;
    private static final long HOT_DROP_RETRY_DELAY_TICKS = 10L;

    private SlaveBileServer srv;
    public static BileTools bile;
    private HashMap<File, Long> mod;
    private HashMap<File, Long> las;
    private File folder;
    private File backoff;
    public String tag;
    private Sound sx;
    private int cd = 10;
    private volatile DirectorRuntimeEngine director;
    private volatile DirectorVisualCommand helpRoot;
    public static DataCluster cfg;

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
        DataCluster cc = new DataCluster();
        cc.set("remote-deploy.slave.slave-enabled", false);
        cc.set("remote-deploy.slave.slave-port", 9876);
        cc.set("remote-deploy.slave.slave-payload", "pickapassword");
        cc.set("remote-deploy.master.master-enabled", false);
        cc.set("remote-deploy.master.master-deploy-to", new GList<String>().qadd("yourserver.com:9876:password"));
        cc.set("remote-deploy.master.master-deploy-signatures", new GList<String>().qadd("MyPlugin").qadd("AnotherPlugin"));
        cc.set("archive-plugins", true);
        cfg = cc;

        File f = new File(getDataFolder(), "config.yml");
        f.getParentFile().mkdirs();

        if (!f.exists()) {
            new YAMLClusterPort().fromCluster(cc).save(f);
        }

        FileConfiguration fc = new YamlConfiguration();
        fc.load(f);
        cfg = new YAMLClusterPort().toCluster(fc);
    }

    @Override
    public void onEnable() {
        try {
            readTheConfig();
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Unable to read the config...", e);
        }

        SplashScreen.print(this);

        if (cfg.getBoolean("remote-deploy.slave.slave-enabled")) {
            getLogger().info("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));

            try {
                srv = new SlaveBileServer();
                srv.start();
                getLogger().info("Remote Slave Server online!");
            } catch (Throwable e) {
                getLogger().warning("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));
                e.printStackTrace();
            }
        }

        cd = 10;
        bile = this;
        tag = ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY;
        mod = new HashMap<>();
        las = new HashMap<>();
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

        getServer().getScheduler().runTaskTimer(this, this::onTick, 10, 0);
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
        mod.put(f, f.length());
        las.put(f, f.lastModified());
    }

    private void scheduleHotDrop(File file) {
        scheduleHotDrop(file, HOT_DROP_RETRY_LIMIT, HOT_DROP_INITIAL_DELAY_TICKS);
    }

    private void scheduleHotDrop(File file, int attemptsRemaining, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(this, () -> attemptHotDrop(file, attemptsRemaining), delayTicks);
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

            getLogger().info("Hot dropping " + file.getName());
            BileUtils.load(file);
            getLogger().info("Hot dropped " + file.getName() + " successfully");

            for (Player k : Bukkit.getOnlinePlayers()) {
                if (k.hasPermission("bile.use")) {
                    k.sendMessage(tag + "Hot Dropped " + ChatColor.WHITE + file.getName());
                    k.playSound(k.getLocation(), sx, 1f, 1.9f);
                }
            }
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

        for (File i : folder.listFiles()) {
            if (i.getName().toLowerCase().endsWith(".jar") && i.isFile()) {
                if (!mod.containsKey(i)) {
                    getLogger().log(Level.INFO, "Now Tracking: " + i.getName());

                    if (!cfg.has("archive-plugins") || cfg.getBoolean("archive-plugins")) {
                        Bukkit.getScheduler().runTaskAsynchronously(bile, () -> {
                            Plugin pp = BileUtils.getPlugin(i);

                            if (pp != null) {
                                try {
                                    BileUtils.backup(pp);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }

                    mod.put(i, i.length());
                    las.put(i, i.lastModified());

                    if (cd == 0) {
                        getLogger().info("Scheduling hot drop for " + i.getName());
                        scheduleHotDrop(i);
                    }
                }

                if (mod.get(i) != i.length() || las.get(i) != i.lastModified()) {
                    mod.put(i, i.length());
                    las.put(i, i.lastModified());

                    for (Plugin j : Bukkit.getServer().getPluginManager().getPlugins()) {
                        if (BileUtils.getPluginFile(j).getName().equals(i.getName())) {
                            getLogger().log(Level.INFO, "File change detected: " + i.getName());
                            getLogger().log(Level.INFO, "Identified Plugin: " + j.getName() + " <-> " + i.getName());
                            getLogger().log(Level.INFO, "Reloading: " + j.getName());

                            try {
                                if (cfg.getBoolean("remote-deploy.master.master-enabled")) {
                                    if (cfg.getStringList("remote-deploy.master.master-deploy-signatures").contains(j.getName())) {
                                        Bukkit.getScheduler().runTaskAsynchronously(BileTools.bile, () -> {
                                            for (String g : cfg.getStringList("remote-deploy.master.master-deploy-to")) {
                                                try {
                                                    streamFile(i, g.split(":")[0], Integer.parseInt(g.split(":")[1]), g.split(":")[2]);
                                                } catch (NumberFormatException e) {
                                                    this.getLogger().warning("Invalid format");
                                                    e.printStackTrace();
                                                } catch (UnknownHostException e) {
                                                    this.getLogger().warning("Invalid host");
                                                    e.printStackTrace();
                                                } catch (IOException e) {
                                                    this.getLogger().warning("Invalid connection");
                                                    e.printStackTrace();
                                                }
                                            }

                                            for (Player k : Bukkit.getOnlinePlayers()) {
                                                if (k.hasPermission("bile.use")) {
                                                    k.sendMessage(tag + "Deployed " + ChatColor.WHITE + j.getName() + ChatColor.GRAY + " to " + cfg.getStringList("remote-deploy.master.master-deploy-to").size() + " remote server(s)");
                                                }
                                            }

                                            Bukkit.getScheduler().runTaskLater(BileTools.bile, () -> {
                                                try {
                                                    BileUtils.reload(j);

                                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                                        if (k.hasPermission("bile.use")) {
                                                            k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
                                                            k.playSound(k.getLocation(), sx, 1f, 1.9f);
                                                        }
                                                    }
                                                } catch (Throwable e) {
                                                    getLogger().log(Level.SEVERE, "Failed to reload " + j.getName() + " after remote deploy", e);

                                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                                        if (k.hasPermission("bile.use")) {
                                                            k.sendMessage(tag + "Failed to Reload " + ChatColor.RED + j.getName());
                                                        }
                                                    }
                                                }
                                            }, 5);
                                        });
                                    }
                                } else {
                                    BileUtils.reload(j);

                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                        if (k.hasPermission("bile.use")) {
                                            k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
                                            k.playSound(k.getLocation(), sx, 1f, 1.9f);
                                        }
                                    }
                                }
                            } catch (Throwable e) {
                                getLogger().log(Level.SEVERE, "Failed to reload " + j.getName(), e);

                                for (Player k : Bukkit.getOnlinePlayers()) {
                                    if (k.hasPermission("bile.use")) {
                                        k.sendMessage(tag + "Failed to Reload " + ChatColor.RED + j.getName());
                                    }
                                }
                            }

                            break;
                        }
                    }
                }
            }
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

            director = DirectorDecreeEngineFactory.create(
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
        try {
            File pluginFile = BileUtils.getPluginFile(pluginName);
            if (pluginFile == null) {
                sender.sendMessage(tag + "Couldn't find \"" + pluginName + "\".");
                return;
            }

            BileUtils.load(pluginFile);
            Plugin loaded = BileUtils.getPluginByName(pluginName);
            String resolvedName = loaded == null ? pluginName : loaded.getName();
            sender.sendMessage(tag + "Loaded " + ChatColor.WHITE + resolvedName + ChatColor.GRAY + " from " + ChatColor.WHITE + pluginFile.getName());
        } catch (Throwable e) {
            sender.sendMessage(tag + "Couldn't load \"" + pluginName + "\".");
            getLogger().log(Level.SEVERE, "Failed to load plugin " + pluginName, e);
        }
    }

    public void unloadPlugin(CommandSender sender, String pluginName) {
        try {
            Plugin plugin = BileUtils.getPluginByName(pluginName);
            if (plugin == null) {
                sender.sendMessage(tag + "Couldn't find \"" + pluginName + "\".");
                return;
            }

            String name = plugin.getName();
            BileUtils.unload(plugin);
            File file = BileUtils.getPluginFile(pluginName);
            String fileName = file == null ? (pluginName + ".jar") : file.getName();
            sender.sendMessage(tag + "Unloaded " + ChatColor.WHITE + name + ChatColor.GRAY + " (" + ChatColor.WHITE + fileName + ChatColor.GRAY + ")");
        } catch (Throwable e) {
            sender.sendMessage(tag + "Couldn't unload \"" + pluginName + "\".");
            getLogger().log(Level.SEVERE, "Failed to unload plugin " + pluginName, e);
        }
    }

    public void reloadPlugin(CommandSender sender, String pluginName) {
        try {
            Plugin plugin = BileUtils.getPluginByName(pluginName);
            if (plugin == null) {
                sender.sendMessage(tag + "Couldn't find \"" + pluginName + "\".");
                return;
            }

            String name = plugin.getName();
            BileUtils.reload(plugin);
            File file = BileUtils.getPluginFile(pluginName);
            String fileName = file == null ? (pluginName + ".jar") : file.getName();
            sender.sendMessage(tag + "Reloaded " + ChatColor.WHITE + name + ChatColor.GRAY + " (" + ChatColor.WHITE + fileName + ChatColor.GRAY + ")");
        } catch (Throwable e) {
            sender.sendMessage(tag + "Couldn't reload \"" + pluginName + "\".");
            getLogger().log(Level.SEVERE, "Failed to reload plugin " + pluginName, e);
        }
    }

    public void uninstallPlugin(CommandSender sender, String pluginName) {
        try {
            File pluginFile = BileUtils.getPluginFile(pluginName);
            if (pluginFile == null) {
                sender.sendMessage(tag + "Couldn't find \"" + pluginName + "\".");
                return;
            }

            String name = BileUtils.getPluginName(pluginFile);
            BileUtils.delete(pluginFile);

            sender.sendMessage(tag + "Uninstalled " + ChatColor.WHITE + name + ChatColor.GRAY + " from " + ChatColor.WHITE + pluginFile.getName());
            if (pluginFile.exists()) {
                sender.sendMessage(tag + "But it looks like we can't delete it. You may need to delete " + ChatColor.RED + pluginFile.getName() + ChatColor.GRAY + " before installing it again.");
            }
        } catch (Throwable e) {
            sender.sendMessage(tag + "Couldn't uninstall \"" + pluginName + "\".");
            getLogger().log(Level.SEVERE, "Failed to uninstall plugin " + pluginName, e);
        }
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

        try {
            File out = new File(BileUtils.getPluginsFolder(), libraryPlugin.getName() + "-" + selected.getName());
            BileUtils.copy(selected, out);
            BileUtils.load(out);
            sender.sendMessage(tag + "Installed " + ChatColor.WHITE + out.getName() + ChatColor.GRAY + " from library.");
        } catch (Throwable e) {
            sender.sendMessage(tag + "Couldn't install \"" + pluginName + "\".");
            getLogger().log(Level.SEVERE, "Failed to install library plugin " + pluginName + "@" + version, e);
        }
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
