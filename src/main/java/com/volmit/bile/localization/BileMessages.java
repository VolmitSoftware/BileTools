package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.Map;

public final class BileMessages {
    public static final TextKey DEBUG_DUMP_DESCRIPTION = TextKey.of("bile.command.debugdump", "Create and optionally upload a diagnostic report");
    public static final TextKey DEBUG_DUMP_UPLOAD = TextKey.of("bile.parameter.debugdump_upload", "Upload the report to mclo.gs");
    public static final TextKey COMMAND_ROOT = TextKey.of("bile.command.root", "BileTools command root");
    public static final TextKey COMMAND_LOAD = TextKey.of("bile.command.load", "Load a plugin jar from the plugins directory");
    public static final TextKey COMMAND_UNLOAD = TextKey.of("bile.command.unload", "Unload an installed plugin");
    public static final TextKey COMMAND_RELOAD = TextKey.of("bile.command.reload", "Reload an installed plugin");
    public static final TextKey COMMAND_UNINSTALL = TextKey.of("bile.command.uninstall", "Delete a plugin jar from the plugins directory");
    public static final TextKey COMMAND_INSTALL = TextKey.of("bile.command.install", "Install a plugin from the Bile library");
    public static final TextKey COMMAND_LIBRARY = TextKey.of("bile.command.library", "List library plugins or versions for one plugin");
    public static final TextKey PARAMETER_INSTALLED_PLUGIN = TextKey.of("bile.parameter.installed_plugin", "Installed plugin name");
    public static final TextKey PARAMETER_LIBRARY_PLUGIN = TextKey.of("bile.parameter.library_plugin", "Library plugin name");
    public static final TextKey PARAMETER_VERSION = TextKey.of("bile.parameter.version", "Library plugin version");
    public static final TextKey PARAMETER_SENDER = TextKey.of("bile.parameter.sender", "Command sender");
    public static final TextKey ERROR_PLUGIN_NAME_REQUIRED = TextKey.of("bile.error.plugin_name_required", "Plugin name cannot be empty");
    public static final TextKey ERROR_LIBRARY_PLUGIN_NAME_REQUIRED = TextKey.of("bile.error.library_plugin_name_required", "Library plugin name cannot be empty");
    public static final TextKey ERROR_VERSION_REQUIRED = TextKey.of("bile.error.version_required", "Version cannot be empty");
    public static final TextKey PERMISSION_DENIED = TextKey.of("bile.message.permission_denied", "&a[&8Bile&a]: &7You need &f{permission}&7 or OP.");
    public static final TextKey UNKNOWN_COMMAND = TextKey.of("bile.message.unknown_command", "&a[&8Bile&a]: &7Unknown command \"&f{command}&7\".");
    public static final TextKey REMOTE_RECEIVING = TextKey.of("bile.message.remote.receiving", "&a[&8Bile&a]: &7Receiving &f{file}&7 from &f{host}&7.");
    public static final TextKey HOT_DROP_SUCCESS = TextKey.of("bile.message.hot_drop.success", "&a[&8Bile&a]: &7Hot dropped &f{file}&7.");
    public static final TextKey HOT_DROP_FAILED = TextKey.of("bile.message.hot_drop.failed", "&a[&8Bile&a]: &7Failed to hot drop &c{file}&7.");
    public static final TextKey RESTART_REQUIRED = TextKey.of("bile.message.restart_required", "&a[&8Bile&a]: &e{plugin}&7 requires a full server restart.");
    public static final TextKey RELOAD_SUCCESS = TextKey.of("bile.message.reload.success", "&a[&8Bile&a]: &7Reloaded &f{plugin}&7 (&f{milliseconds}&7ms).");
    public static final TextKey RELOAD_FAILED = TextKey.of("bile.message.reload.failed", "&a[&8Bile&a]: &7Failed to reload &c{plugin}&7.");
    public static final TextKey UNLOAD_SUCCESS = TextKey.of("bile.message.unload.success", "&a[&8Bile&a]: &7Unloaded &f{plugin}&7 (&f{milliseconds}&7ms).");
    public static final TextKey UNLOAD_FAILED = TextKey.of("bile.message.unload.failed", "&a[&8Bile&a]: &7Failed to unload &c{plugin}&7.");
    public static final PluralKey REMOTE_DEPLOYED = PluralKey.of(
            "bile.message.remote.deployed",
            "count",
            Map.of(
                    "one", "&a[&8Bile&a]: &7Deployed &f{plugin}&7 to &f{count}&7 remote server.",
                    "other", "&a[&8Bile&a]: &7Deployed &f{plugin}&7 to &f{count}&7 remote servers."
            )
    );
    public static final TextKey DIRTY_PLUGIN_PAUSED = TextKey.of("bile.message.dirty_plugin_paused", "&a[&8Bile&a]: &c{plugin}&7 was marked dirty after a lifecycle failure. Automatic reloads are paused for it.");
    public static final TextKey RELOADING_CONTEXT = TextKey.of("bile.message.reloading_context", "&a[&8Bile&a]: &7Reloading &f{plugin}&7 (&f{context}&7).");
    public static final TextKey LOAD_QUEUED = TextKey.of("bile.message.load.queued", "&a[&8Bile&a]: &7Queued load for &f{plugin}&7.");
    public static final TextKey LOAD_SUCCESS = TextKey.of("bile.message.load.success", "&a[&8Bile&a]: &7Loaded &f{plugin}&7 from &f{file}&7 (&f{milliseconds}&7ms).");
    public static final TextKey LOAD_FAILED = TextKey.of("bile.message.load.failed", "&a[&8Bile&a]: &7Couldn't load \"&f{plugin}&7\".");
    public static final TextKey PLUGIN_NOT_FOUND = TextKey.of("bile.message.plugin_not_found", "&a[&8Bile&a]: &7Couldn't find \"&f{plugin}&7\".");
    public static final TextKey UNLOAD_QUEUED = TextKey.of("bile.message.unload.queued", "&a[&8Bile&a]: &7Queued unload for &f{plugin}&7.");
    public static final TextKey UNLOAD_COMMAND_SUCCESS = TextKey.of("bile.message.unload.command_success", "&a[&8Bile&a]: &7Unloaded &f{plugin}&7 (&f{file}&7).");
    public static final TextKey UNLOAD_COMMAND_FAILED = TextKey.of("bile.message.unload.command_failed", "&a[&8Bile&a]: &7Couldn't unload \"&f{plugin}&7\".");
    public static final TextKey RELOAD_QUEUED = TextKey.of("bile.message.reload.queued", "&a[&8Bile&a]: &7Queued reload for &f{plugin}&7.");
    public static final TextKey RELOADING = TextKey.of("bile.message.reloading", "&a[&8Bile&a]: &7Reloading &f{plugin}&7.");
    public static final TextKey RELOAD_COMMAND_SUCCESS = TextKey.of("bile.message.reload.command_success", "&a[&8Bile&a]: &7Reloaded &f{plugin}&7 (&f{file}&7, &f{milliseconds}&7ms).");
    public static final TextKey RELOAD_COMMAND_FAILED = TextKey.of("bile.message.reload.command_failed", "&a[&8Bile&a]: &7Couldn't reload \"&f{plugin}&7\".");
    public static final TextKey UNINSTALL_QUEUED = TextKey.of("bile.message.uninstall.queued", "&a[&8Bile&a]: &7Queued uninstall for &f{plugin}&7.");
    public static final TextKey UNINSTALL_SUCCESS = TextKey.of("bile.message.uninstall.success", "&a[&8Bile&a]: &7Uninstalled &f{plugin}&7 from &f{file}&7.");
    public static final TextKey UNINSTALL_DELETE_FAILED = TextKey.of("bile.message.uninstall.delete_failed", "&a[&8Bile&a]: &7The plugin was unloaded, but &c{file}&7 could not be deleted. Delete it before installing the plugin again.");
    public static final TextKey UNINSTALL_FAILED = TextKey.of("bile.message.uninstall.failed", "&a[&8Bile&a]: &7Couldn't uninstall \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_PLUGIN_NOT_FOUND = TextKey.of("bile.message.library.plugin_not_found", "&a[&8Bile&a]: &7Couldn't find \"&f{plugin}&7\" in the library.");
    public static final TextKey LIBRARY_VERSION_NOT_FOUND = TextKey.of("bile.message.library.version_not_found", "&a[&8Bile&a]: &7Couldn't find version \"&f{version}&7\" for \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_INSTALL_QUEUED = TextKey.of("bile.message.library.install_queued", "&a[&8Bile&a]: &7Queued library install for &f{plugin}&7.");
    public static final TextKey LIBRARY_INSTALL_SUCCESS = TextKey.of("bile.message.library.install_success", "&a[&8Bile&a]: &7Installed &f{file}&7 from the library.");
    public static final TextKey LIBRARY_INSTALL_RESTART_REQUIRED = TextKey.of("bile.message.library.install_restart_required", "&a[&8Bile&a]: &e{plugin}&7 was installed and requires a full server restart.");
    public static final TextKey LIBRARY_INSTALL_FAILED = TextKey.of("bile.message.library.install_failed", "&a[&8Bile&a]: &7Couldn't install \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_EMPTY = TextKey.of("bile.message.library.empty", "&a[&8Bile&a]: &7Library is empty.");
    public static final TextKey LIBRARY_INSTALLED_ENTRY = TextKey.of("bile.message.library.installed_entry", "&a[&8Bile&a]: &7{plugin} &a({installedVersion} installed) &f{latestVersion}&7 (latest)");
    public static final TextKey LIBRARY_ENTRY = TextKey.of("bile.message.library.entry", "&a[&8Bile&a]: &7{plugin} &f{latestVersion}&7 (latest)");
    public static final TextKey LIBRARY_VERSION_ENTRY = TextKey.of("bile.message.library.version_entry", "&a[&8Bile&a]: &7{version}");
    public static final TextKey LIBRARY_LATEST_ENTRY = TextKey.of("bile.message.library.latest_entry", "&a[&8Bile&a]: &7{plugin} &f{version}&7 (latest)");

    private static final MessageCatalog CATALOG = createCatalog();

    private BileMessages() {
    }

    public static MessageCatalog catalog() {
        return CATALOG;
    }

    private static MessageCatalog createCatalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder(VolmitLocales.ENGLISH);
        builder.addAll(DirectorMessages.keys());
        builder.add(DEBUG_DUMP_DESCRIPTION);
        builder.add(DEBUG_DUMP_UPLOAD);
        builder.add(COMMAND_ROOT);
        builder.add(COMMAND_LOAD);
        builder.add(COMMAND_UNLOAD);
        builder.add(COMMAND_RELOAD);
        builder.add(COMMAND_UNINSTALL);
        builder.add(COMMAND_INSTALL);
        builder.add(COMMAND_LIBRARY);
        builder.add(PARAMETER_INSTALLED_PLUGIN);
        builder.add(PARAMETER_LIBRARY_PLUGIN);
        builder.add(PARAMETER_VERSION);
        builder.add(PARAMETER_SENDER);
        builder.add(ERROR_PLUGIN_NAME_REQUIRED);
        builder.add(ERROR_LIBRARY_PLUGIN_NAME_REQUIRED);
        builder.add(ERROR_VERSION_REQUIRED);
        builder.add(PERMISSION_DENIED);
        builder.add(UNKNOWN_COMMAND);
        builder.add(REMOTE_RECEIVING);
        builder.add(HOT_DROP_SUCCESS);
        builder.add(HOT_DROP_FAILED);
        builder.add(RESTART_REQUIRED);
        builder.add(RELOAD_SUCCESS);
        builder.add(RELOAD_FAILED);
        builder.add(UNLOAD_SUCCESS);
        builder.add(UNLOAD_FAILED);
        builder.add(REMOTE_DEPLOYED);
        builder.add(DIRTY_PLUGIN_PAUSED);
        builder.add(RELOADING_CONTEXT);
        builder.add(LOAD_QUEUED);
        builder.add(LOAD_SUCCESS);
        builder.add(LOAD_FAILED);
        builder.add(PLUGIN_NOT_FOUND);
        builder.add(UNLOAD_QUEUED);
        builder.add(UNLOAD_COMMAND_SUCCESS);
        builder.add(UNLOAD_COMMAND_FAILED);
        builder.add(RELOAD_QUEUED);
        builder.add(RELOADING);
        builder.add(RELOAD_COMMAND_SUCCESS);
        builder.add(RELOAD_COMMAND_FAILED);
        builder.add(UNINSTALL_QUEUED);
        builder.add(UNINSTALL_SUCCESS);
        builder.add(UNINSTALL_DELETE_FAILED);
        builder.add(UNINSTALL_FAILED);
        builder.add(LIBRARY_PLUGIN_NOT_FOUND);
        builder.add(LIBRARY_VERSION_NOT_FOUND);
        builder.add(LIBRARY_INSTALL_QUEUED);
        builder.add(LIBRARY_INSTALL_SUCCESS);
        builder.add(LIBRARY_INSTALL_RESTART_REQUIRED);
        builder.add(LIBRARY_INSTALL_FAILED);
        builder.add(LIBRARY_EMPTY);
        builder.add(LIBRARY_INSTALLED_ENTRY);
        builder.add(LIBRARY_ENTRY);
        builder.add(LIBRARY_VERSION_ENTRY);
        builder.add(LIBRARY_LATEST_ENTRY);
        return builder.build();
    }
}
