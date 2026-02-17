package com.volmit.bile.command;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import com.volmit.bile.BileTools;
import com.volmit.bile.BileUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Director(name = "biletools", aliases = {"bile", "bi", "b", "volmit", "vomit", "vom"}, description = "BileTools command root")
public class CommandBile {
    private final BileTools plugin;

    public CommandBile(BileTools plugin) {
        this.plugin = plugin;
    }

    @Director(name = "load", description = "Load a plugin jar from plugins directory")
    public void load(
            @Param(name = "plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.loadPlugin(sender, pluginName);
    }

    @Director(name = "unload", description = "Unload an installed plugin")
    public void unload(
            @Param(name = "plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.unloadPlugin(sender, pluginName);
    }

    @Director(name = "reload", description = "Reload an installed plugin")
    public void reload(
            @Param(name = "plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.reloadPlugin(sender, pluginName);
    }

    @Director(name = "uninstall", description = "Delete plugin jar from plugins directory")
    public void uninstall(
            @Param(name = "plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.uninstallPlugin(sender, pluginName);
    }

    @Director(name = "install", description = "Install plugin from Bile library")
    public void install(
            @Param(name = "plugin", customHandler = LibraryPluginNameHandler.class) String pluginName,
            @Param(name = "version", defaultValue = "latest", customHandler = LibraryVersionHandler.class) String version,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        plugin.installLibraryPlugin(sender, pluginName, version);
    }

    @Director(name = "library", description = "List library plugins or versions for one plugin")
    public void library(
            @Param(name = "plugin", defaultValue = "*", customHandler = LibraryPluginNameHandler.class) String pluginName,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        if (pluginName == null || pluginName.trim().isEmpty() || "*".equals(pluginName.trim())) {
            plugin.listLibrary(sender);
            return;
        }

        plugin.listLibrary(sender, pluginName);
    }

    public static class InstalledPluginNameHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> names = new LinkedHashSet<>();

            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                names.add(plugin.getName());
            }

            File pluginFolder = BileUtils.getPluginsFolder();
            if (pluginFolder != null && pluginFolder.exists() && pluginFolder.isDirectory()) {
                File[] jars = pluginFolder.listFiles();
                if (jars != null) {
                    for (File jar : jars) {
                        if (jar != null && jar.isFile() && jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            try {
                                names.add(BileUtils.getPluginName(jar));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            return new KList<>(names);
        }

        @Override
        public String toString(String s) {
            return s == null ? "" : s;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.trim().isEmpty()) {
                throw new DirectorParsingException("Plugin name cannot be empty");
            }

            String value = in.trim();
            for (String candidate : getPossibilities()) {
                if (candidate.equalsIgnoreCase(value)) {
                    return candidate;
                }
            }

            return value;
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }

    public static class LibraryPluginNameHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> names = new LinkedHashSet<>();
            if (BileTools.bile == null) {
                return new KList<>(names);
            }

            File library = new File(BileTools.bile.getDataFolder(), "library");
            if (!library.exists() || !library.isDirectory()) {
                return new KList<>(names);
            }

            File[] entries = library.listFiles();
            if (entries == null) {
                return new KList<>(names);
            }

            for (File entry : entries) {
                if (entry != null && entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }

            return new KList<>(names);
        }

        @Override
        public String toString(String s) {
            return s == null ? "" : s;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.trim().isEmpty()) {
                throw new DirectorParsingException("Library plugin name cannot be empty");
            }

            String value = in.trim();
            for (String candidate : getPossibilities()) {
                if (candidate.equalsIgnoreCase(value)) {
                    return candidate;
                }
            }

            return value;
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }

    public static class LibraryVersionHandler implements DirectorParameterHandler<String> {
        @Override
        public KList<String> getPossibilities() {
            Set<String> versions = new LinkedHashSet<>();
            versions.add("latest");

            if (BileTools.bile == null) {
                return new KList<>(versions);
            }

            File library = new File(BileTools.bile.getDataFolder(), "library");
            if (!library.exists() || !library.isDirectory()) {
                return new KList<>(versions);
            }

            File[] plugins = library.listFiles();
            if (plugins == null) {
                return new KList<>(versions);
            }

            for (File pluginDir : plugins) {
                if (pluginDir == null || !pluginDir.isDirectory()) {
                    continue;
                }

                File[] jars = pluginDir.listFiles();
                if (jars == null) {
                    continue;
                }

                for (File jar : jars) {
                    if (jar != null && jar.isFile() && jar.getName().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        versions.add(jar.getName().replace(".jar", ""));
                    }
                }
            }

            return new KList<>(versions);
        }

        @Override
        public String toString(String s) {
            return s == null ? "" : s;
        }

        @Override
        public String parse(String in, boolean force) throws DirectorParsingException {
            if (in == null || in.trim().isEmpty()) {
                throw new DirectorParsingException("Version cannot be empty");
            }

            return in.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }
}
