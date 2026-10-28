package com.volmit.bile.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileConfigTest {
    @Test
    public void updateNotificationsDefaultOnAndOptOutSurvivesCopiesAndReload() throws Exception {
        File file = temporaryFolder.newFile("updates.yml");
        BileConfig defaults = BileConfig.load(file);
        assertTrue(defaults.isUpdateNotifications());
        defaults.toBuilder().updateNotifications(false).build().save(file);
        BileConfig loaded = BileConfig.load(file);
        assertFalse(loaded.isUpdateNotifications());
        assertFalse(loaded.toBuilder().language("de_DE").build().isUpdateNotifications());
    }

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void removesObsoleteCoalesceWindowAndRestoresMissingIgnoreDefaults() throws Exception {
        File configFile = temporaryFolder.newFile("biletools.yml");
        Files.writeString(
                configFile.toPath(),
                "watcher:\n  coalesce-window-ticks: 1\n",
                StandardCharsets.UTF_8);

        BileConfig config = BileConfig.load(configFile);
        YamlConfiguration rewritten = YamlConfiguration.loadConfiguration(configFile);

        assertFalse(rewritten.contains("watcher.coalesce-window-ticks"));
        assertEquals(BileConfig.defaults().getWatcherIgnore(), config.getWatcherIgnore());
    }

    @Test
    public void preservesExplicitlyEmptyIgnoreList() throws Exception {
        File configFile = temporaryFolder.newFile("biletools.yml");
        Files.writeString(configFile.toPath(), "watcher:\n  ignore: []\n", StandardCharsets.UTF_8);

        BileConfig config = BileConfig.load(configFile);

        assertTrue(config.getWatcherIgnore().isEmpty());
    }

    @Test
    public void remoteDeployStartsWithoutPlaceholdersAndKeepsTrustedKeys() throws Exception {
        File configFile = temporaryFolder.newFile("biletools.yml");

        BileConfig defaults = BileConfig.load(configFile);
        assertTrue(defaults.getRemoteSlaveTrustedKeys().isEmpty());
        assertTrue(defaults.getRemoteMasterDeployTargets().isEmpty());
        assertEquals("0.0.0.0", defaults.getRemoteSlaveBindAddress());
        assertTrue(defaults.getRemoteSlaveAllowedAddresses().isEmpty());
        assertTrue(defaults.getRemoteMasterTrustedKeys().isEmpty());

        defaults.toBuilder()
                .remoteSlaveBindAddress(" 10.0.0.5 ")
                .remoteSlaveTrustedKeys(List.of("key-one", " key-two ", "key-one"))
                .remoteMasterTrustedKeys(List.of(" receiver-key "))
                .build()
                .save(configFile);
        BileConfig loaded = BileConfig.load(configFile);

        assertEquals("10.0.0.5", loaded.getRemoteSlaveBindAddress());
        assertEquals(List.of("key-one", "key-two"), loaded.getRemoteSlaveTrustedKeys());
        assertEquals(List.of("receiver-key"), loaded.getRemoteMasterTrustedKeys());
    }

    @Test
    public void writesLanguageThenMetricsBeforeOperationalSettings() throws Exception {
        File configFile = temporaryFolder.newFile("biletools.yml");

        BileConfig config = BileConfig.load(configFile);
        String written = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);

        assertEquals("en_US", config.getLanguage());
        assertTrue(config.isMetrics());
        assertTrue(written.indexOf("language: en_US") >= 0);
        assertTrue(written.indexOf("metrics: true") > written.indexOf("language: en_US"));
        assertTrue(written.indexOf("remote-deploy:") > written.indexOf("metrics: true"));
    }
}
