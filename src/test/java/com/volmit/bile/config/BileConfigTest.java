package com.volmit.bile.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileConfigTest {
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
