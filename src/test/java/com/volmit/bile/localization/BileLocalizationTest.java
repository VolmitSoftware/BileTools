package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.BukkitLanguageMessages;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BileLocalizationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private BileLocalization localization;

    @Before
    public void setUp() throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        localization = new BileLocalization(temporaryFolder.newFolder(), logger, "en_US");
    }

    @After
    public void tearDown() {
        localization.close();
    }

    @Test
    public void createsDirectTomlEnglishCatalogAndLanguagePreferences() throws Exception {
        assertEquals("en_US.toml", localization.languageFile().getName());
        assertTrue(localization.languageFile().isFile());
        assertFalse(new File(localization.languageDirectory(), "overrides").exists());
        localization.languageService().selectPlayer(UUID.randomUUID(), "en_US").get(5L, TimeUnit.SECONDS);
        assertTrue(Files.exists(localization.languageDirectory().toPath()
                .resolve("language-preferences.properties")));
    }

    @Test
    public void englishReferenceUsesCanonicalKeysAndSpecificPlaceholderDescriptions() throws Exception {
        String english = Files.readString(localization.languageFile().toPath(), StandardCharsets.UTF_8);
        assertFalse(english.contains("Message-specific value"));
        assertTrue(english.contains("{file}  Plugin jar filename"));
        assertTrue(english.contains("{milliseconds}  Load, unload, or reload duration in milliseconds"));
        assertTrue(english.contains("{new}  New setting or message value"));
        assertTrue(english.contains("{old}  Previous setting or message value"));
        assertTrue(english.contains("{permission}  Required permission node"));
        assertTrue(english.contains("{personal}  Personal locale when different from the server default"));
        assertTrue(english.contains("{setting}  Setting name or message key"));
        assertTrue(english.contains("[gui.setting.general]"));
        assertFalse(english.contains("[bile."));
        int firstTable = english.indexOf("\n[");
        Set<String> documentedPlaceholders = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}")
                .matcher(english.substring(0, firstTable)).results()
                .map(result -> result.group(1)).collect(Collectors.toUnmodifiableSet());
        Set<String> catalogPlaceholders = BileMessages.catalog().keys().stream()
                .flatMap(key -> key.placeholders().stream()).collect(Collectors.toUnmodifiableSet());
        assertEquals(catalogPlaceholders, documentedPlaceholders);
        for (MessageKey key : BileMessages.catalog().keys()) {
            assertFalse(key.id(), key.id().startsWith("bile."));
        }
    }

    @Test
    public void editReceiptIsOneCompactLineWithNewValueBeforeOldValue() {
        ComponentText rendered = localization.text(BileMessages.CHANGE_SAVED, MessageArgs.builder()
                .untrusted("setting", "message.reload.success")
                .untrusted("new", "new value")
                .untrusted("old", "old value")
                .build());

        assertEquals("[Bile]: message.reload.success changed to new value from old value.", rendered.plain());
    }

    @Test
    public void singleLineReceiptFlattensAnExistingMultilineLanguageEntry() throws Exception {
        write(BileMessages.CHANGE_SAVED, new TextValue(
                "&a[&8Bile&a]: &f{setting}\n&aChanged to: &f{new}\n&7From: &f{old}"));
        assertTrue(localization.reload());

        ComponentText rendered = localization.singleLineText(null, BileMessages.CHANGE_SAVED,
                MessageArgs.builder()
                        .untrusted("setting", "Archive plugin jars before replacement")
                        .untrusted("new", "false")
                        .untrusted("old", "true")
                        .build());

        assertEquals("[Bile]: Archive plugin jars before replacement Changed to: false From: true",
                rendered.plain());
        assertFalse(rendered.plain().contains("\n"));
    }

    @Test
    public void editorPreservesPluralFormsAndUnknownTomlKeys() throws Exception {
        PluginLanguageEditor.Options editor = localization.editorOptions();
        LocalizationSnapshot original = editor.loader().load("en_US");
        Files.writeString(localization.languageFile().toPath(),
                Files.readString(localization.languageFile().toPath()) + "\n[operator]\nprivate_note = \"keep me\"\n");
        PluralValue existing = (PluralValue) original.value(BileMessages.REMOTE_DEPLOYED);
        LinkedHashMap<String, String> forms = new LinkedHashMap<>(existing.forms());
        forms.replaceAll((name, value) -> value + " edited");
        PluralValue replacement = new PluralValue(forms);
        editor.writer().write(new PluginLanguageEditor.Edit("en_US", BileMessages.REMOTE_DEPLOYED.id(),
                existing, replacement));
        assertEquals(replacement, editor.loader().load("en_US").value(BileMessages.REMOTE_DEPLOYED));
        assertTrue(Files.readString(localization.languageFile().toPath()).contains("private_note = \"keep me\""));
    }

    @Test
    public void editorRejectsInvalidAndStaleValuesWithoutChangingFile() throws Exception {
        PluginLanguageEditor.Options editor = localization.editorOptions();
        MessageValue original = editor.loader().load("en_US").value(BileMessages.COMMAND_ROOT);
        assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("{unexpected}"))));
        editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("First edit")));
        byte[] first = Files.readAllBytes(localization.languageFile().toPath());
        assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("Stale edit"))));
        assertArrayEquals(first, Files.readAllBytes(localization.languageFile().toPath()));
    }

    @Test
    public void everyDownloadableLocaleFullyCoversTypedCatalogIncludingSharedLanguageMenu() throws Exception {
        Set<String> catalogIds = BileMessages.catalog().keys().stream()
                .map(MessageKey::id).collect(Collectors.toUnmodifiableSet());
        Set<String> catalogPlaceholders = BileMessages.catalog().keys().stream()
                .flatMap(key -> key.placeholders().stream()).collect(Collectors.toUnmodifiableSet());
        for (String locale : VolmitLocales.nonEnglish()) {
            String content = Files.readString(Path.of("src/main/resources/languages", locale + ".toml"));
            int firstTable = content.indexOf("\n[");
            Set<String> documentedPlaceholders = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}")
                    .matcher(content.substring(0, firstTable)).results()
                    .map(result -> result.group(1)).collect(Collectors.toUnmodifiableSet());
            assertEquals(locale, catalogPlaceholders, documentedPlaceholders);
            assertFalse(locale, content.contains("Message-specific value"));
            Map<String, MessageValue> values = TomlLanguageParser.parseValidValues(content, BileMessages.catalog());
            assertEquals(locale, catalogIds, values.keySet());
            for (MessageKey key : BileMessages.catalog().keys()) {
                assertEquals(locale + ":" + key.id(), key.placeholders(), values.get(key.id()).placeholders());
            }
            localization.close();
            localization = new BileLocalization(installedLocaleFolder("locale-" + locale, locale),
                    Logger.getAnonymousLogger(), locale);
            for (MessageKey key : localization.snapshot().catalog().keys()) {
                assertEquals(locale + ":" + key.id(), locale, localization.snapshot().sourceLocale(key));
            }
        }
    }

    @Test
    public void downloadableResourceSetExactlyMatchesSharedManifest() throws Exception {
        Set<String> expected = VolmitLocales.nonEnglish().stream()
                .map(locale -> locale + ".toml").collect(Collectors.toUnmodifiableSet());
        try (Stream<Path> paths = Files.list(Path.of("src/main/resources/languages"))) {
            Set<String> actual = paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString()).collect(Collectors.toUnmodifiableSet());
            assertEquals(expected, actual);
        }
        for (String resource : expected) {
            assertFalse(resource, Files.readString(Path.of("src/main/resources/languages", resource))
                    .contains("[bile."));
        }
        assertFalse(expected.contains(VolmitLocales.ENGLISH + ".toml"));
    }

    @Test
    public void languageSourceManifestTracksTheRepositoryBranch() {
        LanguageSourceManifest manifest = LanguageSourceManifest.load(BileLocalization.class.getClassLoader(),
                BileLocalization.MANIFEST_RESOURCE);
        assertEquals("VolmitSoftware/BileTools", manifest.repository());
        assertFalse(manifest.branch().isBlank());
        assertEquals("src/main/resources/languages", manifest.path());
        assertEquals(".toml", manifest.extension());
        assertEquals(Set.copyOf(VolmitLocales.nonEnglish()), Set.copyOf(manifest.locales()));
        assertEquals("VolmitSoftware/BileTools@" + manifest.branch(), localization.sourceDescription());
        assertTrue(Files.isRegularFile(localization.languageDirectory().toPath().resolve(".upstream/en_US.toml")));
    }

    @Test
    public void upstreamSyncInstallsMergesAndKeepsEditsAndCustomFiles() throws Exception {
        localization.close();
        LoopbackRepository repository = new LoopbackRepository();
        repository.files.put(LoopbackRepository.REVISION_ONE + "/fr_FR",
                "# fr v1\n\n[command]\nload = \"Charger v1\"\nunload = \"Retirer v1\"\n");
        File folder = temporaryFolder.newFolder("upstream-sync");
        try {
            localization = repository.localization(folder);
            Path pirate = folder.toPath().resolve("languages/pirate.toml");
            String pirateContent = "[meta]\nname = \"Pirate Speak\"\nbase = \"fr_FR\"\n\n[command]\nload = \"Hoist\"\n";
            Files.writeString(pirate, pirateContent);
            UUID player = UUID.randomUUID();

            assertTrue(localization.requestInstall("fr_FR").get(10L, TimeUnit.SECONDS).contains("Charger v1"));
            assertTrue(Files.isRegularFile(folder.toPath().resolve("languages/.upstream/fr_FR.toml")));
            assertTrue(Files.isRegularFile(folder.toPath().resolve("languages/.upstream/upstream.properties")));
            localization.languageService().selectPlayer(player, "fr_FR").get(5L, TimeUnit.SECONDS);
            PluginLanguageEditor.Options editor = localization.editorOptions();
            MessageValue unload = editor.loader().load("fr_FR").value(BileMessages.COMMAND_UNLOAD);
            editor.writer().write(new PluginLanguageEditor.Edit("fr_FR", BileMessages.COMMAND_UNLOAD.id(),
                    unload, new TextValue("Retirer local")));
            repository.revision.set(LoopbackRepository.REVISION_TWO);
            repository.files.put(LoopbackRepository.REVISION_TWO + "/fr_FR",
                    "# fr v2\n\n[command]\nload = \"Charger v2\"\nunload = \"Retirer v2\"\n"
                            + "reload = \"Recharger v2\"\n");

            LanguageUpstreamSync.SyncReport report = localization.updateAction().update().get(10L, TimeUnit.SECONDS);

            assertTrue(report.failure().isEmpty());
            assertEquals(List.of("fr_FR"), report.changedLocales());
            assertEquals(List.of(BileMessages.COMMAND_UNLOAD.id()), report.merged().get("fr_FR").kept());
            assertEquals(List.of(BileMessages.COMMAND_RELOAD.id()), report.merged().get("fr_FR").added());
            assertEquals(List.of(BileMessages.COMMAND_LOAD.id()), report.merged().get("fr_FR").updated());
            String merged = Files.readString(folder.toPath().resolve("languages/fr_FR.toml"));
            assertTrue(merged.startsWith("# fr v2\n"));
            assertTrue(merged.contains("Retirer local"));
            assertFalse(merged.contains("Retirer v2"));
            assertEquals("Charger v2", LanguageAudience.call(player,
                    () -> localization.text(BileMessages.COMMAND_LOAD).plain()));
            assertEquals("Recharger v2", LanguageAudience.call(player,
                    () -> localization.text(BileMessages.COMMAND_RELOAD).plain()));
            assertEquals(pirateContent, Files.readString(pirate));
            assertEquals(LoopbackRepository.REVISION_TWO,
                    localization.upstreamStatus().orElseThrow().revision().orElseThrow());

            LanguageUpstreamSync.SyncReport unchanged = localization.updateAction().update().get(10L, TimeUnit.SECONDS);

            assertTrue(unchanged.unchanged());
            assertTrue(unchanged.changedLocales().isEmpty());
            assertEquals(merged, Files.readString(folder.toPath().resolve("languages/fr_FR.toml")));
        } finally {
            repository.close();
        }
    }

    @Test
    public void customLocaleMetaProvidesDisplayNameAndBaseFallback() throws Exception {
        localization.close();
        File folder = temporaryFolder.newFolder("meta-base");
        Path languages = folder.toPath().resolve("languages");
        Files.createDirectories(languages);
        Files.writeString(languages.resolve("de_DE.toml"),
                "[command]\nload = \"Laden\"\nunload = \"Entladen\"\n");
        Files.writeString(languages.resolve("de_AT.toml"),
                "[meta]\nname = \"Österreichisch\"\nbase = \"de_DE\"\n\n[command]\nload = \"Laden, Oida\"\n");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        UUID player = UUID.randomUUID();

        localization.languageService().selectPlayer(player, "de_AT").get(5L, TimeUnit.SECONDS);

        assertTrue(localization.availableLocales().contains("de_AT"));
        assertEquals("Österreichisch", localization.localeDisplayName("de_AT"));
        assertEquals("Österreichisch", localization.displayNames().displayName("de_AT"));
        assertNull(localization.displayNames().displayName("de_DE"));
        assertEquals(VolmitLocales.displayName("de_DE").orElseThrow(), localization.localeDisplayName("de_DE"));
        assertEquals("de_DE", localization.meta("de_AT").base().orElseThrow());
        assertEquals("Laden, Oida", LanguageAudience.call(player,
                () -> localization.text(BileMessages.COMMAND_LOAD).plain()));
        assertEquals("Entladen", LanguageAudience.call(player,
                () -> localization.text(BileMessages.COMMAND_UNLOAD).plain()));
        assertEquals(BileMessages.COMMAND_RELOAD.english(), LanguageAudience.call(player,
                () -> localization.text(BileMessages.COMMAND_RELOAD).plain()));

        localization.prepare("pirate");
        LanguageMeta generated = LanguageMeta.parse(Files.readString(languages.resolve("pirate.toml")));

        assertEquals("pirate", generated.name().orElseThrow());
        assertEquals("en_US", generated.base().orElseThrow());
        assertEquals("pirate", localization.localeDisplayName("pirate"));
        assertTrue(localization.availableLocales().contains("pirate"));
    }

    @Test
    public void englishReferenceGainsNewCatalogKeysWhileKeepingEdits() throws Exception {
        localization.close();
        File folder = temporaryFolder.newFolder("english-merge");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        localization.close();
        Path english = folder.toPath().resolve("languages/en_US.toml");
        Path sidecar = folder.toPath().resolve("languages/.upstream/en_US.toml");
        String olderReference = TomlLanguageEditor.remove(Files.readString(sidecar),
                BileMessages.COMMAND_LOAD.id()).content();
        Files.writeString(sidecar, olderReference);
        Files.writeString(english, TomlLanguageEditor.upsert(olderReference, BileMessages.COMMAND_UNLOAD.id(),
                new TextValue("Operator unload")).content());

        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        Map<String, String> values = TomlLanguageParser.parseText(Files.readString(english));

        assertTrue(values.containsKey(BileMessages.COMMAND_LOAD.id()));
        assertEquals("Operator unload", values.get(BileMessages.COMMAND_UNLOAD.id()));
        assertEquals("Operator unload", localization.text(BileMessages.COMMAND_UNLOAD).plain());
        assertTrue(TomlLanguageParser.parseText(Files.readString(sidecar)).containsKey(BileMessages.COMMAND_LOAD.id()));
        assertEquals(LanguageMeta.empty(), localization.meta("en_US"));
    }

    @Test
    public void playerChoiceUsesInstalledLocaleAndSurvivesRestart() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("player-choice", "de_DE");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        UUID player = UUID.randomUUID();
        String english = localization.text(BileMessages.COMMAND_LOAD).plain();
        localization.languageService().selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
        String translated = LanguageAudience.call(player, () -> localization.text(BileMessages.COMMAND_LOAD).plain());
        assertFalse(english.equals(translated));
        localization.close();
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        assertEquals("de_DE", localization.languageService().playerLocale(player).orElseThrow());
    }

    @Test
    public void serverChoicePersistsAndAppliesPreparedLocale() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("server-choice", "de_DE");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        localization.languageService().selectDefault("de_DE").get(5L, TimeUnit.SECONDS);
        assertEquals("de_DE", localization.activeLocale());
        assertEquals("de_DE", YamlConfiguration.loadConfiguration(new File(folder, "biletools.yml"))
                .getString("language"));
    }

    @Test
    public void personalLocaleEditsRefreshTitlesAndMissingEntriesWithoutChangingTheDefault() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("personal-hotload", "de_DE");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US",
                SilentFileWatcher::new, BileLocalization::readLanguageContent);
        UUID player = UUID.randomUUID();
        localization.languageService().selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
        MessageArgs arguments = MessageArgs.builder().untrusted("plugin", "BileTools")
                .untrusted("section", "Sprachen").build();
        String english = localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain();
        localization.update(0L);
        Path translated = folder.toPath().resolve("languages/de_DE.toml");
        String original = Files.readString(translated);
        Files.writeString(translated, TomlLanguageEditor.upsert(original,
                BukkitLanguageMessages.EDITOR_TITLE.id(), new TextValue("Live {plugin} › {section}")).content());
        awaitPersonalTitle(player, arguments, "Live BileTools › Sprachen", TimeUnit.SECONDS.toNanos(3L));
        assertEquals(english, localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain());
        Files.writeString(translated, original.replace("title = \"{plugin} › {section}\"", ""));
        awaitPersonalTitle(player, arguments, "BileTools › Sprachen", TimeUnit.SECONDS.toNanos(6L));
        assertEquals("de_DE", localization.languageService().playerLocale(player).orElseThrow());
        assertEquals("en_US", localization.activeLocale());
    }

    @Test
    public void malformedPersonalLocaleRetainsItsLastMessagesUntilRepaired() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("personal-invalid-hotload", "de_DE");
        Path translated = folder.toPath().resolve("languages/de_DE.toml");
        String original = TomlLanguageEditor.upsert(Files.readString(translated),
                BukkitLanguageMessages.EDITOR_TITLE.id(), new TextValue("Saved {plugin} › {section}")).content();
        Files.writeString(translated, original);
        AtomicInteger translatedReads = new AtomicInteger();
        AtomicInteger rejectedEdits = new AtomicInteger();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage().contains("retained the previous de_DE messages")) {
                    rejectedEdits.incrementAndGet();
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        localization = new BileLocalization(folder, logger, "en_US",
                SilentFileWatcher::new, file -> {
                    if (file.getName().equals("de_DE.toml")) {
                        translatedReads.incrementAndGet();
                    }
                    return BileLocalization.readLanguageContent(file);
                });
        UUID player = UUID.randomUUID();
        localization.languageService().selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
        MessageArgs arguments = MessageArgs.builder().untrusted("plugin", "BileTools")
                .untrusted("section", "Sprachen").build();
        localization.update(0L);
        Files.writeString(translated, "[unterminated");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (rejectedEdits.get() == 0 && System.nanoTime() < deadline) {
            localization.update(TimeUnit.SECONDS.toNanos(3L));
            Thread.sleep(10L);
        }
        assertEquals(1, translatedReads.get());
        assertEquals(1, rejectedEdits.get());
        assertEquals("Saved BileTools › Sprachen", LanguageAudience.call(player,
                () -> localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain()));
        assertEquals("de_DE", localization.languageService().playerLocale(player).orElseThrow());
        Files.writeString(translated, TomlLanguageEditor.upsert(original,
                BukkitLanguageMessages.EDITOR_TITLE.id(), new TextValue("Repaired {plugin} › {section}")).content());
        awaitPersonalTitle(player, arguments, "Repaired BileTools › Sprachen", TimeUnit.SECONDS.toNanos(6L));
    }

    @Test
    public void openingTheEditorDoesNotDiscardAnAutomaticLocaleRefresh() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("personal-editor-hotload", "de_DE");
        CountDownLatch captured = new CountDownLatch(1);
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US",
                SilentFileWatcher::new, file -> {
                    byte[] content = BileLocalization.readLanguageContent(file);
                    if (file.getName().equals("de_DE.toml")) {
                        captured.countDown();
                    }
                    return content;
                });
        UUID player = UUID.randomUUID();
        localization.languageService().selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
        MessageArgs arguments = MessageArgs.builder().untrusted("plugin", "BileTools")
                .untrusted("section", "Sprachen").build();
        localization.update(0L);
        Path translated = folder.toPath().resolve("languages/de_DE.toml");
        Files.writeString(translated, TomlLanguageEditor.upsert(Files.readString(translated),
                BukkitLanguageMessages.EDITOR_TITLE.id(), new TextValue("Live {plugin} › {section}")).content());
        localization.update(TimeUnit.SECONDS.toNanos(3L));
        assertTrue(captured.await(5L, TimeUnit.SECONDS));
        localization.editorOptions().loader().load("de_DE");
        awaitPersonalTitle(player, arguments, "Live BileTools › Sprachen", TimeUnit.SECONDS.toNanos(3L));
    }

    @Test
    public void deletedPersonalLocaleUsesEnglishWithoutChangingTheSavedChoice() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("personal-deleted-hotload", "de_DE");
        Path translated = folder.toPath().resolve("languages/de_DE.toml");
        Files.writeString(translated, TomlLanguageEditor.upsert(Files.readString(translated),
                BukkitLanguageMessages.EDITOR_TITLE.id(), new TextValue("Saved {plugin} › {section}")).content());
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US",
                SilentFileWatcher::new, BileLocalization::readLanguageContent);
        UUID player = UUID.randomUUID();
        localization.languageService().selectPlayer(player, "de_DE").get(5L, TimeUnit.SECONDS);
        MessageArgs arguments = MessageArgs.builder().untrusted("plugin", "BileTools")
                .untrusted("section", "Sprachen").build();
        assertEquals("Saved BileTools › Sprachen", LanguageAudience.call(player,
                () -> localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain()));
        localization.update(0L);
        Files.delete(translated);
        awaitPersonalTitle(player, arguments, "BileTools › Sprachen", TimeUnit.SECONDS.toNanos(3L));
        assertEquals("de_DE", localization.languageService().playerLocale(player).orElseThrow());
        assertEquals("en_US", localization.activeLocale());
        assertFalse(Files.exists(translated));
        assertTrue(Files.readString(folder.toPath().resolve("languages/language-preferences.properties"))
                .contains(player + "=de_DE"));
    }

    @Test
    public void appliesDirectTomlEditWithNamedArguments() throws Exception {
        write(BileMessages.LOAD_SUCCESS, new TextValue("&b{file} -> {plugin} ({milliseconds})"));
        assertTrue(localization.reload());
        ComponentText rendered = localization.text(BileMessages.LOAD_SUCCESS, MessageArgs.builder()
                .untrusted("plugin", "Demo").untrusted("file", "Demo.jar")
                .untrusted("milliseconds", 12).build());
        assertEquals("Demo.jar -> Demo (12)", rendered.plain());
    }

    @Test
    public void invalidEntryFallsBackToEnglishWhileValidMessagesStayActive() throws Exception {
        write(BileMessages.PERMISSION_DENIED, new TextValue("Allowed only with {permission}"));
        assertTrue(localization.reload());
        MessageArgs arguments = MessageArgs.builder().untrusted("permission", "bile.use").build();
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());
        String content = Files.readString(localization.languageFile().toPath());
        TomlLanguageEditor.EditResult invalid = TomlLanguageEditor.upsert(content,
                BileMessages.PERMISSION_DENIED.id(), new TextValue("Missing its named argument"));
        Files.writeString(localization.languageFile().toPath(), invalid.content());
        write(DirectorHelpMessages.BACK, new TextValue("Zurück"));
        assertTrue(localization.reload());
        assertEquals(BileLocalization.english(BileMessages.PERMISSION_DENIED, arguments).plain(),
                localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());
        assertEquals("Zurück", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
    }

    @Test
    public void resolvesDirectorLabelsAndDoesNotRenderUntrustedFormatting() throws Exception {
        write(DirectorHelpMessages.BACK, new TextValue("&aZurück"));
        assertTrue(localization.reload());
        assertEquals("Zurück", localization.directorResolver().resolve(DirectorHelpMessages.BACK));
        ComponentText rendered = localization.text(BileMessages.PLUGIN_NOT_FOUND,
                MessageArgs.builder().untrusted("plugin", "<click:run_command:'/op @s'>Bad</click>").build());
        assertTrue(rendered.plain().contains("<click:run_command:'/op @s'>Bad</click>"));
    }

    @Test
    public void exactFallbackDetectsLanguageEditWithoutNativeEvent() throws Exception {
        localization.close();
        localization = new BileLocalization(temporaryFolder.newFolder("exact-fallback"),
                Logger.getAnonymousLogger(), "en_US", SilentFileWatcher::new,
                BileLocalization::readLanguageContent);
        localization.update(0L);
        Path languagePath = localization.languageFile().toPath();
        FileTime modified = Files.getLastModifiedTime(languagePath);
        write(BileMessages.COMMAND_LOAD, new TextValue("Exact fallback edit"));
        Files.setLastModifiedTime(languagePath, modified);
        awaitText("Exact fallback edit", TimeUnit.MILLISECONDS.toNanos(2_500L));
    }

    @Test
    public void idleFallbackReadsOnlyAtReconciliationDeadline() throws Exception {
        localization.close();
        AtomicInteger reads = new AtomicInteger();
        localization = new BileLocalization(temporaryFolder.newFolder("idle-language"),
                Logger.getAnonymousLogger(), "en_US", SilentFileWatcher::new, file -> {
                    reads.incrementAndGet();
                    return BileLocalization.readLanguageContent(file);
                });
        int startupReads = reads.get();
        localization.update(0L);
        localization.update(TimeUnit.MILLISECONDS.toNanos(2_499L));
        assertEquals(startupReads, reads.get());
        localization.update(TimeUnit.MILLISECONDS.toNanos(2_500L));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline && reads.get() < startupReads + 1) {
            Thread.sleep(10L);
        }
        assertEquals(startupReads + 1, reads.get());
    }

    private void write(MessageKey key, MessageValue value) throws Exception {
        String content = Files.readString(localization.languageFile().toPath(), StandardCharsets.UTF_8);
        TomlLanguageEditor.EditResult edited = TomlLanguageEditor.upsert(content, key.id(), value);
        Files.writeString(localization.languageFile().toPath(), edited.content(), StandardCharsets.UTF_8);
    }

    private File installedLocaleFolder(String name, String locale) throws Exception {
        File folder = temporaryFolder.newFolder(name);
        Path languages = folder.toPath().resolve("languages");
        Files.createDirectories(languages);
        Files.copy(Path.of("src/main/resources/languages", locale + ".toml"),
                languages.resolve(locale + ".toml"));
        return folder;
    }

    private void awaitText(String expected, long nowNanos) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            localization.update(nowNanos);
            if (expected.equals(localization.text(BileMessages.COMMAND_LOAD).plain())) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, localization.text(BileMessages.COMMAND_LOAD).plain());
    }

    private void awaitPersonalTitle(UUID player, MessageArgs arguments, String expected, long nowNanos) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            localization.update(nowNanos);
            String actual = LanguageAudience.call(player,
                    () -> localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain());
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, LanguageAudience.call(player,
                () -> localization.text(BukkitLanguageMessages.EDITOR_TITLE, arguments).plain()));
    }

    private static final class LoopbackRepository {
        private static final String REVISION_ONE = "1111111111111111111111111111111111111111";
        private static final String REVISION_TWO = "2222222222222222222222222222222222222222";

        private final HttpServer server;
        private final AtomicReference<String> revision = new AtomicReference<>(REVISION_ONE);
        private final Map<String, String> files = new ConcurrentHashMap<>();

        private LoopbackRepository() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/", this::api);
            server.createContext("/raw/", this::raw);
            server.start();
        }

        private BileLocalization localization(File folder) {
            URI root = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            Logger logger = Logger.getAnonymousLogger();
            logger.setUseParentHandlers(false);
            return new BileLocalization(folder, logger, "en_US", new BileLocalization.UpstreamEndpoints(
                    root.resolve("api/"), root.resolve("raw/"), Duration.ZERO));
        }

        private void close() {
            server.stop(0);
        }

        private void api(HttpExchange exchange) throws IOException {
            String etag = "\"" + revision.get() + "\"";
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("ETag", etag);
            byte[] response = ("[{\"sha\":\"" + revision.get() + "\"}]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private void raw(HttpExchange exchange) throws IOException {
            String[] parts = exchange.getRequestURI().getPath().split("/");
            String content = files.get(parts[4] + "/" + parts[parts.length - 1].replace(".toml", ""));
            if (content == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] response = content.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }

    private static final class SilentFileWatcher extends FileWatcher {
        private SilentFileWatcher(File file) {
            super(file, false);
        }

        @Override
        public boolean checkModifiedEvents() {
            return false;
        }
    }
}
