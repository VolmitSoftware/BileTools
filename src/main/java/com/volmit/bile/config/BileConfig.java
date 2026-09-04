package com.volmit.bile.config;

import art.arcane.volmlib.util.io.AtomicFileIO;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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

    private BileConfig(Builder builder) {
        language = sanitizeScalar(builder.language, "en_US");
        metrics = builder.metrics;
        remoteSlaveEnabled = builder.remoteSlaveEnabled;
        remoteSlavePort = Math.max(1, Math.min(65_535, builder.remoteSlavePort));
        remoteSlavePayload = sanitizeScalar(builder.remoteSlavePayload, "pickapassword");
        remoteMasterEnabled = builder.remoteMasterEnabled;
        remoteMasterDeployTargets = List.copyOf(sanitizeList(builder.remoteMasterDeployTargets));
        remoteMasterDeploySignatures = List.copyOf(sanitizeList(builder.remoteMasterDeploySignatures));
        archivePlugins = builder.archivePlugins;
        remoteSocketTimeoutMs = Math.max(1_000, builder.remoteSocketTimeoutMs);
        remoteMaxTransferBytes = Math.max(1024L * 1024L, builder.remoteMaxTransferBytes);
        watcherIdlePollTicks = Math.max(1L, builder.watcherIdlePollTicks);
        watcherActivePollTicks = Math.max(1L, builder.watcherActivePollTicks);
        watcherFingerprintDebounceTicks = Math.max(1, builder.watcherFingerprintDebounceTicks);
        watcherIgnore = List.copyOf(sanitizeList(builder.watcherIgnore));
        watcherOnly = List.copyOf(sanitizeList(builder.watcherOnly));
        logTimings = builder.logTimings;
        healthCheck = builder.healthCheck;
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
        Builder builder = defaults.toBuilder()
                .language(sanitizeScalar(yaml.getString(PATH_LANGUAGE), defaults.language))
                .metrics(yaml.getBoolean(PATH_METRICS, defaults.metrics))
                .remoteSlaveEnabled(yaml.getBoolean(PATH_SLAVE_ENABLED, defaults.remoteSlaveEnabled))
                .remoteSlavePort(yaml.getInt(PATH_SLAVE_PORT, defaults.remoteSlavePort))
                .remoteSlavePayload(sanitizeScalar(yaml.getString(PATH_SLAVE_PAYLOAD), defaults.remoteSlavePayload))
                .remoteMasterEnabled(yaml.getBoolean(PATH_MASTER_ENABLED, defaults.remoteMasterEnabled))
                .remoteMasterDeployTargets(yaml.contains(PATH_MASTER_DEPLOY_TO)
                        ? yaml.getStringList(PATH_MASTER_DEPLOY_TO) : defaults.remoteMasterDeployTargets)
                .remoteMasterDeploySignatures(yaml.contains(PATH_MASTER_DEPLOY_SIGNATURES)
                        ? yaml.getStringList(PATH_MASTER_DEPLOY_SIGNATURES) : defaults.remoteMasterDeploySignatures)
                .archivePlugins(yaml.getBoolean(PATH_ARCHIVE_PLUGINS, defaults.archivePlugins))
                .remoteSocketTimeoutMs(yaml.getInt(PATH_REMOTE_SOCKET_TIMEOUT_MS, defaults.remoteSocketTimeoutMs))
                .remoteMaxTransferBytes(yaml.getLong(PATH_REMOTE_MAX_BYTES, defaults.remoteMaxTransferBytes))
                .watcherIdlePollTicks(yaml.getLong(PATH_WATCHER_IDLE_TICKS, defaults.watcherIdlePollTicks))
                .watcherActivePollTicks(yaml.getLong(PATH_WATCHER_ACTIVE_TICKS, defaults.watcherActivePollTicks))
                .watcherFingerprintDebounceTicks(yaml.getInt(
                        PATH_WATCHER_DEBOUNCE_TICKS, defaults.watcherFingerprintDebounceTicks))
                .watcherIgnore(yaml.contains(PATH_WATCHER_IGNORE)
                        ? yaml.getStringList(PATH_WATCHER_IGNORE) : defaults.watcherIgnore)
                .watcherOnly(yaml.contains(PATH_WATCHER_ONLY)
                        ? yaml.getStringList(PATH_WATCHER_ONLY) : defaults.watcherOnly)
                .logTimings(yaml.getBoolean(PATH_LOG_TIMINGS, defaults.logTimings))
                .healthCheck(yaml.getBoolean(PATH_HEALTH_CHECK, defaults.healthCheck));
        BileConfig config = builder.build();
        config.save(file);
        return config;
    }

    public static BileConfig defaults() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public void save(File file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.isFile()) {
            try {
                yaml.load(file);
            } catch (Exception exception) {
                throw new IOException("Could not read the current BileTools configuration", exception);
            }
        }
        write(yaml);
        AtomicFileIO.writeString(file.toPath(), yaml.saveToString());
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
            boolean included = false;
            for (String entry : watcherOnly) {
                if (name.equals(entry.toLowerCase(Locale.ROOT))) {
                    included = true;
                    break;
                }
            }
            if (!included) {
                return false;
            }
        }
        for (String entry : watcherIgnore) {
            if (name.equals(entry.toLowerCase(Locale.ROOT))) {
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
            if (name.equals(signature.toLowerCase(Locale.ROOT))) {
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

    private static List<String> sanitizeList(List<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    normalized.add(value.trim());
                }
            }
        }
        return new ArrayList<>(normalized);
    }

    public static final class Builder {
        private String language = "en_US";
        private boolean metrics = true;
        private boolean remoteSlaveEnabled;
        private int remoteSlavePort = 9_876;
        private String remoteSlavePayload = "pickapassword";
        private boolean remoteMasterEnabled;
        private List<String> remoteMasterDeployTargets = List.of("yourserver.com:9876:password");
        private List<String> remoteMasterDeploySignatures = List.of("MyPlugin", "AnotherPlugin");
        private boolean archivePlugins = true;
        private int remoteSocketTimeoutMs = 15_000;
        private long remoteMaxTransferBytes = 256L * 1024L * 1024L;
        private long watcherIdlePollTicks = 20L;
        private long watcherActivePollTicks = 5L;
        private int watcherFingerprintDebounceTicks = 8;
        private List<String> watcherIgnore = List.of(
                "LuckPerms", "Vault", "ProtocolLib", "packetevents", "WorldGuard", "CoreProtect", "spark");
        private List<String> watcherOnly = List.of();
        private boolean logTimings = true;
        private boolean healthCheck = true;

        public Builder() {
        }

        private Builder(BileConfig config) {
            language = config.language;
            metrics = config.metrics;
            remoteSlaveEnabled = config.remoteSlaveEnabled;
            remoteSlavePort = config.remoteSlavePort;
            remoteSlavePayload = config.remoteSlavePayload;
            remoteMasterEnabled = config.remoteMasterEnabled;
            remoteMasterDeployTargets = config.remoteMasterDeployTargets;
            remoteMasterDeploySignatures = config.remoteMasterDeploySignatures;
            archivePlugins = config.archivePlugins;
            remoteSocketTimeoutMs = config.remoteSocketTimeoutMs;
            remoteMaxTransferBytes = config.remoteMaxTransferBytes;
            watcherIdlePollTicks = config.watcherIdlePollTicks;
            watcherActivePollTicks = config.watcherActivePollTicks;
            watcherFingerprintDebounceTicks = config.watcherFingerprintDebounceTicks;
            watcherIgnore = config.watcherIgnore;
            watcherOnly = config.watcherOnly;
            logTimings = config.logTimings;
            healthCheck = config.healthCheck;
        }

        public Builder language(String value) {
            language = value;
            return this;
        }

        public Builder metrics(boolean value) {
            metrics = value;
            return this;
        }

        public Builder remoteSlaveEnabled(boolean value) {
            remoteSlaveEnabled = value;
            return this;
        }

        public Builder remoteSlavePort(int value) {
            remoteSlavePort = value;
            return this;
        }

        public Builder remoteSlavePayload(String value) {
            remoteSlavePayload = value;
            return this;
        }

        public Builder remoteMasterEnabled(boolean value) {
            remoteMasterEnabled = value;
            return this;
        }

        public Builder remoteMasterDeployTargets(List<String> value) {
            remoteMasterDeployTargets = List.copyOf(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder remoteMasterDeploySignatures(List<String> value) {
            remoteMasterDeploySignatures = List.copyOf(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder archivePlugins(boolean value) {
            archivePlugins = value;
            return this;
        }

        public Builder remoteSocketTimeoutMs(int value) {
            remoteSocketTimeoutMs = value;
            return this;
        }

        public Builder remoteMaxTransferBytes(long value) {
            remoteMaxTransferBytes = value;
            return this;
        }

        public Builder watcherIdlePollTicks(long value) {
            watcherIdlePollTicks = value;
            return this;
        }

        public Builder watcherActivePollTicks(long value) {
            watcherActivePollTicks = value;
            return this;
        }

        public Builder watcherFingerprintDebounceTicks(int value) {
            watcherFingerprintDebounceTicks = value;
            return this;
        }

        public Builder watcherIgnore(List<String> value) {
            watcherIgnore = List.copyOf(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder watcherOnly(List<String> value) {
            watcherOnly = List.copyOf(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder logTimings(boolean value) {
            logTimings = value;
            return this;
        }

        public Builder healthCheck(boolean value) {
            healthCheck = value;
            return this;
        }

        public BileConfig build() {
            return new BileConfig(this);
        }
    }
}
