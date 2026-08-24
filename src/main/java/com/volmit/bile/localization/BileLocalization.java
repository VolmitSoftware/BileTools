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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class BileLocalization implements AutoCloseable {
    private static final long MAX_LANGUAGE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_REPORTED_ISSUES = 12;
    private static final long AUTOMATIC_RELOAD_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long EXACT_RECONCILIATION_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(2_500L);
    private static final long IO_SHUTDOWN_MILLIS = 1_000L;
    private static final MessageCatalog CATALOG = BileMessages.catalog();

    private final File languageFile;
    private final String configuredLocale;
    private final Logger logger;
    private final LocalizationManager manager;
    private final FileWatcher watcher;
    private final SnapshotReader snapshotReader;
    private final ExecutorService automaticReloadIo;
    private final AtomicBoolean automaticReadInFlight = new AtomicBoolean();
    private final AtomicReference<AutomaticReadResult> completedAutomaticRead = new AtomicReference<>();
    private final Map<String, byte[]> bundledResources;
    private volatile String activeLocale;
    private byte[] appliedLanguageContent = new byte[0];
    private byte[] pendingAutomaticContent;
    private boolean automaticReadRequested;
    private boolean closed;
    private long automaticReadGeneration;
    private long nextAutomaticReloadNanos = Long.MIN_VALUE;
    private long nextExactReconciliationNanos = Long.MIN_VALUE;
    private String lastAutomaticReadFailure;

    public BileLocalization(File dataFolder, Logger logger, String configuredLocale) {
        this(dataFolder, logger, configuredLocale, FileWatcher::new, BileLocalization::readLanguageContent);
    }

    BileLocalization(File dataFolder,
                     Logger logger,
                     String configuredLocale,
                     FileWatcherFactory watcherFactory,
                     SnapshotReader snapshotReader) {
        this.languageFile = new File(dataFolder, "language.yml");
        this.configuredLocale = configuredLocale == null || configuredLocale.isBlank()
                ? CATALOG.englishLocale()
                : configuredLocale.trim();
        this.logger = Objects.requireNonNull(logger, "logger");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, PluralSelector.oneOther()));
        this.activeLocale = CATALOG.englishLocale();
        this.bundledResources = loadBundledResources();
        this.automaticReloadIo = Executors.newSingleThreadExecutor((Runnable task) -> {
            Thread thread = new Thread(task, "BileTools-Language-Hotload-IO");
            thread.setDaemon(true);
            return thread;
        });
        ensureDefaultFile();
        reload();
        try {
            this.watcher = Objects.requireNonNull(watcherFactory, "watcherFactory").create(languageFile);
        } catch (RuntimeException failure) {
            automaticReloadIo.shutdownNow();
            throw failure;
        }
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
        update(System.nanoTime());
    }

    synchronized void update(long nowNanos) {
        if (closed) {
            return;
        }

        if (nextExactReconciliationNanos == Long.MIN_VALUE) {
            nextExactReconciliationNanos = saturatingAdd(nowNanos, EXACT_RECONCILIATION_INTERVAL_NANOS);
        }

        boolean eventDetected = watcher.checkModifiedEvents();
        boolean reconciliationDue = nowNanos >= nextExactReconciliationNanos;
        if (reconciliationDue) {
            nextExactReconciliationNanos = saturatingAdd(nowNanos, EXACT_RECONCILIATION_INTERVAL_NANOS);
        }
        if (eventDetected || reconciliationDue) {
            queueAutomaticRead();
        }

        consumeAutomaticRead();
        if (automaticReadInFlight.get()
                || completedAutomaticRead.get() != null
                || pendingAutomaticContent == null
                || nowNanos < nextAutomaticReloadNanos) {
            return;
        }

        nextAutomaticReloadNanos = saturatingAdd(nowNanos, AUTOMATIC_RELOAD_INTERVAL_NANOS);
        byte[] candidate = pendingAutomaticContent;
        if (applyLanguageContent(candidate)) {
            appliedLanguageContent = candidate.clone();
            pendingAutomaticContent = null;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        automaticReadGeneration++;
        automaticReadRequested = false;
        pendingAutomaticContent = null;
        completedAutomaticRead.set(null);
        watcher.close();
        automaticReloadIo.shutdownNow();
        try {
            if (!automaticReloadIo.awaitTermination(IO_SHUTDOWN_MILLIS, TimeUnit.MILLISECONDS)) {
                logger.warning("Language hotload IO worker did not stop within one second");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while stopping the language hotload IO worker", interrupted);
        }
    }

    public synchronized boolean reload() {
        if (!languageFile.exists()) {
            ensureDefaultFile();
        }

        byte[] content;
        try {
            content = readSnapshotContent();
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Language reload could not capture a stable file snapshot", failure);
            return false;
        }

        if (!applyLanguageContent(content)) {
            return false;
        }

        automaticReadGeneration++;
        automaticReadRequested = false;
        pendingAutomaticContent = null;
        completedAutomaticRead.set(null);
        appliedLanguageContent = content.clone();
        nextAutomaticReloadNanos = Long.MIN_VALUE;
        return true;
    }

    private boolean applyLanguageContent(byte[] content) {
        byte[] immutableContent = content.clone();
        LocalizationReloadResult result = manager.reload(() -> loadCandidate(immutableContent));
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

    private LocalizationCandidate loadCandidate(byte[] content) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        try (InputStreamReader reader = new InputStreamReader(
                new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            yaml.load(reader);
        }
        if (yaml.contains("locale")) {
            throw new IllegalArgumentException(
                    "language.yml no longer accepts locale. Set language in biletools.yml, delete language.yml, and restart BileTools to regenerate the overrides-only file.");
        }
        String selectedLocale = configuredLocale;
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

    private void queueAutomaticRead() {
        if (closed) {
            return;
        }
        if (!automaticReadInFlight.compareAndSet(false, true)) {
            automaticReadRequested = true;
            return;
        }

        long generation = automaticReadGeneration;
        try {
            automaticReloadIo.execute(() -> captureAutomaticRead(generation));
        } catch (RejectedExecutionException rejected) {
            automaticReadInFlight.set(false);
            if (!closed) {
                automaticReadRequested = true;
            }
        }
    }

    private void captureAutomaticRead(long generation) {
        AutomaticReadResult result;
        try {
            result = new AutomaticReadResult(generation, readSnapshotContent(), null);
        } catch (NoSuchFileException missing) {
            result = new AutomaticReadResult(generation, null, null);
        } catch (IOException | RuntimeException failure) {
            result = new AutomaticReadResult(generation, null, failure);
        }
        completedAutomaticRead.set(result);
        automaticReadInFlight.set(false);
    }

    private byte[] readSnapshotContent() throws IOException {
        byte[] content = snapshotReader.read(languageFile);
        if (content == null) {
            throw new IOException("Language snapshot reader returned no content");
        }
        return content;
    }

    private void consumeAutomaticRead() {
        AutomaticReadResult result = completedAutomaticRead.getAndSet(null);
        if (result != null && result.generation() == automaticReadGeneration) {
            if (result.failure() == null && result.content() != null) {
                lastAutomaticReadFailure = null;
                pendingAutomaticContent = Arrays.equals(result.content(), appliedLanguageContent)
                        ? null
                        : result.content().clone();
            } else if (result.failure() == null) {
                lastAutomaticReadFailure = null;
            } else {
                String message = result.failure().getMessage();
                if (!Objects.equals(message, lastAutomaticReadFailure)) {
                    lastAutomaticReadFailure = message;
                    logger.log(Level.WARNING, "Language hotload could not capture a stable file snapshot", result.failure());
                }
            }
        }

        if (automaticReadRequested && !automaticReadInFlight.get()) {
            automaticReadRequested = false;
            queueAutomaticRead();
        }
    }

    static byte[] readLanguageContent(File file) throws IOException {
        BasicFileAttributes before = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        if (!before.isRegularFile()) {
            throw new IOException("Language source is not a regular file: " + file.getPath());
        }
        if (before.size() > MAX_LANGUAGE_BYTES) {
            throw new IOException("Language source is too large: " + file.getPath());
        }

        byte[] content;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            content = input.readNBytes((int) MAX_LANGUAGE_BYTES + 1);
        }
        if (content.length > MAX_LANGUAGE_BYTES) {
            throw new IOException("Language source is too large: " + file.getPath());
        }

        BasicFileAttributes after = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || content.length != after.size()) {
            throw new IOException("Language source changed while it was being read: " + file.getPath());
        }
        return content;
    }

    private LocaleOverlay loadBundledOverlay(String locale) throws Exception {
        if (VolmitLocales.ENGLISH.equals(locale)) {
            return null;
        }

        byte[] resource = bundledResources.get(locale);
        if (resource == null) {
            if (VolmitLocales.isBundled(locale)) {
                throw new IllegalArgumentException("Missing bundled language resource for " + locale);
            }
            return null;
        }

        String resourcePath = "/languages/" + locale + ".yml";
        try (InputStreamReader reader = new InputStreamReader(
                new ByteArrayInputStream(resource), StandardCharsets.UTF_8)) {
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

    private Map<String, byte[]> loadBundledResources() {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (String locale : VolmitLocales.nonEnglish()) {
            String resourcePath = "/languages/" + locale + ".yml";
            InputStream input = BileLocalization.class.getResourceAsStream(resourcePath);
            if (input == null) {
                throw new IllegalStateException("Missing bundled language resource: " + resourcePath);
            }
            try (InputStream stream = input) {
                byte[] content = stream.readNBytes((int) MAX_LANGUAGE_BYTES + 1);
                if (content.length > MAX_LANGUAGE_BYTES) {
                    throw new IllegalStateException("Bundled language resource is too large: " + resourcePath);
                }
                resources.put(locale, content);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot preload bundled language resource: " + resourcePath, exception);
            }
        }
        return Map.copyOf(resources);
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
            yaml.options().header(
                    "BileTools language overrides. Select the active language in biletools.yml.\n"
                            + "Add only message keys you want to replace under messages.");
            yaml.set("messages", Map.of());
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

    private long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
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

    interface FileWatcherFactory {
        FileWatcher create(File file);
    }

    interface SnapshotReader {
        byte[] read(File file) throws IOException;
    }

    private record AutomaticReadResult(long generation, byte[] content, Throwable failure) {
        private AutomaticReadResult {
            content = content == null ? null : content.clone();
        }
    }

    private record RenderedArgument(String token, MessageArgument argument) {
    }
}
