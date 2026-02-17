package com.volmit.bile.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BileConfig {
    private static final String PATH_SLAVE_ENABLED = "remote-deploy.slave.slave-enabled";
    private static final String PATH_SLAVE_PORT = "remote-deploy.slave.slave-port";
    private static final String PATH_SLAVE_PAYLOAD = "remote-deploy.slave.slave-payload";
    private static final String PATH_MASTER_ENABLED = "remote-deploy.master.master-enabled";
    private static final String PATH_MASTER_DEPLOY_TO = "remote-deploy.master.master-deploy-to";
    private static final String PATH_MASTER_DEPLOY_SIGNATURES = "remote-deploy.master.master-deploy-signatures";
    private static final String PATH_ARCHIVE_PLUGINS = "archive-plugins";

    private final boolean remoteSlaveEnabled;
    private final int remoteSlavePort;
    private final String remoteSlavePayload;
    private final boolean remoteMasterEnabled;
    private final List<String> remoteMasterDeployTargets;
    private final List<String> remoteMasterDeploySignatures;
    private final boolean archivePlugins;

    public BileConfig(boolean remoteSlaveEnabled,
                      int remoteSlavePort,
                      String remoteSlavePayload,
                      boolean remoteMasterEnabled,
                      List<String> remoteMasterDeployTargets,
                      List<String> remoteMasterDeploySignatures,
                      boolean archivePlugins) {
        this.remoteSlaveEnabled = remoteSlaveEnabled;
        this.remoteSlavePort = remoteSlavePort;
        this.remoteSlavePayload = remoteSlavePayload;
        this.remoteMasterEnabled = remoteMasterEnabled;
        this.remoteMasterDeployTargets = List.copyOf(remoteMasterDeployTargets);
        this.remoteMasterDeploySignatures = List.copyOf(remoteMasterDeploySignatures);
        this.archivePlugins = archivePlugins;
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
        boolean remoteSlaveEnabled = yaml.getBoolean(PATH_SLAVE_ENABLED, defaults.remoteSlaveEnabled);
        int remoteSlavePort = yaml.getInt(PATH_SLAVE_PORT, defaults.remoteSlavePort);
        String remoteSlavePayload = sanitizeScalar(yaml.getString(PATH_SLAVE_PAYLOAD), defaults.remoteSlavePayload);
        boolean remoteMasterEnabled = yaml.getBoolean(PATH_MASTER_ENABLED, defaults.remoteMasterEnabled);
        List<String> remoteMasterDeployTargets = sanitizeList(yaml.getStringList(PATH_MASTER_DEPLOY_TO), defaults.remoteMasterDeployTargets);
        List<String> remoteMasterDeploySignatures = sanitizeList(yaml.getStringList(PATH_MASTER_DEPLOY_SIGNATURES), defaults.remoteMasterDeploySignatures);
        boolean archivePlugins = yaml.getBoolean(PATH_ARCHIVE_PLUGINS, defaults.archivePlugins);

        BileConfig config = new BileConfig(
                remoteSlaveEnabled,
                remoteSlavePort,
                remoteSlavePayload,
                remoteMasterEnabled,
                remoteMasterDeployTargets,
                remoteMasterDeploySignatures,
                archivePlugins
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

        return new BileConfig(
                false,
                9876,
                "pickapassword",
                false,
                deployTargets,
                deploySignatures,
                true
        );
    }

    public void write(YamlConfiguration yaml) {
        yaml.set(PATH_SLAVE_ENABLED, remoteSlaveEnabled);
        yaml.set(PATH_SLAVE_PORT, remoteSlavePort);
        yaml.set(PATH_SLAVE_PAYLOAD, remoteSlavePayload);
        yaml.set(PATH_MASTER_ENABLED, remoteMasterEnabled);
        yaml.set(PATH_MASTER_DEPLOY_TO, remoteMasterDeployTargets);
        yaml.set(PATH_MASTER_DEPLOY_SIGNATURES, remoteMasterDeploySignatures);
        yaml.set(PATH_ARCHIVE_PLUGINS, archivePlugins);
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

        if (normalized.isEmpty()) {
            normalized.addAll(fallback);
        }

        return new ArrayList<>(normalized);
    }
}
