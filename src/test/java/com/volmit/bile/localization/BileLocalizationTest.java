package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.help.DirectorHelpMessages;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeMessages;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.md_5.bungee.api.ChatColor;
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
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertArrayEquals;
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
        localization = new BileLocalization(temporaryFolder.newFolder(), logger, "en_US");
    }

    @After
    public void tearDown() {
        localization.close();
    }

    @Test
    public void editorPreservesPluralFormsAndUnrelatedMessages() throws Exception {
        PluginLanguageEditor.Options editor = localization.editorOptions();
        LocalizationSnapshot original = editor.loader().load("en_US");
        TextValue text = new TextValue("Edited root");
        editor.writer().write(new PluginLanguageEditor.Edit("en_US", BileMessages.COMMAND_ROOT.id(),
                original.value(BileMessages.COMMAND_ROOT), text));
        PluralValue existing = (PluralValue) original.value(BileMessages.REMOTE_DEPLOYED);
        Map<String, String> forms = new LinkedHashMap<>();
        for (Map.Entry<String, String> form : existing.forms().entrySet()) {
            forms.put(form.getKey(), form.getValue() + " edited");
        }
        PluralValue replacement = new PluralValue(forms);
        editor.writer().write(new PluginLanguageEditor.Edit("en_US", BileMessages.REMOTE_DEPLOYED.id(),
                existing, replacement));

        LocalizationSnapshot reloaded = editor.loader().load("en_US");
        assertEquals(replacement, reloaded.value(BileMessages.REMOTE_DEPLOYED));
        assertEquals(text, reloaded.value(BileMessages.COMMAND_ROOT));
    }

    @Test
    public void editorPersistsEnglishAndPreservesGlobalOverrides() throws Exception {
        YamlConfiguration overrides = loadLanguageFile();
        overrides.set("messages." + BileMessages.COMMAND_ROOT.id(), "Global root");
        overrides.save(localization.languageFile());
        PluginLanguageEditor.Options editor = localization.editorOptions();
        MessageValue original = editor.loader().load("en_US").value(BileMessages.COMMAND_ROOT);
        byte[] global = Files.readAllBytes(localization.languageFile().toPath());
        TextValue replacement = new TextValue("Edited command root");

        LocalizationSnapshot edited = editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, replacement));

        assertEquals(replacement, edited.value(BileMessages.COMMAND_ROOT));
        assertEquals(replacement, localization.snapshot().value(BileMessages.COMMAND_ROOT));
        assertEquals(replacement, editor.loader().load("en_US").value(BileMessages.COMMAND_ROOT));
        assertArrayEquals(global, Files.readAllBytes(localization.languageFile().toPath()));
        assertTrue(Files.isRegularFile(localization.languageFile().toPath().getParent()
                .resolve("languages/overrides/en_US.yml")));
        assertTrue(localization.reload());
        assertEquals(replacement, localization.snapshot().value(BileMessages.COMMAND_ROOT));
    }

    @Test
    public void editorLeavesActiveSelectionUnchangedForAnotherLocale() throws Exception {
        Path data = localization.languageFile().toPath().getParent();
        Files.createDirectories(data.resolve("languages"));
        Files.writeString(data.resolve("languages/fr_FR.yml"), "locale: fr_FR\nmessages:\n  bile.command.root: Racine\n");
        PluginLanguageEditor.Options editor = localization.editorOptions();
        MessageValue english = localization.snapshot().value(BileMessages.COMMAND_ROOT);
        MessageValue french = editor.loader().load("fr_FR").value(BileMessages.COMMAND_ROOT);
        TextValue replacement = new TextValue("Racine modifiee");

        editor.writer().write(new PluginLanguageEditor.Edit("fr_FR", BileMessages.COMMAND_ROOT.id(), french, replacement));

        assertEquals("en_US", localization.activeLocale());
        assertEquals(english, localization.snapshot().value(BileMessages.COMMAND_ROOT));
        assertEquals(replacement, editor.loader().load("fr_FR").value(BileMessages.COMMAND_ROOT));
    }

    @Test
    public void editorRejectsInvalidAndStaleValuesWithoutChangingFiles() throws Exception {
        PluginLanguageEditor.Options editor = localization.editorOptions();
        MessageValue original = editor.loader().load("en_US").value(BileMessages.COMMAND_ROOT);
        Path file = localization.languageFile().toPath().getParent().resolve("languages/overrides/en_US.yml");

        assertThrows(IllegalArgumentException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("{unexpected}"))));
        assertFalse(Files.exists(file));
        editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("First edit")));
        byte[] first = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> editor.writer().write(new PluginLanguageEditor.Edit(
                "en_US", BileMessages.COMMAND_ROOT.id(), original, new TextValue("Stale edit"))));
        assertArrayEquals(first, Files.readAllBytes(file));
    }

    @Test
    public void generatesOverridesOnlyFileWithEnglishInTheTypedCatalog() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(localization.languageFile());

        assertFalse(yaml.contains("locale"));
        assertTrue(yaml.contains("messages"));
        assertEquals("Load a plugin jar from the plugins directory", BileMessages.COMMAND_LOAD.english());
    }

    @Test
    public void everyDownloadableLocaleFullyCoversTheTypedCatalog() throws Exception {
        for (String locale : VolmitLocales.nonEnglish()) {
            localization.close();
            localization = new BileLocalization(
                    installedLocaleFolder("locale-" + locale, locale),
                    Logger.getAnonymousLogger(),
                    locale);
            for (MessageKey key : localization.snapshot().catalog().keys()) {
                assertEquals(locale + ":" + key.id(), locale, localization.snapshot().sourceLocale(key));
            }
        }
    }

    @Test
    public void downloadableResourceSetExactlyMatchesSharedManifest() throws Exception {
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
    public void playerChoiceUsesInstalledLocaleAndSurvivesRestart() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("player-choice", "de_DE");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        UUID player = UUID.randomUUID();
        String english = localization.text(BileMessages.COMMAND_LOAD).plain();

        localization.languageService().selectPlayer(player, "de_DE").get(5, TimeUnit.SECONDS);
        String translated = LanguageAudience.call(player, () -> localization.text(BileMessages.COMMAND_LOAD).plain());
        assertFalse(english.equals(translated));
        assertEquals(english, localization.text(BileMessages.COMMAND_LOAD).plain());
        localization.close();
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        assertEquals("de_DE", localization.languageService().playerLocale(player).orElseThrow());
        localization.languageService().clearPlayer(player).get(5, TimeUnit.SECONDS);
        assertEquals(english, LanguageAudience.call(player, () -> localization.text(BileMessages.COMMAND_LOAD).plain()));
    }

    @Test
    public void serverChoicePersistsAndAppliesPreparedLocale() throws Exception {
        localization.close();
        File folder = installedLocaleFolder("server-choice", "de_DE");
        localization = new BileLocalization(folder, Logger.getAnonymousLogger(), "en_US");
        localization.languageService().selectDefault("de_DE").get(5, TimeUnit.SECONDS);
        assertEquals("de_DE", localization.activeLocale());
        assertEquals("de_DE", YamlConfiguration.loadConfiguration(new File(folder, "biletools.yml")).getString("language"));
        assertEquals("de_DE", localization.snapshot().sourceLocale(BileMessages.COMMAND_LOAD));
    }

    @Test
    public void appliesExternalOverrideWithNamedArguments() throws Exception {
        localization.close();
        localization = new BileLocalization(
                installedLocaleFolder("de_DE-overrides", "de_DE"),
                Logger.getAnonymousLogger(),
                "de_DE");
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.LOAD_SUCCESS.id(), "&b{file} -> {plugin} ({milliseconds})");
        yaml.save(localization.languageFile());

        assertTrue(localization.reload());
        ComponentText rendered = localization.text(
                BileMessages.LOAD_SUCCESS,
                MessageArgs.builder()
                        .untrusted("plugin", "Demo")
                        .untrusted("file", "Demo.jar")
                        .untrusted("milliseconds", 12)
                        .build()
        );

        assertEquals(ChatColor.AQUA + "Demo.jar -> Demo (12)", rendered.legacy());
        assertEquals("Demo.jar -> Demo (12)", rendered.plain());
        assertEquals("de_DE", localization.activeLocale());
    }

    @Test
    public void rejectsInvalidReloadAndRetainsLastGoodSnapshot() throws Exception {
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.PERMISSION_DENIED.id(), "Allowed only with {permission}");
        yaml.save(localization.languageFile());
        assertTrue(localization.reload());

        MessageArgs arguments = MessageArgs.builder().untrusted("permission", "bile.use").build();
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());

        yaml.set("messages." + BileMessages.PERMISSION_DENIED.id(), "Missing its named argument");
        yaml.save(localization.languageFile());

        assertFalse(localization.reload());
        assertEquals("Allowed only with bile.use", localization.text(BileMessages.PERMISSION_DENIED, arguments).plain());
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
        ComponentText rendered = localization.text(
                BileMessages.PLUGIN_NOT_FOUND,
                MessageArgs.builder().untrusted("plugin", "&cBad" + ChatColor.DARK_RED + "Name").build()
        );
        assertTrue(rendered.plain().contains("&cBadName"));
        assertFalse(rendered.plain().contains(String.valueOf(ChatColor.DARK_RED)));
    }

    @Test
    public void insertedArgumentsAreNeverReprocessedAsLaterSentinels() {
        ComponentText rendered = localization.text(
                BileMessages.LOAD_SUCCESS,
                MessageArgs.builder()
                        .untrusted("plugin", "\uE0001\uE001")
                        .untrusted("file", "replacement.jar")
                        .untrusted("milliseconds", 12)
                        .build()
        );

        assertTrue(rendered.plain().contains("\uE0001\uE001"));
        assertTrue(rendered.plain().contains("replacement.jar"));
    }

    @Test
    public void untrustedArgumentsCannotInjectLegacyMiniMessageOrRgbFormatting() throws Exception {
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.PLUGIN_NOT_FOUND.id(), "{plugin}");
        yaml.save(localization.languageFile());
        assertTrue(localization.reload());
        String payload = "&cAmp \u00a74Section <click:run_command:'/op @s'>Click</click> "
                + "<hover:show_text:'bad'>Hover</hover> <#ff0000>RGB</#ff0000> "
                + "&#00ff00 &x&0&0&f&f&0&0Hex";

        ComponentText rendered = localization.text(
                BileMessages.PLUGIN_NOT_FOUND,
                MessageArgs.builder().untrusted("plugin", payload).build()
        );
        Component component = MiniMessage.miniMessage().deserialize(rendered.miniMessage());

        assertEquals(payload.replace("\u00a74", ""), rendered.plain());
        assertFalse(rendered.legacy().contains("\u00a7"));
        assertFalse(hasFormattingOrEvents(component));
    }

    @Test
    public void automaticReloadsAreQueuedBehindThreeSecondCooldown() throws Exception {
        long originalModified = Files.getLastModifiedTime(localization.languageFile().toPath()).toMillis();
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.COMMAND_LOAD.id(), "First automatic override");
        yaml.save(localization.languageFile());
        Files.setLastModifiedTime(localization.languageFile().toPath(), FileTime.fromMillis(originalModified + 1_000L));
        awaitText("First automatic override", 100L);

        yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.COMMAND_LOAD.id(), "Second automatic override");
        yaml.save(localization.languageFile());
        Files.setLastModifiedTime(localization.languageFile().toPath(), FileTime.fromMillis(originalModified + 2_000L));
        settleUpdates(TimeUnit.SECONDS.toNanos(2L));
        assertEquals("First automatic override", localization.text(BileMessages.COMMAND_LOAD).plain());

        awaitText("Second automatic override", 100L + TimeUnit.SECONDS.toNanos(3L));
    }

    @Test
    public void idleEventFirstUpdatesReadOnlyAtExactReconciliationDeadline() throws Exception {
        localization.close();
        AtomicInteger reads = new AtomicInteger();
        localization = new BileLocalization(
                temporaryFolder.newFolder("idle-language"),
                Logger.getAnonymousLogger(),
                "en_US",
                SilentFileWatcher::new,
                file -> {
                    reads.incrementAndGet();
                    return BileLocalization.readLanguageContent(file);
                }
        );
        int startupReads = reads.get();

        localization.update(0L);
        localization.update(TimeUnit.SECONDS.toNanos(1L));
        localization.update(TimeUnit.MILLISECONDS.toNanos(2_499L));

        assertEquals(startupReads, reads.get());
        localization.update(TimeUnit.MILLISECONDS.toNanos(2_500L));
        awaitReadCount(reads, startupReads + 1);
    }

    @Test
    public void exactFallbackDetectsSameMetadataLanguageEditWithoutNativeEvent() throws Exception {
        localization.close();
        localization = new BileLocalization(
                temporaryFolder.newFolder("same-metadata-language"),
                Logger.getAnonymousLogger(),
                "en_US",
                SilentFileWatcher::new,
                BileLocalization::readLanguageContent
        );
        localization.update(0L);
        Path languagePath = localization.languageFile().toPath();
        FileTime originalModified = Files.getLastModifiedTime(languagePath);
        YamlConfiguration yaml = loadLanguageFile();
        yaml.set("messages." + BileMessages.COMMAND_LOAD.id(), "Same metadata override");
        yaml.save(localization.languageFile());
        Files.setLastModifiedTime(languagePath, originalModified);

        awaitText("Same metadata override", TimeUnit.MILLISECONDS.toNanos(2_500L));
    }

    @Test
    public void automaticReloadAppliesTheCapturedImmutableBytes() throws Exception {
        localization.close();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        localization = new BileLocalization(
                temporaryFolder.newFolder("immutable-language-snapshot"),
                Logger.getAnonymousLogger(),
                "en_US",
                SilentFileWatcher::new,
                file -> {
                    byte[] content = BileLocalization.readLanguageContent(file);
                    if (reads.incrementAndGet() > 1) {
                        captured.countDown();
                        try {
                            if (!release.await(5L, TimeUnit.SECONDS)) {
                                throw new IOException("Timed out while testing an immutable language snapshot");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while testing an immutable language snapshot", interrupted);
                        }
                    }
                    return content;
                }
        );
        localization.update(0L);
        Path languagePath = localization.languageFile().toPath();
        Files.writeString(languagePath, "messages:\n  bile:\n    command:\n      load: Captured override\n", StandardCharsets.UTF_8);

        localization.update(TimeUnit.MILLISECONDS.toNanos(2_500L));
        assertTrue(captured.await(5L, TimeUnit.SECONDS));
        Files.writeString(languagePath, "messages:\n  bile:\n    command:\n      load: Newer override\n", StandardCharsets.UTF_8);
        release.countDown();

        awaitText("Captured override", TimeUnit.MILLISECONDS.toNanos(2_500L));
        assertEquals("messages:\n  bile:\n    command:\n      load: Newer override\n", Files.readString(languagePath, StandardCharsets.UTF_8));
    }

    @Test
    public void automaticMissingSnapshotDoesNotRecreateTheLanguageFile() throws Exception {
        localization.close();
        localization = new BileLocalization(
                temporaryFolder.newFolder("missing-language-snapshot"),
                Logger.getAnonymousLogger(),
                "en_US",
                SilentFileWatcher::new,
                BileLocalization::readLanguageContent
        );
        localization.update(0L);
        Path languagePath = localization.languageFile().toPath();
        Files.delete(languagePath);

        settleUpdates(TimeUnit.MILLISECONDS.toNanos(2_500L));

        assertFalse(Files.exists(languagePath));
        assertEquals("en_US", localization.activeLocale());
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

    private File installedLocaleFolder(String name, String locale) throws Exception {
        File folder = temporaryFolder.newFolder(name);
        Path languages = folder.toPath().resolve("languages");
        Files.createDirectories(languages);
        Files.copy(Path.of("src/main/resources/languages", locale + ".yml"), languages.resolve(locale + ".yml"));
        return folder;
    }

    private YamlConfiguration loadLanguageFile() throws Exception {
        File file = localization.languageFile();
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file);
        return yaml;
    }

    private void awaitLocale(String expected, long nowNanos) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            localization.update(nowNanos);
            if (expected.equals(localization.activeLocale())) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, localization.activeLocale());
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

    private static boolean hasFormattingOrEvents(Component component) {
        if (component.color() != null
                || component.clickEvent() != null
                || component.hoverEvent() != null
                || component.hasDecoration(TextDecoration.BOLD)
                || component.hasDecoration(TextDecoration.ITALIC)
                || component.hasDecoration(TextDecoration.UNDERLINED)
                || component.hasDecoration(TextDecoration.STRIKETHROUGH)
                || component.hasDecoration(TextDecoration.OBFUSCATED)) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasFormattingOrEvents(child)) {
                return true;
            }
        }
        return false;
    }

    private void settleUpdates(long nowNanos) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250L);
        while (System.nanoTime() < deadline) {
            localization.update(nowNanos);
            Thread.sleep(5L);
        }
    }

    private void awaitReadCount(AtomicInteger reads, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline && reads.get() < expected) {
            Thread.sleep(10L);
        }
        assertEquals(expected, reads.get());
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
