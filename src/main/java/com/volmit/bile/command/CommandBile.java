package com.volmit.bile.command;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.volmlib.util.director.DirectorParameterHandler;
import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.exceptions.DirectorParsingException;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.bile.BileTools;
import com.volmit.bile.BileUtils;
import com.volmit.bile.localization.BileLocalization;
import com.volmit.bile.localization.BileMessages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Director(name = "biletools", aliases = {"bile", "bi", "b", "vomit", "vom"}, description = "BileTools command root", descriptionKey = "command.root")
public class CommandBile {
    private final BileTools plugin;
    private BileDebugCommands debug;

    public CommandBile(BileTools plugin) {
        this.plugin = plugin;
        debug = new BileDebugCommands(plugin);
    }

    @Director(name = "config", sync = true, description = "Open the complete in-game configuration editor", descriptionKey = "command.config")
    public void config(@Param(name = "sender", contextual = true) CommandSender sender) {
        if (!sender.hasPermission("biletools.config")) {
            ComponentMessenger.send(sender, plugin.getLocalization().text(sender,
                    BileMessages.PERMISSION_DENIED,
                    MessageArgs.builder().untrusted("permission", "biletools.config").build()));
            return;
        }
        if (!(sender instanceof Player player)) {
            ComponentMessenger.send(sender, plugin.getLocalization().text(sender, BileMessages.PLAYER_ONLY));
            return;
        }
        plugin.configEditor().open(player);
        ComponentMessenger.send(player, plugin.getLocalization().text(player, BileMessages.CONFIG_OPENED));
    }

    @Director(name = "language", sync = true, description = "Choose your language or the server language", descriptionKey = "command.language")
    public void language(@Param(name = "sender", contextual = true) CommandSender sender) {
        plugin.languageSwitcher().open(sender);
    }

    @Director(name = "load", description = "Load a plugin jar from the plugins directory", descriptionKey = "command.load")
    public void load(
            @Param(name = "plugin", description = "Installed plugin name", descriptionKey = "parameter.installed_plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
    ) {
        plugin.loadPlugin(sender, pluginName);
    }

    @Director(name = "unload", description = "Unload an installed plugin", descriptionKey = "command.unload")
    public void unload(
            @Param(name = "plugin", description = "Installed plugin name", descriptionKey = "parameter.installed_plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
    ) {
        plugin.unloadPlugin(sender, pluginName);
    }

    @Director(name = "reload", description = "Reload an installed plugin", descriptionKey = "command.reload")
    public void reload(
            @Param(name = "plugin", description = "Installed plugin name", descriptionKey = "parameter.installed_plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
    ) {
        plugin.reloadPlugin(sender, pluginName);
    }

    @Director(name = "uninstall", description = "Delete a plugin jar from the plugins directory", descriptionKey = "command.uninstall")
    public void uninstall(
            @Param(name = "plugin", description = "Installed plugin name", descriptionKey = "parameter.installed_plugin", customHandler = InstalledPluginNameHandler.class) String pluginName,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
    ) {
        plugin.uninstallPlugin(sender, pluginName);
    }

    @Director(name = "install", description = "Install a plugin from the Bile library", descriptionKey = "command.install")
    public void install(
            @Param(name = "plugin", description = "Library plugin name", descriptionKey = "parameter.library_plugin", customHandler = LibraryPluginNameHandler.class) String pluginName,
            @Param(name = "version", description = "Library plugin version", descriptionKey = "parameter.version", defaultValue = "latest", customHandler = LibraryVersionHandler.class) String version,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
    ) {
        plugin.installLibraryPlugin(sender, pluginName, version);
    }

    @Director(name = "library", description = "List library plugins or versions for one plugin", descriptionKey = "command.library")
    public void library(
            @Param(name = "plugin", description = "Library plugin name", descriptionKey = "parameter.library_plugin", defaultValue = "*", customHandler = LibraryPluginNameHandler.class) String pluginName,
            @Param(name = "sender", description = "Command sender", descriptionKey = "parameter.sender", contextual = true) CommandSender sender
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
                throw new DirectorParsingException(localized(BileMessages.ERROR_PLUGIN_NAME_REQUIRED));
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
                throw new DirectorParsingException(localized(BileMessages.ERROR_LIBRARY_PLUGIN_NAME_REQUIRED));
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
                throw new DirectorParsingException(localized(BileMessages.ERROR_VERSION_REQUIRED));
            }

            return in.trim();
        }

        @Override
        public boolean supports(Class<?> type) {
            return type == String.class;
        }
    }

    private static String localized(TextKey key) {
        BileTools active = BileTools.bile;
        if (active == null || active.getLocalization() == null) {
            return BileLocalization.english(key).plain();
        }
        return active.getLocalization().text(key).plain();
    }
}
