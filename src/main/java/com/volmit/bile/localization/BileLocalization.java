package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationIssue;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.MessageArgument;
import art.arcane.volmlib.util.localization.MessageArgumentKind;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.VolmitLocales;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BileLocalization {
    private static final long MAX_LANGUAGE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_REPORTED_ISSUES = 12;
    private static final MessageCatalog CATALOG = BileMessages.catalog();

    private final File languageFile;
    private final Logger logger;
    private final LocalizationManager manager;
    private final FileWatcher watcher;
    private volatile String activeLocale;

    public BileLocalization(File dataFolder, Logger logger) {
        this.languageFile = new File(dataFolder, "language.yml");
        this.logger = logger;
        this.manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
        this.activeLocale = CATALOG.englishLocale();
        ensureDefaultFile();
        reload();
        this.watcher = new FileWatcher(languageFile);
    }

    public String activeLocale() {
        return activeLocale;
    }

    public File languageFile() {
        return languageFile;
    }

    LocalizationSnapshot snapshot() {
        return manager.snapshot();
    }

    public void update() {
        if (watcher.checkModified()) {
            reload();
        }
    }

    public synchronized boolean reload() {
        if (!languageFile.exists()) {
            ensureDefaultFile();
        }

        LocalizationReloadResult result = manager.reload(this::loadCandidate);
        if (!result.applied()) {
            reportRejectedReload(result);
            return false;
        }

        activeLocale = result.current().overlays().isEmpty()
                ? CATALOG.englishLocale()
                : result.current().overlays().get(0).locale();
        return true;
    }

    public String text(TextKey key) {
        return text(key, MessageArgs.empty());
    }

    public String text(TextKey key, MessageArgs arguments) {
        return render(manager.snapshot().resolve(key, arguments));
    }

    public String text(PluralKey key, MessageArgs arguments) {
        return render(manager.snapshot().resolve(key, arguments));
    }

    public DirectorTextResolver directorResolver() {
        return (key, arguments) -> {
            MessageKey definition = CATALOG.key(key.id());
            if (!(definition instanceof TextKey textKey)) {
                return DirectorTextResolver.ENGLISH.resolve(key, arguments);
            }
            String rendered = text(textKey, arguments);
            String plain = ChatColor.stripColor(rendered);
            return plain == null ? DirectorTextResolver.ENGLISH.resolve(key, arguments) : plain;
        };
    }

    public static String english(TextKey key) {
        return english(key, MessageArgs.empty());
    }

    public static String english(TextKey key, MessageArgs arguments) {
        return renderTemplate(key.english(), arguments);
    }

    private LocalizationCandidate loadCandidate() throws Exception {
        if (!languageFile.isFile()) {
            throw new IllegalArgumentException("Language source is not a regular file: " + languageFile.getPath());
        }
        if (languageFile.length() > MAX_LANGUAGE_BYTES) {
            throw new IllegalArgumentException("Language source is too large: " + languageFile.getPath());
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(languageFile);
        String locale = yaml.getString("locale", CATALOG.englishLocale());
        if (locale == null || locale.isBlank()) {
            locale = CATALOG.englishLocale();
        }

        String selectedLocale = locale.trim();
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(languageFile.getPath(), selectedLocale);
        ConfigurationSection messages = yaml.getConfigurationSection("messages");
        if (messages != null) {
            appendMessages(messages, overlay);
        }

        List<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(overlay.build());
        LocaleOverlay bundled = loadBundledOverlay(selectedLocale);
        if (bundled != null) {
            overlays.add(bundled);
        }
        return new LocalizationCandidate(CATALOG, overlays, PluralSelector.oneOther());
    }

    private LocaleOverlay loadBundledOverlay(String locale) throws Exception {
        if (VolmitLocales.ENGLISH.equals(locale)) {
            return null;
        }

        String resourcePath = "/languages/" + locale + ".yml";
        InputStream input = BileLocalization.class.getResourceAsStream(resourcePath);
        if (input == null) {
            if (VolmitLocales.isBundled(locale)) {
                throw new IllegalArgumentException("Missing bundled language resource: " + resourcePath);
            }
            return null;
        }

        try (InputStream stream = input; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(reader);
            String declaredLocale = yaml.getString("locale");
            if (!locale.equals(declaredLocale)) {
                throw new IllegalArgumentException(resourcePath + " must declare locale: " + locale);
            }

            LocaleOverlay.Builder overlay = LocaleOverlay.builder(resourcePath, locale);
            ConfigurationSection messages = yaml.getConfigurationSection("messages");
            if (messages != null) {
                appendMessages(messages, overlay);
            }
            return overlay.build();
        }
    }

    private void appendMessages(ConfigurationSection messages, LocaleOverlay.Builder overlay) {
        Map<String, Map<String, String>> pluralForms = new LinkedHashMap<>();
        for (String path : messages.getKeys(true)) {
            if (messages.isConfigurationSection(path)) {
                continue;
            }

            Object value = messages.get(path);
            if (!(value instanceof String template)) {
                throw new IllegalArgumentException("Language value must be text: " + path);
            }

            MessageKey key = CATALOG.key(path);
            if (key instanceof TextKey) {
                overlay.text(path, template);
                continue;
            }

            int separator = path.lastIndexOf('.');
            String pluralId = separator < 0 ? "" : path.substring(0, separator);
            if (CATALOG.key(pluralId) instanceof PluralKey) {
                String category = path.substring(separator + 1);
                pluralForms.computeIfAbsent(pluralId, ignored -> new LinkedHashMap<>()).put(category, template);
                continue;
            }

            overlay.text(path, template);
        }

        for (Map.Entry<String, Map<String, String>> entry : pluralForms.entrySet()) {
            overlay.plural(entry.getKey(), entry.getValue());
        }
    }

    private void ensureDefaultFile() {
        if (languageFile.exists()) {
            return;
        }

        try {
            Files.createDirectories(languageFile.toPath().getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("locale", CATALOG.englishLocale());
            yaml.save(languageFile);
        } catch (Exception exception) {
            logger.log(Level.SEVERE, "Unable to create the default language file", exception);
        }
    }

    private void reportRejectedReload(LocalizationReloadResult result) {
        logger.severe("Rejected language reload; continuing with " + activeLocale + ".");
        List<LocalizationIssue> issues = result.validation().errors();
        for (int index = 0; index < Math.min(issues.size(), MAX_REPORTED_ISSUES); index++) {
            LocalizationIssue issue = issues.get(index);
            logger.severe(issue.source() + " [" + issue.key() + "]: " + issue.detail());
        }
        if (issues.size() > MAX_REPORTED_ISSUES) {
            logger.severe((issues.size() - MAX_REPORTED_ISSUES) + " additional language errors were omitted.");
        }
        if (result.failure() != null) {
            logger.log(Level.SEVERE, "Language reload failed", result.failure());
        }
    }

    private static String render(ResolvedText resolved) {
        return renderTemplate(resolved.template(), resolved.arguments());
    }

    private static String renderTemplate(String template, MessageArgs arguments) {
        String prepared = template;
        List<RenderedArgument> replacements = new ArrayList<>(arguments.size());
        int index = 0;
        for (MessageArgument argument : arguments.arguments().values()) {
            String token = "\uE000" + index + "\uE001";
            prepared = prepared.replace("{" + argument.name() + "}", token);
            replacements.add(new RenderedArgument(token, argument));
            index++;
        }

        return applyReplacements(ChatColor.translateAlternateColorCodes('&', prepared), replacements);
    }

    private static String applyReplacements(String rendered, List<RenderedArgument> replacements) {
        StringBuilder output = new StringBuilder(rendered.length());
        int cursor = 0;
        while (cursor < rendered.length()) {
            RenderedArgument match = null;
            for (RenderedArgument replacement : replacements) {
                if (rendered.startsWith(replacement.token(), cursor)) {
                    match = replacement;
                    break;
                }
            }
            if (match == null) {
                output.append(rendered.charAt(cursor));
                cursor++;
                continue;
            }

            MessageArgument argument = match.argument();
            String value = String.valueOf(argument.value());
            output.append(argument.kind() == MessageArgumentKind.TRUSTED
                    ? ChatColor.translateAlternateColorCodes('&', value)
                    : sanitizeUntrusted(value));
            cursor += match.token().length();
        }
        return output.toString();
    }

    private static String sanitizeUntrusted(String value) {
        String stripped = ChatColor.stripColor(value);
        return stripped == null ? "" : stripped.replace(String.valueOf(ChatColor.COLOR_CHAR), "");
    }

    private record RenderedArgument(String token, MessageArgument argument) {
    }
}
