package com.volmit.bile.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BileConfig {
    private static final String PATH_LANGUAGE = "language";
    private static final String PATH_METRICS = "metrics";
    private static final String PATH_SLAVE_ENABLED = "remote-deploy.slave.slave-enabled";
    private static final String PATH_SLAVE_PORT = "remote-deploy.slave.slave-port";
    private static final String PATH_SLAVE_PAYLOAD = "remote-deploy.slave.slave-payload";
    private static final String PATH_MASTER_ENABLED = "remote-deploy.master.master-enabled";
    private static final String PATH_MASTER_DEPLOY_TO = "remote-deploy.master.master-deploy-to";
    private static final String PATH_MASTER_DEPLOY_SIGNATURES = "remote-deploy.master.master-deploy-signatures";
    private static final String PATH_ARCHIVE_PLUGINS = "archive-plugins";
    private static final String PATH_REMOTE_SOCKET_TIMEOUT_MS = "remote-deploy.socket-timeout-ms";
    private static final String PATH_REMOTE_MAX_BYTES = "remote-deploy.max-transfer-bytes";
    private static final String PATH_WATCHER_IDLE_TICKS = "watcher.idle-poll-ticks";
    private static final String PATH_WATCHER_ACTIVE_TICKS = "watcher.active-poll-ticks";
    private static final String PATH_WATCHER_DEBOUNCE_TICKS = "watcher.fingerprint-debounce-ticks";
    private static final String PATH_WATCHER_IGNORE = "watcher.ignore";
    private static final String PATH_WATCHER_ONLY = "watcher.only";
    private static final String PATH_REMOVED_WATCHER_COALESCE_TICKS = "watcher.coalesce-window-ticks";
    private static final String PATH_LOG_TIMINGS = "observability.log-timings";
    private static final String PATH_HEALTH_CHECK = "lifecycle.health-check";

    private final String language;
    private final boolean metrics;
    private final boolean remoteSlaveEnabled;
    private final int remoteSlavePort;
    private final String remoteSlavePayload;
    private final boolean remoteMasterEnabled;
    private final List<String> remoteMasterDeployTargets;
    private final List<String> remoteMasterDeploySignatures;
    private final boolean archivePlugins;
    private final int remoteSocketTimeoutMs;
    private final long remoteMaxTransferBytes;
    private final long watcherIdlePollTicks;
    private final long watcherActivePollTicks;
    private final int watcherFingerprintDebounceTicks;
    private final List<String> watcherIgnore;
    private final List<String> watcherOnly;
    private final boolean logTimings;
    private final boolean healthCheck;

    public BileConfig(String language,
                      boolean metrics,
                      boolean remoteSlaveEnabled,
                      int remoteSlavePort,
                      String remoteSlavePayload,
                      boolean remoteMasterEnabled,
                      List<String> remoteMasterDeployTargets,
                      List<String> remoteMasterDeploySignatures,
                      boolean archivePlugins,
                      int remoteSocketTimeoutMs,
                      long remoteMaxTransferBytes,
                      long watcherIdlePollTicks,
                      long watcherActivePollTicks,
                      int watcherFingerprintDebounceTicks,
                      List<String> watcherIgnore,
                      List<String> watcherOnly,
                      boolean logTimings,
                      boolean healthCheck) {
        this.language = language;
        this.metrics = metrics;
        this.remoteSlaveEnabled = remoteSlaveEnabled;
        this.remoteSlavePort = remoteSlavePort;
        this.remoteSlavePayload = remoteSlavePayload;
        this.remoteMasterEnabled = remoteMasterEnabled;
        this.remoteMasterDeployTargets = List.copyOf(remoteMasterDeployTargets);
        this.remoteMasterDeploySignatures = List.copyOf(remoteMasterDeploySignatures);
        this.archivePlugins = archivePlugins;
        this.remoteSocketTimeoutMs = remoteSocketTimeoutMs;
        this.remoteMaxTransferBytes = remoteMaxTransferBytes;
        this.watcherIdlePollTicks = watcherIdlePollTicks;
        this.watcherActivePollTicks = watcherActivePollTicks;
        this.watcherFingerprintDebounceTicks = watcherFingerprintDebounceTicks;
        this.watcherIgnore = List.copyOf(watcherIgnore);
        this.watcherOnly = List.copyOf(watcherOnly);
        this.logTimings = logTimings;
        this.healthCheck = healthCheck;
    }

    public static BileConfig load(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        YamlConfiguration yaml = new YamlConfiguration();
        if (file.exists()) {
            yaml.load(file);
        }

        BileConfig defaults = defaults();
        String language = sanitizeScalar(yaml.getString(PATH_LANGUAGE), defaults.language);
        boolean metrics = yaml.getBoolean(PATH_METRICS, defaults.metrics);
        boolean remoteSlaveEnabled = yaml.getBoolean(PATH_SLAVE_ENABLED, defaults.remoteSlaveEnabled);
        int remoteSlavePort = yaml.getInt(PATH_SLAVE_PORT, defaults.remoteSlavePort);
        String remoteSlavePayload = sanitizeScalar(yaml.getString(PATH_SLAVE_PAYLOAD), defaults.remoteSlavePayload);
        boolean remoteMasterEnabled = yaml.getBoolean(PATH_MASTER_ENABLED, defaults.remoteMasterEnabled);
        List<String> remoteMasterDeployTargets = sanitizeList(yaml.getStringList(PATH_MASTER_DEPLOY_TO), defaults.remoteMasterDeployTargets);
        List<String> remoteMasterDeploySignatures = sanitizeList(yaml.getStringList(PATH_MASTER_DEPLOY_SIGNATURES), defaults.remoteMasterDeploySignatures);
        boolean archivePlugins = yaml.getBoolean(PATH_ARCHIVE_PLUGINS, defaults.archivePlugins);
        int remoteSocketTimeoutMs = Math.max(1000, yaml.getInt(PATH_REMOTE_SOCKET_TIMEOUT_MS, defaults.remoteSocketTimeoutMs));
        long remoteMaxTransferBytes = Math.max(1024L * 1024L, yaml.getLong(PATH_REMOTE_MAX_BYTES, defaults.remoteMaxTransferBytes));
        long watcherIdlePollTicks = Math.max(1L, yaml.getLong(PATH_WATCHER_IDLE_TICKS, defaults.watcherIdlePollTicks));
        long watcherActivePollTicks = Math.max(1L, yaml.getLong(PATH_WATCHER_ACTIVE_TICKS, defaults.watcherActivePollTicks));
        int watcherFingerprintDebounceTicks = Math.max(1, yaml.getInt(PATH_WATCHER_DEBOUNCE_TICKS, defaults.watcherFingerprintDebounceTicks));
        List<String> watcherIgnore = yaml.contains(PATH_WATCHER_IGNORE)
                ? sanitizeListAllowEmpty(yaml.getStringList(PATH_WATCHER_IGNORE))
                : defaults.watcherIgnore;
        List<String> watcherOnly = sanitizeListAllowEmpty(yaml.getStringList(PATH_WATCHER_ONLY));
        boolean logTimings = yaml.getBoolean(PATH_LOG_TIMINGS, defaults.logTimings);
        boolean healthCheck = yaml.getBoolean(PATH_HEALTH_CHECK, defaults.healthCheck);

        BileConfig config = new BileConfig(
                language,
                metrics,
                remoteSlaveEnabled,
                remoteSlavePort,
                remoteSlavePayload,
                remoteMasterEnabled,
                remoteMasterDeployTargets,
                remoteMasterDeploySignatures,
                archivePlugins,
                remoteSocketTimeoutMs,
                remoteMaxTransferBytes,
                watcherIdlePollTicks,
                watcherActivePollTicks,
                watcherFingerprintDebounceTicks,
                watcherIgnore,
                watcherOnly,
                logTimings,
                healthCheck
        );

        config.write(yaml);
        yaml.save(file);
        return config;
    }

    public static BileConfig defaults() {
        List<String> deployTargets = new ArrayList<>();
        deployTargets.add("yourserver.com:9876:password");

        List<String> deploySignatures = new ArrayList<>();
        deploySignatures.add("MyPlugin");
        deploySignatures.add("AnotherPlugin");

        // Plugins that are usually dangerous or useless to auto-reload on a dev box.
        List<String> ignore = new ArrayList<>();
        ignore.add("LuckPerms");
        ignore.add("Vault");
        ignore.add("ProtocolLib");
        ignore.add("packetevents");
        ignore.add("WorldGuard");
        ignore.add("CoreProtect");
        ignore.add("spark");

        return new BileConfig(
                "en_US",
                true,
                false,
                9876,
                "pickapassword",
                false,
                deployTargets,
                deploySignatures,
                true,
                15_000,
                256L * 1024L * 1024L,
                20L,
                5L,
                8,
                ignore,
                List.of(),
                true,
                true
        );
    }

    public void write(YamlConfiguration yaml) {
        yaml.set(PATH_LANGUAGE, language);
        yaml.set(PATH_METRICS, metrics);
        yaml.set(PATH_SLAVE_ENABLED, remoteSlaveEnabled);
        yaml.set(PATH_SLAVE_PORT, remoteSlavePort);
        yaml.set(PATH_SLAVE_PAYLOAD, remoteSlavePayload);
        yaml.set(PATH_MASTER_ENABLED, remoteMasterEnabled);
        yaml.set(PATH_MASTER_DEPLOY_TO, remoteMasterDeployTargets);
        yaml.set(PATH_MASTER_DEPLOY_SIGNATURES, remoteMasterDeploySignatures);
        yaml.set(PATH_ARCHIVE_PLUGINS, archivePlugins);
        yaml.set(PATH_REMOTE_SOCKET_TIMEOUT_MS, remoteSocketTimeoutMs);
        yaml.set(PATH_REMOTE_MAX_BYTES, remoteMaxTransferBytes);
        yaml.set(PATH_WATCHER_IDLE_TICKS, watcherIdlePollTicks);
        yaml.set(PATH_WATCHER_ACTIVE_TICKS, watcherActivePollTicks);
        yaml.set(PATH_WATCHER_DEBOUNCE_TICKS, watcherFingerprintDebounceTicks);
        yaml.set(PATH_WATCHER_IGNORE, watcherIgnore);
        yaml.set(PATH_WATCHER_ONLY, watcherOnly);
        yaml.set(PATH_REMOVED_WATCHER_COALESCE_TICKS, null);
        yaml.set(PATH_LOG_TIMINGS, logTimings);
        yaml.set(PATH_HEALTH_CHECK, healthCheck);
    }

    /**
     * Whether auto watcher paths (hot-drop / file-change reload / file-removal unload)
     * should act on this plugin. Manual commands always ignore this filter.
     */
    public boolean isAutoReloadAllowed(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return false;
        }

        String name = pluginName.trim().toLowerCase(Locale.ROOT);

        if (!watcherOnly.isEmpty()) {
            boolean allowed = false;
            for (String entry : watcherOnly) {
                if (entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(name)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                return false;
            }
        }

        for (String entry : watcherIgnore) {
            if (entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(name)) {
                return false;
            }
        }

        return true;
    }

    public String getLanguage() {
        return language;
    }

    public boolean isMetrics() {
        return metrics;
    }

    public boolean isRemoteSlaveEnabled() {
        return remoteSlaveEnabled;
    }

    public int getRemoteSlavePort() {
        return remoteSlavePort;
    }

    public String getRemoteSlavePayload() {
        return remoteSlavePayload;
    }

    public boolean isRemoteMasterEnabled() {
        return remoteMasterEnabled;
    }

    public List<String> getRemoteMasterDeployTargets() {
        return remoteMasterDeployTargets;
    }

    public List<String> getRemoteMasterDeploySignatures() {
        return remoteMasterDeploySignatures;
    }

    public boolean isArchivePlugins() {
        return archivePlugins;
    }

    public int getRemoteSocketTimeoutMs() {
        return remoteSocketTimeoutMs;
    }

    public long getRemoteMaxTransferBytes() {
        return remoteMaxTransferBytes;
    }

    public long getWatcherIdlePollTicks() {
        return watcherIdlePollTicks;
    }

    public long getWatcherActivePollTicks() {
        return watcherActivePollTicks;
    }

    public int getWatcherFingerprintDebounceTicks() {
        return watcherFingerprintDebounceTicks;
    }

    public List<String> getWatcherIgnore() {
        return watcherIgnore;
    }

    public List<String> getWatcherOnly() {
        return watcherOnly;
    }

    public boolean isLogTimings() {
        return logTimings;
    }

    public boolean isHealthCheck() {
        return healthCheck;
    }

    public boolean hasRemoteDeploySignature(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return false;
        }

        String name = pluginName.trim().toLowerCase(Locale.ROOT);
        for (String signature : remoteMasterDeploySignatures) {
            if (signature != null && signature.trim().toLowerCase(Locale.ROOT).equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static String sanitizeScalar(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }

        return value.trim();
    }

    private static List<String> sanitizeList(List<String> values, List<String> fallback) {
        List<String> normalized = sanitizeListAllowEmpty(values);
        if (normalized.isEmpty()) {
            return new ArrayList<>(fallback);
        }
        return normalized;
    }

    private static List<String> sanitizeListAllowEmpty(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }

                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                normalized.add(trimmed);
            }
        }

        return new ArrayList<>(normalized);
    }
}
