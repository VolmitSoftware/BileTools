package com.volmit.bile.localization;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LinesValue;
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
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralSelector;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.PluginLanguageEditor;
import art.arcane.volmlib.util.localization.PluginLanguageService;
import art.arcane.volmlib.util.localization.RemoteLanguageCatalog;
import art.arcane.volmlib.util.localization.ResolvedText;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import art.arcane.volmlib.util.localization.TomlLanguageEditor;
import art.arcane.volmlib.util.localization.VolmitLocales;
import art.arcane.volmlib.util.localization.LanguageReferenceRenderer;
import art.arcane.volmlib.util.plugin.ComponentText;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class BileLocalization implements AutoCloseable {
    private static final long MAXIMUM_LANGUAGE_BYTES = 2L * 1024L * 1024L;
    private static final int MAXIMUM_REPORTED_ISSUES = 12;
    private static final long AUTOMATIC_RELOAD_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long EXACT_RECONCILIATION_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(2_500L);
    private static final long IO_SHUTDOWN_MILLIS = 1_000L;
    private static final MessageCatalog CATALOG = BileMessages.catalog();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();
    private static final Pattern LOCALE_PATTERN = Pattern.compile("[A-Za-z0-9_-]{2,32}");
    private static final PluralSelector ENGLISH_PLURALS = PluralSelector.oneOther();

    private final File dataFolder;
    private final File languageDirectory;
    private final Path preferenceFile;
    private final Logger logger;
    private final LocalizationManager manager;
    private final AtomicReference<File> activeFile;
    private final RemoteLanguageCatalog remoteCatalog;
    private final Throwable remoteCatalogFailure;
    private final FileWatcherFactory watcherFactory;
    private final SnapshotReader snapshotReader;
    private final ExecutorService automaticReloadIo;
    private final AtomicBoolean automaticReadInFlight = new AtomicBoolean();
    private final AtomicReference<AutomaticReadResult> completedAutomaticRead = new AtomicReference<>();
    private volatile PluginLanguageService languageService;
    private volatile FileWatcher watcher;
    private volatile List<String> availableLocales = List.of();
    private volatile String configuredLocale;
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
        this(dataFolder, logger, configuredLocale, null, FileWatcher::new, BileLocalization::readLanguageContent);
    }

    public BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            PluginLanguageService.DefaultSelection defaultSelection
    ) {
        this(dataFolder, logger, configuredLocale, defaultSelection,
                FileWatcher::new, BileLocalization::readLanguageContent);
    }

    BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            FileWatcherFactory watcherFactory,
            SnapshotReader snapshotReader
    ) {
        this(dataFolder, logger, configuredLocale, null, watcherFactory, snapshotReader);
    }

    private BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            PluginLanguageService.DefaultSelection defaultSelection,
            FileWatcherFactory watcherFactory,
            SnapshotReader snapshotReader
    ) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.watcherFactory = Objects.requireNonNull(watcherFactory, "watcherFactory");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.configuredLocale = normalizeConfiguredLocale(configuredLocale);
        languageDirectory = new File(dataFolder, "languages");
        preferenceFile = languageDirectory.toPath().resolve("language-preferences.properties");
        validateCatalogTemplates();
        manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, ENGLISH_PLURALS));
        activeFile = new AtomicReference<>(languageFile(this.configuredLocale));
        RemoteCatalogState catalogState = loadRemoteCatalog();
        remoteCatalog = catalogState.catalog();
        remoteCatalogFailure = catalogState.failure();
        automaticReloadIo = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "BileTools-Language-Hotload-IO");
            thread.setDaemon(true);
            return thread;
        });

        PreparedLanguage prepared = prepareInitialLanguage(this.configuredLocale);
        installPrepared(prepared);
        PluginLanguageService.DefaultSelection selection = defaultSelection == null
                ? this::selectDefault : defaultSelection;
        languageService = new PluginLanguageService(new PluginLanguageService.Options(
                preferenceFile,
                this::availableLocales,
                () -> this.configuredLocale,
                manager::snapshot,
                this::loadSelectionSnapshot,
                selection,
                logger
        ));
        languageService.cache(prepared.locale(), selectionSnapshot(prepared.locale(), prepared.snapshot()));
        resetWatcher(prepared.file());
    }

    public synchronized PreparedLanguage prepare(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        prepareLanguageDirectory();
        createEnglishLanguageIfMissing();
        File file = languageFile(requiredLocale);
        validateLanguagePath(file);
        if (!file.exists() && !hasRemoteCatalogLocale(requiredLocale)) {
            createCustomLanguageIfMissing(requiredLocale);
        }
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        if (file.isFile()) {
            overlays.add(createOverlay(requiredLocale, file.getPath(), readLanguageText(file)));
        }
        PreparedLanguage prepared = createPrepared(requiredLocale, file, overlays, file.isFile());
        refreshAvailableLocales();
        return prepared;
    }

    public synchronized PreparedLanguage englishFallback(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        prepareLanguageDirectory();
        createEnglishLanguageIfMissing();
        refreshAvailableLocales();
        return new PreparedLanguage(
                requiredLocale,
                languageFile(requiredLocale),
                LocalizationSnapshot.create(LocalizationCandidate.english(CATALOG, ENGLISH_PLURALS)),
                requiredLocale.equalsIgnoreCase(CATALOG.englishLocale())
        );
    }

    public synchronized void installDefault(String locale, LocalizationSnapshot snapshot) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        File file = languageFile(requiredLocale);
        validateLanguagePath(file);
        if (!file.isFile()) {
            throw new IOException("Language file is not installed: " + requiredLocale);
        }
        installPrepared(new PreparedLanguage(requiredLocale, file, snapshot, true));
    }

    public String activeLocale() {
        return activeLocale;
    }

    public File languageFile() {
        return activeFile.get();
    }

    public File languageFile(String locale) {
        return new File(languageDirectory, requireLocale(locale) + ".toml");
    }

    public File languageDirectory() {
        return languageDirectory;
    }

    public List<String> availableLocales() {
        return availableLocales;
    }

    public Optional<Throwable> remoteCatalogFailure() {
        return Optional.ofNullable(remoteCatalogFailure);
    }

    public Optional<String> remoteCatalogReference() {
        return remoteCatalog == null ? Optional.empty() : Optional.of(remoteCatalog.revision());
    }

    public boolean hasRemoteCatalogLocale(String locale) {
        String requiredLocale = canonicalLocale(locale);
        return remoteCatalog != null && remoteCatalog.availableLocales().contains(requiredLocale);
    }

    LocalizationSnapshot snapshot() {
        return manager.snapshot();
    }

    public PluginLanguageService languageService() {
        return languageService;
    }

    public DirectorTextResolver directorResolver() {
        return (key, arguments) -> {
            MessageKey definition = CATALOG.key(key.id());
            if (!(definition instanceof TextKey textKey)) {
                return DirectorTextResolver.ENGLISH.resolve(key, arguments);
            }
            return text(textKey, arguments).plain();
        };
    }

    public PluginLanguageEditor.Options editorOptions() {
        return new PluginLanguageEditor.Options(this::loadSelectionSnapshot, this::saveEditor);
    }

    public ComponentText text(TextKey key) {
        return text(key, MessageArgs.empty());
    }

    public ComponentText text(TextKey key, MessageArgs arguments) {
        return render(selectedSnapshot(null).resolve(key, arguments));
    }

    public ComponentText text(PluralKey key, MessageArgs arguments) {
        return render(selectedSnapshot(null).resolve(key, arguments));
    }

    public ComponentText text(CommandSender sender, TextKey key) {
        return text(sender, key, MessageArgs.empty());
    }

    public ComponentText text(CommandSender sender, TextKey key, MessageArgs arguments) {
        return render(selectedSnapshot(sender).resolve(key, arguments));
    }

    public ComponentText singleLineText(CommandSender sender, TextKey key, MessageArgs arguments) {
        return renderSingleLine(selectedSnapshot(sender).resolve(key, arguments));
    }

    public ComponentText text(CommandSender sender, PluralKey key, MessageArgs arguments) {
        return render(selectedSnapshot(sender).resolve(key, arguments));
    }

    public static ComponentText english(TextKey key) {
        return english(key, MessageArgs.empty());
    }

    public static ComponentText english(TextKey key, MessageArgs arguments) {
        return renderTemplate(key.english(), arguments);
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
        FileWatcher activeWatcher = watcher;
        boolean eventDetected = activeWatcher != null && activeWatcher.checkModifiedEvents();
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
        if (applyLanguageContent(configuredLocale, activeFile.get(), candidate)) {
            appliedLanguageContent = candidate.clone();
            pendingAutomaticContent = null;
        }
    }

    public synchronized boolean reload() {
        File file = activeFile.get();
        byte[] content;
        try {
            content = readLanguageContent(file);
        } catch (IOException failure) {
            logger.log(Level.SEVERE, "Language reload could not capture a stable file snapshot", failure);
            return false;
        }
        if (!applyLanguageContent(configuredLocale, file, content)) {
            return false;
        }
        resetAutomaticState(content);
        return true;
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
        FileWatcher activeWatcher = watcher;
        watcher = null;
        if (activeWatcher != null) {
            activeWatcher.close();
        }
        PluginLanguageService selections = languageService;
        languageService = null;
        if (selections != null) {
            selections.close();
        }
        if (remoteCatalog != null) {
            remoteCatalog.close();
        }
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

    static byte[] readLanguageContent(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || Files.isSymbolicLink(path)) {
            throw new IOException("Language source is not a regular file: " + file.getPath());
        }
        if (before.size() > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language source is too large: " + file.getPath());
        }
        byte[] content;
        try (InputStream input = Files.newInputStream(path)) {
            content = input.readNBytes((int) MAXIMUM_LANGUAGE_BYTES + 1);
        }
        if (content.length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language source is too large: " + file.getPath());
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())
                || content.length != after.size()) {
            throw new IOException("Language source changed while it was being read: " + file.getPath());
        }
        return content;
    }

    private RemoteCatalogState loadRemoteCatalog() {
        try {
            RemoteLanguageCatalog catalog = RemoteLanguageCatalog.load(new RemoteLanguageCatalog.Options(
                    "BileTools",
                    URI.create("https://raw.githubusercontent.com/VolmitSoftware/BileTools/"),
                    "src/main/resources/languages",
                    ".toml",
                    "biletools-language-source.properties",
                    new File(dataFolder, ".language-cache").toPath(),
                    BileLocalization.class.getClassLoader()
            ));
            return new RemoteCatalogState(catalog, null);
        } catch (Throwable failure) {
            logger.log(Level.WARNING,
                    "Remote language catalog is unavailable; code-owned English and installed local language files remain active",
                    failure);
            return new RemoteCatalogState(null, failure);
        }
    }

    private PreparedLanguage prepareInitialLanguage(String locale) {
        try {
            LocalizationSnapshot prepared = loadSelectionSnapshot(locale);
            return new PreparedLanguage(canonicalLocale(locale), languageFile(locale), prepared, true);
        } catch (Exception failure) {
            logger.log(Level.WARNING,
                    "Configured language could not be loaded; BileTools is continuing with code-owned English",
                    failure);
            try {
                return englishFallback(locale);
            } catch (IOException fallbackFailure) {
                failure.addSuppressed(fallbackFailure);
                throw new IllegalStateException("Unable to initialize BileTools localization", failure);
            }
        }
    }

    private synchronized LocalizationSnapshot loadSelectionSnapshot(String locale) throws Exception {
        String requiredLocale = canonicalLocale(locale);
        prepareLanguageDirectory();
        createEnglishLanguageIfMissing();
        File target = languageFile(requiredLocale);
        if (!target.exists() && hasRemoteCatalogLocale(requiredLocale)) {
            URI source = remoteCatalog.sourceUri(requiredLocale);
            logger.info("Downloading BileTools language " + requiredLocale + " from " + source + "...");
            remoteCatalog.readOrInstall(requiredLocale, target.toPath(), this::validateDownloadedContent);
            logger.info("Downloaded BileTools language " + requiredLocale + " to "
                    + target.toPath().toAbsolutePath().normalize() + ".");
        }
        PreparedLanguage prepared = prepare(requiredLocale);
        if (!prepared.selectionReady()) {
            throw new IOException("Language file is not installed: " + requiredLocale);
        }
        return selectionSnapshot(requiredLocale, prepared.snapshot());
    }

    private synchronized void selectDefault(String locale, LocalizationSnapshot prepared) throws Exception {
        File configurationFile = new File(dataFolder, "biletools.yml");
        YamlConfiguration configuration = new YamlConfiguration();
        if (configurationFile.isFile()) {
            configuration.load(configurationFile);
        }
        configuration.set("language", locale);
        AtomicFileIO.writeString(configurationFile.toPath(), configuration.saveToString());
        installPrepared(new PreparedLanguage(locale, languageFile(locale), prepared, true));
    }

    private synchronized LocalizationSnapshot saveEditor(PluginLanguageEditor.Edit edit) throws Exception {
        LocalizationSnapshot current = loadSelectionSnapshot(edit.locale());
        MessageKey definition = CATALOG.require(edit.key());
        if (!current.value(definition).equals(edit.expected())) {
            throw new IOException("Language message changed while it was being edited: " + edit.key());
        }
        Path path = languageFile(edit.locale()).toPath();
        LocalizationSnapshot updated = LanguageFileEditor.update(path, raw -> {
            TomlLanguageEditor.EditResult result = TomlLanguageEditor.upsert(raw, edit.key(), edit.value());
            LocalizationSnapshot snapshot = createSnapshot(edit.locale(), path.toString(), result.content());
            return new LanguageFileEditor.Prepared<>(result.content(), snapshot);
        });
        PluginLanguageService selections = languageService;
        if (selections != null) {
            selections.cache(edit.locale(), updated);
        }
        if (sameLocale(configuredLocale, edit.locale())) {
            installPrepared(new PreparedLanguage(edit.locale(), path.toFile(), updated, true));
        }
        refreshAvailableLocales();
        return updated;
    }

    private synchronized void installPrepared(PreparedLanguage prepared) {
        manager.install(prepared.snapshot());
        configuredLocale = prepared.locale();
        activeLocale = prepared.locale();
        activeFile.set(prepared.file());
        PluginLanguageService selections = languageService;
        if (selections != null) {
            selections.invalidate();
            selections.cache(prepared.locale(), selectionSnapshot(prepared.locale(), prepared.snapshot()));
        }
        byte[] content = readCurrentContent(prepared.file());
        resetAutomaticState(content);
        if (watcher != null) {
            resetWatcher(prepared.file());
        }
    }

    private void resetWatcher(File file) {
        FileWatcher previous = watcher;
        if (previous != null) {
            previous.close();
        }
        try {
            watcher = watcherFactory.create(file);
        } catch (RuntimeException failure) {
            watcher = null;
            logger.log(Level.WARNING, "Unable to watch the active BileTools language file", failure);
        }
    }

    private void resetAutomaticState(byte[] content) {
        automaticReadGeneration++;
        automaticReadRequested = false;
        pendingAutomaticContent = null;
        completedAutomaticRead.set(null);
        appliedLanguageContent = content.clone();
        nextAutomaticReloadNanos = Long.MIN_VALUE;
        nextExactReconciliationNanos = Long.MIN_VALUE;
        lastAutomaticReadFailure = null;
    }

    private byte[] readCurrentContent(File file) {
        try {
            return file.isFile() ? readLanguageContent(file) : new byte[0];
        } catch (IOException failure) {
            logger.log(Level.WARNING, "Unable to establish the active language snapshot", failure);
            return new byte[0];
        }
    }

    private LocalizationSnapshot selectionSnapshot(String locale, LocalizationSnapshot prepared) {
        if (sameLocale(locale, CATALOG.englishLocale())) {
            return prepared;
        }
        ArrayList<LocaleOverlay> overlays = new ArrayList<>(prepared.overlays());
        LocaleOverlay.Builder englishFallback = LocaleOverlay.builder("code-owned-English:" + locale, locale);
        for (MessageKey key : CATALOG.keys()) {
            englishFallback.put(key.id(), key.englishValue());
        }
        overlays.add(englishFallback.build());
        return LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, overlays, ENGLISH_PLURALS));
    }

    private boolean applyLanguageContent(String locale, File file, byte[] content) {
        byte[] immutableContent = content.clone();
        LocalizationReloadResult result = manager.reload(() -> createCandidate(
                locale, file.getPath(), decodeUtf8(immutableContent)));
        if (!result.applied()) {
            reportRejectedReload(result);
            return false;
        }
        PluginLanguageService selections = languageService;
        if (selections != null) {
            selections.invalidate();
            selections.cache(locale, selectionSnapshot(locale, result.current()));
        }
        activeLocale = locale;
        return true;
    }

    private LocalizationSnapshot createSnapshot(String locale, String source, String content) throws IOException {
        try {
            return LocalizationSnapshot.create(createCandidate(locale, source, content));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid language selection " + locale, exception);
        }
    }

    private LocalizationCandidate createCandidate(String locale, String source, String content) throws IOException {
        return new LocalizationCandidate(
                CATALOG,
                List.of(createOverlay(locale, source, content)),
                ENGLISH_PLURALS
        );
    }

    private PreparedLanguage createPrepared(
            String locale,
            File file,
            List<LocaleOverlay> overlays,
            boolean selectionReady
    ) throws IOException {
        try {
            LocalizationSnapshot snapshot = LocalizationSnapshot.create(
                    new LocalizationCandidate(CATALOG, overlays, ENGLISH_PLURALS));
            return new PreparedLanguage(locale, file, snapshot, selectionReady);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid language selection " + locale, exception);
        }
    }

    private LocaleOverlay createOverlay(String locale, String source, String content) throws IOException {
        Map<String, MessageValue> values;
        try {
            values = BileTomlLanguageParser.parse(content, CATALOG);
        } catch (IOException exception) {
            throw new IOException("Invalid TOML in " + new File(source).getName() + ": " + exception.getMessage(), exception);
        }
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        for (Map.Entry<String, MessageValue> entry : values.entrySet()) {
            validateValue("language:" + entry.getKey(), entry.getValue());
            overlay.put(entry.getKey(), entry.getValue());
        }
        return overlay.build();
    }

    private String readLanguageText(File file) throws IOException {
        return decodeUtf8(readLanguageContent(file));
    }

    private String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Language file contains invalid UTF-8", exception);
        }
    }

    private void validateDownloadedContent(String locale, String content) throws IOException {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language file exceeds the 2 MiB safety limit");
        }
        LocaleOverlay overlay = createOverlay(locale, "download:" + locale, content);
        if (overlay.values().isEmpty()) {
            throw new IOException("Downloaded locale does not contain any recognized BileTools messages: " + locale);
        }
        try {
            LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, List.of(overlay), ENGLISH_PLURALS));
        } catch (RuntimeException exception) {
            throw new IOException("Downloaded locale is invalid: " + locale, exception);
        }
    }

    private void prepareLanguageDirectory() throws IOException {
        Path directory = languageDirectory.toPath().toAbsolutePath().normalize();
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory))) {
            throw new IOException("Language path is not a regular directory: " + directory);
        }
        Files.createDirectories(directory);
    }

    private void validateLanguagePath(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))) {
            throw new IOException("Language path is not a regular file: " + file.getName());
        }
    }

    private void createEnglishLanguageIfMissing() throws IOException {
        File english = languageFile(CATALOG.englishLocale());
        if (!english.exists()) {
            AtomicFileIO.writeString(english.toPath(), LanguageReferenceRenderer.render(
                    CATALOG, englishHeader(CATALOG.englishLocale())));
        }
    }

    private void createCustomLanguageIfMissing(String locale) throws IOException {
        File target = languageFile(locale);
        if (!target.exists()) {
            AtomicFileIO.writeString(target.toPath(), LanguageReferenceRenderer.render(CATALOG, englishHeader(locale)));
        }
    }

    private void refreshAvailableLocales() throws IOException {
        Path root = languageDirectory.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            availableLocales = List.of();
            return;
        }
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(CATALOG.englishLocale());
        if (remoteCatalog != null) {
            choices.addAll(remoteCatalog.availableLocales());
        }
        try (Stream<Path> files = Files.list(root)) {
            for (Path path : files.toList()) {
                locale(path).ifPresent(choices::add);
            }
        }
        ArrayList<String> discovered = new ArrayList<>(choices);
        discovered.sort(String.CASE_INSENSITIVE_ORDER);
        availableLocales = List.copyOf(discovered);
    }

    private Optional<String> locale(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String fileName = path.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".toml")) {
            return Optional.empty();
        }
        String locale = fileName.substring(0, fileName.length() - ".toml".length());
        return LOCALE_PATTERN.matcher(locale).matches() ? Optional.of(locale) : Optional.empty();
    }

    private String normalizeConfiguredLocale(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        return LOCALE_PATTERN.matcher(normalized).matches() ? normalized : CATALOG.englishLocale();
    }

    private String requireLocale(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        if (!LOCALE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Language locale must be a safe name without a path or extension");
        }
        return normalized;
    }

    private String canonicalLocale(String locale) {
        String requiredLocale = requireLocale(locale);
        if (CATALOG.englishLocale().equalsIgnoreCase(requiredLocale)) {
            return CATALOG.englishLocale();
        }
        if (remoteCatalog != null) {
            for (String available : remoteCatalog.availableLocales()) {
                if (available.equalsIgnoreCase(requiredLocale)) {
                    return available;
                }
            }
        }
        return requiredLocale;
    }

    private List<String> englishHeader(String locale) {
        return List.of(
                "BileTools language: " + locale,
                "",
                "This file is editable in a text editor or through /biletools config.",
                "BileTools creates or downloads it only when missing. Local changes are not replaced.",
                "Missing messages use the built-in English catalog.",
                "",
                "Formatting",
                "  Colors and styles  &0 through &f, &k through &r",
                "  RGB                &#RRGGBB, &xRRGGBB, &x&R&R&G&G&B&B, [RRGGBB]",
                "  Custom markup      MiniMessage is supported",
                "  Literal text       Put a backslash before & or [",
                "",
                "Placeholders",
                "Keep the placeholders already used by a message. Do not rename them.",
                "  {argument}          Unexpected command argument",
                "  {category}          Configuration editor category",
                "  {command}           Command path or submitted command",
                "  {context}           Reason or trigger for a plugin reload",
                "  {count}             Configured remote deployment target count and plural selector",
                "  {file}              Plugin jar filename",
                "  {host}              Inbound sender IP address",
                "  {installedVersion}  Currently installed plugin version",
                "  {key}               Command parameter key",
                "  {latestVersion}     Latest available library version",
                "  {locale}            Locale identifier",
                "  {milliseconds}      Load, unload, or reload duration in milliseconds",
                "  {new}               Newly saved language message value",
                "  {old}               Previous language message value",
                "  {parameter}         Command parameter name",
                "  {permission}        Required permission node",
                "  {personal}          Personal locale when different from the server default",
                "  {plugin}            Plugin name",
                "  {reason}            Failure reason",
                "  {setting}           Configuration setting name",
                "  {type}              Command parameter type",
                "  {usage}             Command usage syntax",
                "  {value}             Current, default, or submitted value",
                "  {version}           Requested or listed library plugin version"
        );
    }

    private LocalizationSnapshot selectedSnapshot(CommandSender sender) {
        PluginLanguageService selections = languageService;
        if (selections == null) {
            return manager.snapshot();
        }
        return sender instanceof Player player
                ? selections.snapshot(player.getUniqueId()) : selections.snapshot();
    }

    private void validateCatalogTemplates() {
        for (MessageKey key : CATALOG.keys()) {
            validateValue("catalog:" + key.id(), key.englishValue());
        }
    }

    private void validateValue(String path, MessageValue value) {
        if (value instanceof TextValue text) {
            validateTemplate(path, text.template(), sampleArguments(value.placeholders()));
            return;
        }
        if (value instanceof LinesValue lines) {
            for (String line : lines.lines()) {
                validateTemplate(path, line, sampleArguments(value.placeholders()));
            }
            return;
        }
        PluralValue plural = (PluralValue) value;
        for (Map.Entry<String, String> form : plural.forms().entrySet()) {
            validateTemplate(path + "." + form.getKey(), form.getValue(), sampleArguments(value.placeholders()));
        }
    }

    private void validateTemplate(String path, String template, MessageArgs arguments) {
        try {
            STRICT_MINI_MESSAGE.deserialize(interpolate(template, arguments));
            MINI_MESSAGE.deserialize(interpolate(ComponentText.normalizeMarkup(template), arguments));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(path + ": invalid message markup", exception);
        }
    }

    private MessageArgs sampleArguments(Set<String> placeholders) {
        MessageArgs.Builder builder = MessageArgs.builder();
        for (String placeholder : placeholders) {
            builder.untrusted(placeholder, "value");
        }
        return builder.build();
    }

    private static ComponentText render(ResolvedText resolved) {
        return renderTemplate(resolved.template(), resolved.arguments());
    }

    private static ComponentText renderSingleLine(ResolvedText resolved) {
        String rendered = interpolate(resolved.template(), resolved.arguments())
                .replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ');
        return ComponentText.markup(rendered);
    }

    private static ComponentText renderTemplate(String template, MessageArgs arguments) {
        return ComponentText.markup(interpolate(template, arguments));
    }

    private static String interpolate(String template, MessageArgs arguments) {
        StringBuilder output = new StringBuilder(template.length());
        int index = 0;
        while (index < template.length()) {
            char current = template.charAt(index);
            if (current == '{' && index + 1 < template.length() && template.charAt(index + 1) == '{') {
                output.append('{');
                index += 2;
                continue;
            }
            if (current == '}' && index + 1 < template.length() && template.charAt(index + 1) == '}') {
                output.append('}');
                index += 2;
                continue;
            }
            if (current != '{') {
                output.append(current);
                index++;
                continue;
            }
            int end = template.indexOf('}', index + 1);
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed message placeholder");
            }
            String name = template.substring(index + 1, end);
            MessageArgument argument = arguments.require(name);
            String replacement = String.valueOf(argument.value());
            if (argument.kind() == MessageArgumentKind.UNTRUSTED) {
                replacement = escapeUntrusted(replacement);
            }
            output.append(replacement);
            index = end + 1;
        }
        return output.toString();
    }

    private static String escapeUntrusted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '&' || current == '[') {
                escaped.append('\\');
            }
            escaped.append(current);
        }
        return MINI_MESSAGE.escapeTags(escaped.toString());
    }

    private boolean sameLocale(String first, String second) {
        return first.replace('-', '_').equalsIgnoreCase(second.replace('-', '_'));
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
        File file = activeFile.get();
        String locale = configuredLocale;
        try {
            automaticReloadIo.execute(() -> captureAutomaticRead(generation, file, locale));
        } catch (RejectedExecutionException rejected) {
            automaticReadInFlight.set(false);
            if (!closed) {
                automaticReadRequested = true;
            }
        }
    }

    private void captureAutomaticRead(long generation, File file, String locale) {
        AutomaticReadResult result;
        try {
            result = new AutomaticReadResult(generation, file, locale, snapshotReader.read(file), null);
        } catch (NoSuchFileException missing) {
            result = new AutomaticReadResult(generation, file, locale, null, null);
        } catch (IOException | RuntimeException failure) {
            result = new AutomaticReadResult(generation, file, locale, null, failure);
        }
        completedAutomaticRead.set(result);
        automaticReadInFlight.set(false);
    }

    private void consumeAutomaticRead() {
        AutomaticReadResult result = completedAutomaticRead.getAndSet(null);
        if (result != null
                && result.generation() == automaticReadGeneration
                && result.file().equals(activeFile.get())
                && sameLocale(result.locale(), configuredLocale)) {
            if (result.failure() == null && result.content() != null) {
                lastAutomaticReadFailure = null;
                pendingAutomaticContent = Arrays.equals(result.content(), appliedLanguageContent)
                        ? null : result.content().clone();
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

    private void reportRejectedReload(LocalizationReloadResult result) {
        logger.severe("Rejected language reload; continuing with " + activeLocale + ".");
        List<LocalizationIssue> issues = result.validation().errors();
        for (int index = 0; index < Math.min(issues.size(), MAXIMUM_REPORTED_ISSUES); index++) {
            LocalizationIssue issue = issues.get(index);
            logger.severe(issue.source() + " [" + issue.key() + "]: " + issue.detail());
        }
        if (issues.size() > MAXIMUM_REPORTED_ISSUES) {
            logger.severe((issues.size() - MAXIMUM_REPORTED_ISSUES) + " additional language errors were omitted.");
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

    interface FileWatcherFactory {
        FileWatcher create(File file);
    }

    interface SnapshotReader {
        byte[] read(File file) throws IOException;
    }

    public record PreparedLanguage(
            String locale,
            File file,
            LocalizationSnapshot snapshot,
            boolean selectionReady
    ) {
    }

    private record AutomaticReadResult(
            long generation,
            File file,
            String locale,
            byte[] content,
            Throwable failure
    ) {
        private AutomaticReadResult {
            content = content == null ? null : content.clone();
        }
    }

    private record RemoteCatalogState(RemoteLanguageCatalog catalog, Throwable failure) {
    }
}
