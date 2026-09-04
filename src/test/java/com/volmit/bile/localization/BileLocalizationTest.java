package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.LanguageAudience;
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
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertTrue(english.contains("{file}              Plugin jar filename"));
        assertTrue(english.contains("{milliseconds}      Load, unload, or reload duration in milliseconds"));
        assertTrue(english.contains("{new}               Newly saved language message value"));
        assertTrue(english.contains("{old}               Previous language message value"));
        assertTrue(english.contains("{permission}        Required permission node"));
        assertTrue(english.contains("{personal}          Personal locale when different from the server default"));
        assertTrue(english.contains("{setting}           Configuration setting name"));
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
    public void pluginRegistersItsCompactLanguageEditReceipt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/volmit/bile/BileTools.java"))
                .replace("\r\n", "\n");

        assertTrue(source.contains("this::renderLanguageEditFeedback"));
        assertTrue(source.contains("localization.singleLineText(sender, BileMessages.CHANGE_SAVED"));
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
            Map<String, MessageValue> values = BileTomlLanguageParser.parse(content, BileMessages.catalog());
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
    public void appliesDirectTomlEditWithNamedArguments() throws Exception {
        write(BileMessages.LOAD_SUCCESS, new TextValue("&b{file} -> {plugin} ({milliseconds})"));
        assertTrue(localization.reload());
        ComponentText rendered = localization.text(BileMessages.LOAD_SUCCESS, MessageArgs.builder()
                .untrusted("plugin", "Demo").untrusted("file", "Demo.jar")
                .untrusted("milliseconds", 12).build());
        assertEquals("Demo.jar -> Demo (12)", rendered.plain());
    }

    @Test
    public void rejectsInvalidReloadAndRetainsLastGoodSnapshot() throws Exception {
        write(BileMessages.PERMISSION_DENIED, new TextValue("Allowed only with {permission}"));
        assertTrue(localization.reload());
        MessageArgs arguments = MessageArgs.builder().untrusted("permission", "bile.use").build();
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());
        String content = Files.readString(localization.languageFile().toPath());
        TomlLanguageEditor.EditResult invalid = TomlLanguageEditor.upsert(content,
                BileMessages.PERMISSION_DENIED.id(), new TextValue("Missing its named argument"));
        Files.writeString(localization.languageFile().toPath(), invalid.content());
        assertFalse(localization.reload());
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());
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
