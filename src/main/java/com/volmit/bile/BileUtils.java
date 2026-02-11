package com.volmit.bile;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.ZipFile;

public class BileUtils {
    public static void delete(Plugin p) throws IOException {
        File f = getPluginFile(p);
        if (BileTools.cfg.getBoolean("archive-plugins")) {
            backup(p);
        }
        unload(p);
        f.delete();
    }

    public static void delete(File f) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        if (getPlugin(f) != null) {
            delete(getPlugin(f));
            return;
        }

        if (BileTools.cfg.getBoolean("archive-plugins")) {
            PluginDescriptionFile fx = getPluginDescription(f);
            copy(f, new File(getBackupLocation(fx.getName()), fx.getVersion() + ".jar"));
        }
        f.delete();
    }

    public static void reload(Plugin p) throws IOException, UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, InvalidConfigurationException {
        File f = getPluginFile(p);
        if (BileTools.cfg.getBoolean("archive-plugins")) {
            backup(p);
        }
        Set<File> x = unload(p);

        for (File i : x) {
            load(i);
        }

        load(f);
    }

    public static void stp(String s) {
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY + s);
    }

    public static void load(File file) throws UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, IOException, InvalidConfigurationException {
        if (getPlugin(file) != null) {
            return;
        }

        stp("Loading " + getPluginName(file) + " " + getPluginVersion(file));
        PluginDescriptionFile f = getPluginDescription(file);

        for (String i : f.getDepend()) {
            if (Bukkit.getPluginManager().getPlugin(i) == null) {
                stp(getPluginName(file) + " depends on " + i);
                File fx = getPluginFile(i);

                if (fx != null) {
                    load(fx);
                } else {
                    return;
                }
            }
        }

        for (String i : f.getSoftDepend()) {
            if (Bukkit.getPluginManager().getPlugin(i) == null) {
                File fx = getPluginFile(i);

                if (fx != null) {
                    stp(getPluginName(file) + " soft depends on " + i);
                    load(fx);
                }
            }
        }

        Plugin target = Bukkit.getPluginManager().loadPlugin(file);
        target.onLoad();
        Bukkit.getPluginManager().enablePlugin(target);
    }

    @SuppressWarnings("unchecked")
    public static Set<File> unload(Plugin plugin) {
        File file = getPluginFile(plugin);
        stp("Unloading " + plugin.getName());
        Set<File> deps = new HashSet<>();

        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.equals(plugin)) {
                continue;
            }

            if (i.getDescription().getSoftDepend().contains(plugin.getName())) {
                stp(i.getName() + " soft depends on " + plugin.getName() + ". Playing it safe.");
                deps.add(getPluginFile(i));
            }

            if (i.getDescription().getDepend().contains(plugin.getName())) {
                stp(i.getName() + " depends on " + plugin.getName() + ". Playing it safe.");
                deps.add(getPluginFile(i));
            }
        }

        if (plugin.getName().equals("WorldEdit")) {
            Plugin fa = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");

            if (fa != null) {
                stp(fa.getName() + " (kind of) depends on " + plugin.getName() + ". Playing it safe.");
                deps.add(getPluginFile(fa));
            }
        }

        for (File i : new HashSet<>(deps)) {
            deps.addAll(unload(getPlugin(i)));
        }

        Bukkit.getScheduler().cancelTasks(plugin);
        HandlerList.unregisterAll(plugin);
        String name = plugin.getName();
        PluginManager pluginManager = Bukkit.getPluginManager();
        SimpleCommandMap commandMap = null;
        List<Plugin> plugins = null;
        Map<String, Plugin> names = null;
        Map<String, Command> commands = null;
        Map<Event, SortedSet<RegisteredListener>> listeners = null;
        boolean reloadlisteners = true;

        if (pluginManager != null) {
            pluginManager.disablePlugin(plugin);

            try {
                Field pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
                Field lookupNamesField = Bukkit.getPluginManager().getClass().getDeclaredField("lookupNames");
                pluginsField.setAccessible(true);
                plugins = (List<Plugin>) pluginsField.get(pluginManager);
                lookupNamesField.setAccessible(true);
                names = (Map<String, Plugin>) lookupNamesField.get(pluginManager);

                try {
                    Field listenersField = Bukkit.getPluginManager().getClass().getDeclaredField("listeners");
                    listenersField.setAccessible(true);
                    listeners = (Map<Event, SortedSet<RegisteredListener>>) listenersField.get(pluginManager);
                } catch (Exception e) {
                    reloadlisteners = false;
                }

                Field commandMapField = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                commandMapField.setAccessible(true);
                commandMap = (SimpleCommandMap) commandMapField.get(pluginManager);
                knownCommandsField.setAccessible(true);
                commands = (Map<String, Command>) knownCommandsField.get(commandMap);
            } catch (Throwable e) {
                e.printStackTrace();
                return new HashSet<>();
            }
        }

        pluginManager.disablePlugin(plugin);

        if (plugins != null) {
            plugins.remove(plugin);
        }

        if (names != null) {
            names.remove(name);
        }

        if (listeners != null && reloadlisteners) {
            for (SortedSet<RegisteredListener> set : listeners.values()) {
                set.removeIf(value -> value.getPlugin() == plugin);
            }
        }

        if (commandMap != null) {
            for (Iterator<Map.Entry<String, Command>> it = commands.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Command> entry = it.next();

                if (entry.getValue() instanceof PluginCommand) {
                    PluginCommand c = (PluginCommand) entry.getValue();

                    if (c.getPlugin() == plugin) {
                        c.unregister(commandMap);
                        it.remove();
                    }
                }
            }
        }

        ClassLoader cl = plugin.getClass().getClassLoader();

        if (cl instanceof java.io.Closeable) {
            try {
                ((java.io.Closeable) cl).close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        String idx = UUID.randomUUID().toString();
        File ff = new File(new File(BileTools.bile.getDataFolder(), "temp"), idx);
        System.gc();

        try {
            copy(file, ff);
            file.delete();
            copy(ff, file);
            BileTools.bile.reset(file);
            ff.deleteOnExit();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return deps;
    }

    public static File getBackupLocation(Plugin p) {
        return new File(new File(BileTools.bile.getDataFolder(), "library"), p.getName());
    }

    public static File getBackupLocation(String n) {
        return new File(new File(BileTools.bile.getDataFolder(), "library"), n);
    }

    public List<String> getBackedUpVersions(Plugin p) {
        List<String> s = new ArrayList<>();

        if (getBackupLocation(p).exists()) {
            for (File i : getBackupLocation(p).listFiles()) {
                s.add(i.getName().replace(".jar", ""));
            }
        }

        return s;
    }

    public static void backup(Plugin p) throws IOException {
        BileTools.bile.getLogger().info("Backed up " + p.getName() + " " + p.getDescription().getVersion());
        copy(getPluginFile(p), new File(getBackupLocation(p), p.getDescription().getVersion() + ".jar"));
    }

    public static void copy(File a, File b) throws IOException {
        b.getParentFile().mkdirs();
        Files.copy(a.toPath(), b.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static long hash(File file) throws NoSuchAlgorithmException {
        ByteBuffer buf = ByteBuffer.wrap(MessageDigest.getInstance("MD5").digest((file.lastModified() + "" + file.length()).getBytes()));
        return buf.getLong() + buf.getLong();
    }

    public static Plugin getPlugin(File file) {
        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            try {
                if (getPluginFile(i).equals(file)) {
                    return i;
                }
            } catch (Throwable ignored) {

            }
        }

        return null;
    }

    public static File getPluginFile(Plugin plugin) {
        for (File i : getPluginsFolder().listFiles()) {
            if (isPluginJar(i)) {
                try {
                    if (plugin.getName().equals(getPluginName(i))) {
                        return i;
                    }
                } catch (Throwable ignored) {

                }
            }
        }

        return null;
    }

    public static File getPluginFile(String name) {
        for (File i : getPluginsFolder().listFiles()) {
            if (isPluginJar(i) && i.isFile() && i.getName().equalsIgnoreCase(name)) {
                return i;
            }
        }

        for (File i : getPluginsFolder().listFiles()) {
            try {
                if (isPluginJar(i) && i.isFile() && getPluginName(i).equalsIgnoreCase(name)) {
                    return i;
                }
            } catch (Throwable ignored) {

            }
        }

        return null;
    }

    public static boolean isPluginJar(File f) {
        return f != null && f.exists() && f.isFile() && f.getName().toLowerCase().endsWith(".jar");
    }

    public static File getPluginsFolder() {
        return BileTools.bile.getDataFolder().getParentFile();
    }

    public static List<String> getDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getDepend();
    }

    public static List<String> getSoftDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getSoftDepend();
    }

    public static String getPluginVersion(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getVersion();
    }

    public static String getPluginName(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getName();
    }

    public static PluginDescriptionFile getPluginDescription(File file) throws IOException, InvalidDescriptionException {
        ZipFile z = new ZipFile(file);
        InputStream is = z.getInputStream(z.getEntry("plugin.yml"));
        PluginDescriptionFile f = new PluginDescriptionFile(is);
        z.close();

        return f;
    }

    public static Plugin getPluginByName(String string) {
        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().equalsIgnoreCase(string)) {
                return i;
            }
        }

        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().toLowerCase().contains(string.toLowerCase())) {
                return i;
            }
        }

        return null;
    }
}
