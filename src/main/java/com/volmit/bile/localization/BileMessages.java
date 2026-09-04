package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.DirectorMessages;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;

import java.util.List;
import java.util.Map;

public final class BileMessages {
    public static final TextKey DEBUG_DESCRIPTION = TextKey.of("command.debug", "BileTools diagnostic tools");
    public static final TextKey DEBUG_DUMP_DESCRIPTION = TextKey.of("command.debug_dump", "Create a comprehensive BileTools diagnostic report");
    public static final TextKey DEBUG_DUMP_UPLOAD = TextKey.of("parameter.debug_upload", "Upload the report to mclo.gs");
    public static final TextKey COMMAND_ROOT = TextKey.of("command.root", "BileTools command root");
    public static final TextKey COMMAND_CONFIG = TextKey.of("command.config", "Open the complete in-game configuration editor");
    public static final TextKey COMMAND_LANGUAGE = TextKey.of("command.language", "Choose your language or the server language");
    public static final TextKey COMMAND_LOAD = TextKey.of("command.load", "Load a plugin jar from the plugins directory");
    public static final TextKey COMMAND_UNLOAD = TextKey.of("command.unload", "Unload an installed plugin");
    public static final TextKey COMMAND_RELOAD = TextKey.of("command.reload", "Reload an installed plugin");
    public static final TextKey COMMAND_UNINSTALL = TextKey.of("command.uninstall", "Delete a plugin jar from the plugins directory");
    public static final TextKey COMMAND_INSTALL = TextKey.of("command.install", "Install a plugin from the Bile library");
    public static final TextKey COMMAND_LIBRARY = TextKey.of("command.library", "List library plugins or versions for one plugin");
    public static final TextKey PARAMETER_INSTALLED_PLUGIN = TextKey.of("parameter.installed_plugin", "Installed plugin name");
    public static final TextKey PARAMETER_LIBRARY_PLUGIN = TextKey.of("parameter.library_plugin", "Library plugin name");
    public static final TextKey PARAMETER_VERSION = TextKey.of("parameter.version", "Library plugin version");
    public static final TextKey PARAMETER_SENDER = TextKey.of("parameter.sender", "Command sender");
    public static final TextKey ERROR_PLUGIN_NAME_REQUIRED = TextKey.of("error.plugin_name_required", "Plugin name cannot be empty");
    public static final TextKey ERROR_LIBRARY_PLUGIN_NAME_REQUIRED = TextKey.of("error.library_plugin_name_required", "Library plugin name cannot be empty");
    public static final TextKey ERROR_VERSION_REQUIRED = TextKey.of("error.version_required", "Version cannot be empty");
    public static final TextKey PERMISSION_DENIED = TextKey.of("message.permission_denied", "&a[&8Bile&a]: &7You need &f{permission}&7 or OP.");
    public static final TextKey UNKNOWN_COMMAND = TextKey.of("message.unknown_command", "&a[&8Bile&a]: &7Unknown command \"&f{command}&7\".");
    public static final TextKey REMOTE_RECEIVING = TextKey.of("message.remote.receiving", "&a[&8Bile&a]: &7Receiving &f{file}&7 from &f{host}&7.");
    public static final TextKey HOT_DROP_SUCCESS = TextKey.of("message.hot_drop.success", "&a[&8Bile&a]: &7Hot dropped &f{file}&7.");
    public static final TextKey HOT_DROP_FAILED = TextKey.of("message.hot_drop.failed", "&a[&8Bile&a]: &7Failed to hot drop &c{file}&7.");
    public static final TextKey RESTART_REQUIRED = TextKey.of("message.restart_required", "&a[&8Bile&a]: &e{plugin}&7 requires a full server restart.");
    public static final TextKey RELOAD_SUCCESS = TextKey.of("message.reload.success", "&a[&8Bile&a]: &7Reloaded &f{plugin}&7 (&f{milliseconds}&7ms).");
    public static final TextKey RELOAD_FAILED = TextKey.of("message.reload.failed", "&a[&8Bile&a]: &7Failed to reload &c{plugin}&7.");
    public static final TextKey UNLOAD_SUCCESS = TextKey.of("message.unload.success", "&a[&8Bile&a]: &7Unloaded &f{plugin}&7 (&f{milliseconds}&7ms).");
    public static final TextKey UNLOAD_FAILED = TextKey.of("message.unload.failed", "&a[&8Bile&a]: &7Failed to unload &c{plugin}&7.");
    public static final PluralKey REMOTE_DEPLOYED = PluralKey.of(
            "message.remote.deployed",
            "count",
            Map.of(
                    "one", "&a[&8Bile&a]: &7Deployed &f{plugin}&7 to &f{count}&7 remote server.",
                    "other", "&a[&8Bile&a]: &7Deployed &f{plugin}&7 to &f{count}&7 remote servers."
            )
    );
    public static final TextKey DIRTY_PLUGIN_PAUSED = TextKey.of("message.dirty_plugin_paused", "&a[&8Bile&a]: &c{plugin}&7 was marked dirty after a lifecycle failure. Automatic reloads are paused for it.");
    public static final TextKey RELOADING_CONTEXT = TextKey.of("message.reloading_context", "&a[&8Bile&a]: &7Reloading &f{plugin}&7 (&f{context}&7).");
    public static final TextKey LOAD_QUEUED = TextKey.of("message.load.queued", "&a[&8Bile&a]: &7Queued load for &f{plugin}&7.");
    public static final TextKey LOAD_SUCCESS = TextKey.of("message.load.success", "&a[&8Bile&a]: &7Loaded &f{plugin}&7 from &f{file}&7 (&f{milliseconds}&7ms).");
    public static final TextKey LOAD_FAILED = TextKey.of("message.load.failed", "&a[&8Bile&a]: &7Couldn't load \"&f{plugin}&7\".");
    public static final TextKey PLUGIN_NOT_FOUND = TextKey.of("message.plugin_not_found", "&a[&8Bile&a]: &7Couldn't find \"&f{plugin}&7\".");
    public static final TextKey UNLOAD_QUEUED = TextKey.of("message.unload.queued", "&a[&8Bile&a]: &7Queued unload for &f{plugin}&7.");
    public static final TextKey UNLOAD_COMMAND_SUCCESS = TextKey.of("message.unload.command_success", "&a[&8Bile&a]: &7Unloaded &f{plugin}&7 (&f{file}&7).");
    public static final TextKey UNLOAD_COMMAND_FAILED = TextKey.of("message.unload.command_failed", "&a[&8Bile&a]: &7Couldn't unload \"&f{plugin}&7\".");
    public static final TextKey RELOAD_QUEUED = TextKey.of("message.reload.queued", "&a[&8Bile&a]: &7Queued reload for &f{plugin}&7.");
    public static final TextKey RELOADING = TextKey.of("message.reloading", "&a[&8Bile&a]: &7Reloading &f{plugin}&7.");
    public static final TextKey RELOAD_COMMAND_SUCCESS = TextKey.of("message.reload.command_success", "&a[&8Bile&a]: &7Reloaded &f{plugin}&7 (&f{file}&7, &f{milliseconds}&7ms).");
    public static final TextKey RELOAD_COMMAND_FAILED = TextKey.of("message.reload.command_failed", "&a[&8Bile&a]: &7Couldn't reload \"&f{plugin}&7\".");
    public static final TextKey UNINSTALL_QUEUED = TextKey.of("message.uninstall.queued", "&a[&8Bile&a]: &7Queued uninstall for &f{plugin}&7.");
    public static final TextKey UNINSTALL_SUCCESS = TextKey.of("message.uninstall.success", "&a[&8Bile&a]: &7Uninstalled &f{plugin}&7 from &f{file}&7.");
    public static final TextKey UNINSTALL_DELETE_FAILED = TextKey.of("message.uninstall.delete_failed", "&a[&8Bile&a]: &7The plugin was unloaded, but &c{file}&7 could not be deleted. Delete it before installing the plugin again.");
    public static final TextKey UNINSTALL_FAILED = TextKey.of("message.uninstall.failed", "&a[&8Bile&a]: &7Couldn't uninstall \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_PLUGIN_NOT_FOUND = TextKey.of("message.library.plugin_not_found", "&a[&8Bile&a]: &7Couldn't find \"&f{plugin}&7\" in the library.");
    public static final TextKey LIBRARY_VERSION_NOT_FOUND = TextKey.of("message.library.version_not_found", "&a[&8Bile&a]: &7Couldn't find version \"&f{version}&7\" for \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_INSTALL_QUEUED = TextKey.of("message.library.install_queued", "&a[&8Bile&a]: &7Queued library install for &f{plugin}&7.");
    public static final TextKey LIBRARY_INSTALL_SUCCESS = TextKey.of("message.library.install_success", "&a[&8Bile&a]: &7Installed &f{file}&7 from the library.");
    public static final TextKey LIBRARY_INSTALL_RESTART_REQUIRED = TextKey.of("message.library.install_restart_required", "&a[&8Bile&a]: &e{plugin}&7 was installed and requires a full server restart.");
    public static final TextKey LIBRARY_INSTALL_FAILED = TextKey.of("message.library.install_failed", "&a[&8Bile&a]: &7Couldn't install \"&f{plugin}&7\".");
    public static final TextKey LIBRARY_EMPTY = TextKey.of("message.library.empty", "&a[&8Bile&a]: &7Library is empty.");
    public static final TextKey LIBRARY_INSTALLED_ENTRY = TextKey.of("message.library.installed_entry", "&a[&8Bile&a]: &7{plugin} &a({installedVersion} installed) &f{latestVersion}&7 (latest)");
    public static final TextKey LIBRARY_ENTRY = TextKey.of("message.library.entry", "&a[&8Bile&a]: &7{plugin} &f{latestVersion}&7 (latest)");
    public static final TextKey LIBRARY_VERSION_ENTRY = TextKey.of("message.library.version_entry", "&a[&8Bile&a]: &7{version}");
    public static final TextKey LIBRARY_LATEST_ENTRY = TextKey.of("message.library.latest_entry", "&a[&8Bile&a]: &7{plugin} &f{version}&7 (latest)");
    public static final TextKey PLAYER_ONLY = TextKey.of("message.player_only", "&a[&8Bile&a]: &cThis command can only be used by a player.");
    public static final TextKey CONFIG_OPENED = TextKey.of("message.config.opened", "&a[&8Bile&a]: &7Opened the complete in-game configuration editor.");
    public static final TextKey CONFIG_SAVE_FAILED = TextKey.of("message.config.save_failed", "&a[&8Bile&a]: &cCould not apply &f{setting}&c: {reason}");
    public static final TextKey CHANGE_SAVED = TextKey.of("message.change.saved", "&a[&8Bile&a]: &f{setting}&7 changed to &f{new}&7 from &f{old}&7.");
    public static final TextKey GUI_ROOT_TITLE = TextKey.of("gui.title.root", "&2BileTools Configuration");
    public static final TextKey GUI_CATEGORY_TITLE = TextKey.of("gui.title.category", "&2BileTools &8› {category}");
    public static final TextKey GUI_CATEGORY_GENERAL = TextKey.of("gui.category.general", "&aGeneral");
    public static final TextKey GUI_CATEGORY_METRICS = TextKey.of("gui.category.metrics", "&aMetrics");
    public static final TextKey GUI_CATEGORY_REMOTE_SLAVE = TextKey.of("gui.category.remote_slave", "&aRemote Receiver");
    public static final TextKey GUI_CATEGORY_REMOTE_MASTER = TextKey.of("gui.category.remote_master", "&aRemote Deployment");
    public static final TextKey GUI_CATEGORY_NETWORK = TextKey.of("gui.category.network", "&aNetwork Limits");
    public static final TextKey GUI_CATEGORY_WATCHER = TextKey.of("gui.category.watcher", "&aPlugin Watcher");
    public static final TextKey GUI_CATEGORY_LIFECYCLE = TextKey.of("gui.category.lifecycle", "&aLifecycle");
    public static final TextKey GUI_CATEGORY_LANGUAGES = TextKey.of("gui.category.languages", "&aLanguages");
    public static final TextKey GUI_BACK = TextKey.of("gui.navigation.back", "&eBack");
    public static final TextKey GUI_CLOSE = TextKey.of("gui.navigation.close", "&cClose");
    public static final TextKey GUI_STATE = TextKey.of("gui.lore.state", "&7Current: {value}");
    public static final TextKey GUI_TOGGLE = TextKey.of("gui.lore.toggle", "&8Click to toggle.");
    public static final TextKey GUI_NUMBER = TextKey.of("gui.lore.number", "&8Left +1, right -1, shift ×10, drop to type.");
    public static final TextKey GUI_TEXT = TextKey.of("gui.lore.text", "&8Click to type a new value in chat.");
    public static final TextKey GUI_LANGUAGE_SELECT = TextKey.of("gui.lore.language", "&8Click to choose an available language or type a locale.");
    public static final TextKey GUI_CATEGORY_OPEN = TextKey.of("gui.lore.category", "&8Click to edit every setting in this category.");
    public static final TextKey GUI_FILE_ONLY = TextKey.of("gui.lore.file_only", "&8Sensitive value; edit biletools.yml, then reload BileTools.");
    public static final TextKey GUI_PROMPT = TextKey.of("gui.prompt.request", "&a[&8Bile&a]: &eType a new value for &f{setting}&e in chat.");
    public static final TextKey GUI_PROMPT_LIST = TextKey.of("gui.prompt.list", "&a[&8Bile&a]: &7Separate list entries with commas. Type &fnone&7 for an empty list.");
    public static final TextKey GUI_PROMPT_CANCEL = TextKey.of("gui.prompt.cancel", "&a[&8Bile&a]: &7Type &fcancel&7 to stop. This prompt expires in 60 seconds.");
    public static final TextKey GUI_PROMPT_CANCELLED = TextKey.of("gui.prompt.cancelled", "&a[&8Bile&a]: &eConfiguration edit cancelled.");
    public static final TextKey GUI_PROMPT_TIMEOUT = TextKey.of("gui.prompt.timeout", "&a[&8Bile&a]: &eConfiguration edit expired without changing anything.");
    public static final TextKey SETTING_LANGUAGE = setting("general.language", "Active language locale");
    public static final TextKey SETTING_ARCHIVE = setting("general.archive_plugins", "Archive plugin jars before replacement");
    public static final TextKey SETTING_METRICS = setting("metrics.enabled", "Anonymous bStats metrics");
    public static final TextKey SETTING_SLAVE_ENABLED = setting("remote_slave.enabled", "Remote receiver");
    public static final TextKey SETTING_SLAVE_PORT = setting("remote_slave.port", "Remote receiver port");
    public static final TextKey SETTING_SLAVE_PAYLOAD = setting("remote_slave.payload", "Remote receiver secret");
    public static final TextKey SETTING_MASTER_ENABLED = setting("remote_master.enabled", "Remote deployment");
    public static final TextKey SETTING_MASTER_TARGETS = setting("remote_master.targets", "Remote deployment targets");
    public static final TextKey SETTING_MASTER_SIGNATURES = setting("remote_master.signatures", "Remote deployment plugin names");
    public static final TextKey SETTING_SOCKET_TIMEOUT = setting("network.socket_timeout", "Remote socket timeout milliseconds");
    public static final TextKey SETTING_MAX_TRANSFER = setting("network.max_transfer", "Maximum transfer bytes");
    public static final TextKey SETTING_WATCHER_IDLE = setting("watcher.idle", "Idle poll ticks");
    public static final TextKey SETTING_WATCHER_ACTIVE = setting("watcher.active", "Active poll ticks");
    public static final TextKey SETTING_WATCHER_DEBOUNCE = setting("watcher.debounce", "Fingerprint debounce ticks");
    public static final TextKey SETTING_WATCHER_IGNORE = setting("watcher.ignore", "Ignored plugin names");
    public static final TextKey SETTING_WATCHER_ONLY = setting("watcher.only", "Allowed plugin names");
    public static final TextKey SETTING_LOG_TIMINGS = setting("lifecycle.log_timings", "Lifecycle timing logs");
    public static final TextKey SETTING_HEALTH_CHECK = setting("lifecycle.health_check", "Post-load health checks");

    private static final MessageCatalog CATALOG = createCatalog();

    private BileMessages() {
    }

    public static MessageCatalog catalog() {
        return CATALOG;
    }

    private static MessageCatalog createCatalog() {
        MessageCatalog.Builder builder = MessageCatalog.builder(VolmitLocales.ENGLISH);
        builder.addAll(List.of(
                COMMAND_ROOT, COMMAND_CONFIG, COMMAND_LANGUAGE, COMMAND_LOAD, COMMAND_UNLOAD, COMMAND_RELOAD,
                COMMAND_UNINSTALL, COMMAND_INSTALL, COMMAND_LIBRARY, DEBUG_DESCRIPTION, DEBUG_DUMP_DESCRIPTION));
        builder.addAll(List.of(
                PARAMETER_INSTALLED_PLUGIN, PARAMETER_LIBRARY_PLUGIN, PARAMETER_VERSION, PARAMETER_SENDER,
                DEBUG_DUMP_UPLOAD));
        builder.addAll(List.of(
                ERROR_PLUGIN_NAME_REQUIRED, ERROR_LIBRARY_PLUGIN_NAME_REQUIRED, ERROR_VERSION_REQUIRED));
        builder.addAll(List.of(
                PERMISSION_DENIED, UNKNOWN_COMMAND, REMOTE_RECEIVING, HOT_DROP_SUCCESS, HOT_DROP_FAILED,
                RESTART_REQUIRED, RELOAD_SUCCESS, RELOAD_FAILED, UNLOAD_SUCCESS, UNLOAD_FAILED));
        builder.add(REMOTE_DEPLOYED);
        builder.addAll(List.of(
                DIRTY_PLUGIN_PAUSED, RELOADING_CONTEXT, LOAD_QUEUED, LOAD_SUCCESS, LOAD_FAILED, PLUGIN_NOT_FOUND,
                UNLOAD_QUEUED, UNLOAD_COMMAND_SUCCESS, UNLOAD_COMMAND_FAILED, RELOAD_QUEUED, RELOADING,
                RELOAD_COMMAND_SUCCESS, RELOAD_COMMAND_FAILED, UNINSTALL_QUEUED, UNINSTALL_SUCCESS,
                UNINSTALL_DELETE_FAILED, UNINSTALL_FAILED, LIBRARY_PLUGIN_NOT_FOUND, LIBRARY_VERSION_NOT_FOUND,
                 LIBRARY_INSTALL_QUEUED, LIBRARY_INSTALL_SUCCESS, LIBRARY_INSTALL_RESTART_REQUIRED,
                 LIBRARY_INSTALL_FAILED, LIBRARY_EMPTY, LIBRARY_INSTALLED_ENTRY, LIBRARY_ENTRY,
                 LIBRARY_VERSION_ENTRY, LIBRARY_LATEST_ENTRY, PLAYER_ONLY, CONFIG_OPENED,
                 CONFIG_SAVE_FAILED, CHANGE_SAVED));
        builder.addAll(List.of(
                GUI_ROOT_TITLE, GUI_CATEGORY_TITLE, GUI_CATEGORY_GENERAL, GUI_CATEGORY_METRICS,
                GUI_CATEGORY_REMOTE_SLAVE, GUI_CATEGORY_REMOTE_MASTER, GUI_CATEGORY_NETWORK, GUI_CATEGORY_WATCHER,
                GUI_CATEGORY_LIFECYCLE, GUI_CATEGORY_LANGUAGES, GUI_BACK, GUI_CLOSE, GUI_STATE, GUI_TOGGLE,
                GUI_NUMBER, GUI_TEXT, GUI_LANGUAGE_SELECT, GUI_CATEGORY_OPEN, GUI_FILE_ONLY, GUI_PROMPT,
                GUI_PROMPT_LIST, GUI_PROMPT_CANCEL, GUI_PROMPT_CANCELLED, GUI_PROMPT_TIMEOUT, SETTING_LANGUAGE,
                SETTING_ARCHIVE, SETTING_METRICS, SETTING_SLAVE_ENABLED, SETTING_SLAVE_PORT,
                SETTING_SLAVE_PAYLOAD, SETTING_MASTER_ENABLED, SETTING_MASTER_TARGETS,
                SETTING_MASTER_SIGNATURES, SETTING_SOCKET_TIMEOUT, SETTING_MAX_TRANSFER, SETTING_WATCHER_IDLE,
                SETTING_WATCHER_ACTIVE, SETTING_WATCHER_DEBOUNCE, SETTING_WATCHER_IGNORE, SETTING_WATCHER_ONLY,
                SETTING_LOG_TIMINGS, SETTING_HEALTH_CHECK));
        builder.addAll(DirectorMessages.keys());
        builder.addAll(BukkitLanguageMessages.keys());
        return builder.build();
    }

    private static TextKey setting(String id, String english) {
        return TextKey.of("gui.setting." + id, "&a" + english);
    }
}
