package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileLocalizationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private BileLocalization localization;

    @Before
    public void setUp() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        localization = new BileLocalization(temporaryFolder.newFolder(), logger);
    }

    @After
    public void tearDown() {
        localization.close();
    }

    @Test
    public void generatesSparseLocaleSelectorWithEnglishInTheTypedCatalog() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(localization.languageFile());

        assertEquals("en_US", yaml.getString("locale"));
        assertFalse(yaml.contains("messages"));
        assertEquals("Load a plugin jar from the plugins directory", BileMessages.COMMAND_LOAD.english());
    }

    @Test
    public void everyBundledLocaleFullyCoversTheTypedCatalog() throws Exception {
        for (String locale : VolmitLocales.nonEnglish()) {
            YamlConfiguration yaml = loadLanguageFile();
            yaml.set("locale", locale);
            yaml.set("messages", null);
            yaml.save(localization.languageFile());

            assertTrue(locale, localization.reload());
            for (MessageKey key : localization.snapshot().catalog().keys()) {
                assertEquals(locale + ":" + key.id(), locale, localization.snapshot().sourceLocale(key));
            }
        }
    }

    @Test
    public void bundledResourceSetExactlyMatchesSharedManifest() throws Exception {
        Set<String> expected = VolmitLocales.nonEnglish().stream()
                .map(locale -> locale + ".yml")
                .collect(Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
            Set<String> actual = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
            assertEquals(expected, actual);
        }
        assertFalse(expected.contains(VolmitLocales.ENGLISH + ".yml"));
    }

    @Test
    public void appliesExternalOverrideWithNamedArguments() throws Exception {
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("locale", "de_DE");
        yaml.set("messages." + BileMessages.LOAD_SUCCESS.id(), "&b{file} -> {plugin} ({milliseconds})");
        yaml.save(localization.languageFile());

        assertTrue(localization.reload());
        String rendered = localization.text(
                BileMessages.LOAD_SUCCESS,
                MessageArgs.builder()
                        .untrusted("plugin", "Demo")
                        .untrusted("file", "Demo.jar")
                        .untrusted("milliseconds", 12)
                        .build()
        );

        assertEquals(ChatColor.AQUA + "Demo.jar -> Demo (12)", rendered);
        assertEquals("de_DE", localization.activeLocale());
    }

    @Test
    public void rejectsInvalidReloadAndRetainsLastGoodSnapshot() throws Exception {
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.PERMISSION_DENIED.id(), "Allowed only with {permission}");
        yaml.save(localization.languageFile());
        assertTrue(localization.reload());

        MessageArgs arguments = MessageArgs.builder().untrusted("permission", "bile.use").build();
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments));

        yaml.set("messages." + BileMessages.PERMISSION_DENIED.id(), "Missing its named argument");
        yaml.save(localization.languageFile());

        assertFalse(localization.reload());
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments));
    }

    @Test
    public void resolvesDirectorLabelsAndDoesNotRenderUntrustedFormatting() throws Exception {
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages.director.help.navigation.back", "&aZurück");
        yaml.save(localization.languageFile());
        assertTrue(localization.reload());

        assertEquals("Zurück", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
        assertEquals(
                "Unknown parameter key: &cBadName",
                localization.directorResolver().resolve(
                        DirectorRuntimeMessages.UNKNOWN_PARAMETER,
                        MessageArgs.builder().untrusted("key", "&cBad" + ChatColor.DARK_RED + "Name").build()
                )
        );
        String rendered = localization.text(
                BileMessages.PLUGIN_NOT_FOUND,
                MessageArgs.builder().untrusted("plugin", "&cBad" + ChatColor.DARK_RED + "Name").build()
        );
        assertTrue(rendered.contains("&cBadName"));
        assertFalse(rendered.contains(String.valueOf(ChatColor.DARK_RED)));
    }

    @Test
    public void insertedArgumentsAreNeverReprocessedAsLaterSentinels() {
        String rendered = localization.text(
                BileMessages.LOAD_SUCCESS,
                MessageArgs.builder()
                        .untrusted("plugin", "\uE0001\uE001")
                        .untrusted("file", "replacement.jar")
                        .untrusted("milliseconds", 12)
                        .build()
        );

        assertTrue(rendered.contains("\uE0001\uE001"));
        assertTrue(rendered.contains("replacement.jar"));
    }

    @Test
    public void automaticReloadsAreQueuedBehindThreeSecondCooldown() throws Exception {
        long originalModified = Files.getLastModifiedTime(localization.languageFile().toPath()).toMillis();
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("locale", "de_DE");
        yaml.save(localization.languageFile());
        Files.setLastModifiedTime(localization.languageFile().toPath(), FileTime.fromMillis(originalModified + 1_000L));
        localization.update(100L);
        assertEquals("de_DE", localization.activeLocale());

        yaml = loadLanguageFile();
        yaml.set("locale", "fr_FR");
        yaml.save(localization.languageFile());
        Files.setLastModifiedTime(localization.languageFile().toPath(), FileTime.fromMillis(originalModified + 2_000L));
        localization.update(TimeUnit.SECONDS.toNanos(2L));
        assertEquals("de_DE", localization.activeLocale());

        localization.update(100L + TimeUnit.SECONDS.toNanos(3L));
        assertEquals("fr_FR", localization.activeLocale());
    }

    @Test
    public void closeStopsAutomaticLanguageReloads() throws Exception {
        localization.close();
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("locale", "de_DE");
        yaml.save(localization.languageFile());

        localization.update(Long.MAX_VALUE);

        assertEquals("en_US", localization.activeLocale());
    }

    private YamlConfiguration loadLanguageFile() throws Exception {
        File file = localization.languageFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }
}
