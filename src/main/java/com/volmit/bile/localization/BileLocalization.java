package com.volmit.bile.localization;

import art.arcane.volmlib.util.localization.TomlLanguageParser;
import art.arcane.volmlib.util.localization.LanguageFileHeader;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.io.AtomicFileIO;
import art.arcane.volmlib.util.io.FileWatcher;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import art.arcane.volmlib.util.localization.GitHubLanguageSource;
import art.arcane.volmlib.util.localization.LanguageCatalogMerge;
import art.arcane.volmlib.util.localization.LanguageDirectory;
import art.arcane.volmlib.util.localization.LanguageFileEditor;
import art.arcane.volmlib.util.localization.LanguageMeta;
import art.arcane.volmlib.util.localization.LanguageSourceManifest;
import art.arcane.volmlib.util.localization.LanguageUpstreamStore;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.LocaleOverlay;
import art.arcane.volmlib.util.localization.LocalizationCandidate;
import art.arcane.volmlib.util.localization.LocalizationIssue;
import art.arcane.volmlib.util.localization.LocalizationManager;
import art.arcane.volmlib.util.localization.LocalizationReloadResult;
import art.arcane.volmlib.util.localization.LocalizationSnapshot;
import art.arcane.volmlib.util.localization.LocalizationValidator;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class BileLocalization implements AutoCloseable {
    public static final String MANIFEST_RESOURCE = "biletools-language-source.properties";
    private static final long MAXIMUM_LANGUAGE_BYTES = LanguageDirectory.MAXIMUM_BYTES;
    private static final int MAXIMUM_BASE_DEPTH = 4;
    private static final int MAXIMUM_REPORTED_ISSUES = 12;
    private static final long AUTOMATIC_RELOAD_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long EXACT_RECONCILIATION_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(2_500L);
    private static final long IO_SHUTDOWN_MILLIS = 1_000L;
    private static final MessageCatalog CATALOG = BileMessages.catalog();
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final MiniMessage STRICT_MINI_MESSAGE = MiniMessage.builder().strict(true).build();
    private static final PluralSelector ENGLISH_PLURALS = PluralSelector.oneOther();

    private final File dataFolder;
    private final File languageDirectory;
    private final LanguageDirectory directory;
    private final Path preferenceFile;
    private final Logger logger;
    private final LocalizationManager manager;
    private final AtomicReference<File> activeFile;
    private final LanguageSourceManifest manifest;
    private final LanguageUpstreamSync sync;
    private final Throwable sourceFailure;
    private final FileWatcherFactory watcherFactory;
    private final SnapshotReader snapshotReader;
    private final ExecutorService automaticReloadIo;
    private final AtomicBoolean automaticReadInFlight = new AtomicBoolean();
    private final AtomicReference<AutomaticReadBatch> completedAutomaticRead = new AtomicReference<>();
    private final Map<String, byte[]> appliedPersonalContent = new HashMap<>();
    private final Map<String, AutomaticReadResult> pendingPersonalContent = new HashMap<>();
    private final Map<String, String> personalReadFailures = new HashMap<>();
    private final Set<String> observedLocales = new HashSet<>();
    private final ConcurrentLinkedQueue<SelfWrite> selfWrites = new ConcurrentLinkedQueue<>();
    private volatile PluginLanguageService languageService;
    private volatile FileWatcher watcher;
    private volatile List<String> availableLocales = List.of();
    private volatile Map<String, LanguageMeta> metas = Map.of();
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
        this(dataFolder, logger, configuredLocale, null, FileWatcher::new, BileLocalization::readLanguageContent,
                UpstreamEndpoints.GITHUB);
    }

    public BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            PluginLanguageService.DefaultSelection defaultSelection
    ) {
        this(dataFolder, logger, configuredLocale, defaultSelection,
                FileWatcher::new, BileLocalization::readLanguageContent, UpstreamEndpoints.GITHUB);
    }

    BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            FileWatcherFactory watcherFactory,
            SnapshotReader snapshotReader
    ) {
        this(dataFolder, logger, configuredLocale, null, watcherFactory, snapshotReader, UpstreamEndpoints.GITHUB);
    }

    BileLocalization(File dataFolder, Logger logger, String configuredLocale, UpstreamEndpoints upstream) {
        this(dataFolder, logger, configuredLocale, null, FileWatcher::new, BileLocalization::readLanguageContent,
                upstream);
    }

    private BileLocalization(
            File dataFolder,
            Logger logger,
            String configuredLocale,
            PluginLanguageService.DefaultSelection defaultSelection,
            FileWatcherFactory watcherFactory,
            SnapshotReader snapshotReader,
            UpstreamEndpoints upstream
    ) {
        this.dataFolder = Objects.requireNonNull(dataFolder, "dataFolder");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.watcherFactory = Objects.requireNonNull(watcherFactory, "watcherFactory");
        this.snapshotReader = Objects.requireNonNull(snapshotReader, "snapshotReader");
        this.configuredLocale = normalizeConfiguredLocale(configuredLocale);
        languageDirectory = new File(dataFolder, "languages");
        directory = new LanguageDirectory(languageDirectory);
        preferenceFile = directory.root().resolve("language-preferences.properties");
        validateCatalogTemplates();
        manager = new LocalizationManager(LocalizationCandidate.english(CATALOG, ENGLISH_PLURALS));
        activeFile = new AtomicReference<>(languageFile(this.configuredLocale));
        UpstreamState upstreamState = loadUpstream(Objects.requireNonNull(upstream, "upstream"));
        manifest = upstreamState.manifest();
        sync = upstreamState.sync();
        sourceFailure = upstreamState.failure();
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
        directory.prepare();
        ensureEnglish();
        File file = languageFile(requiredLocale);
        validateLanguagePath(file);
        if (!file.exists() && !isRepositoryLocale(requiredLocale)) {
            createCustomLanguageIfMissing(requiredLocale);
        }
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        if (file.isFile()) {
            String content = readLanguageText(file);
            overlays.add(createOverlay(requiredLocale, file.getPath(), content));
            overlays.addAll(baseOverlays(requiredLocale, content));
        }
        PreparedLanguage prepared = createPrepared(requiredLocale, file, overlays, file.isFile());
        refreshAvailableLocales();
        return prepared;
    }

    public synchronized PreparedLanguage englishFallback(String locale) throws IOException {
        String requiredLocale = canonicalLocale(locale);
        directory.prepare();
        ensureEnglish();
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
        return directory.languageFile(locale).toFile();
    }

    public File languageDirectory() {
        return languageDirectory;
    }

    public List<String> availableLocales() {
        return availableLocales;
    }

    public String localeDisplayName(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        return metas.getOrDefault(normalized, LanguageMeta.empty()).name()
                .or(() -> VolmitLocales.displayName(normalized))
                .orElse(normalized);
    }

    public LanguageMeta meta(String locale) {
        return metas.getOrDefault(locale == null ? "" : locale.trim(), LanguageMeta.empty());
    }

    public Optional<Throwable> sourceFailure() {
        return Optional.ofNullable(sourceFailure);
    }

    public String sourceDescription() {
        if (manifest == null) {
            return "unavailable";
        }
        return manifest.repository() + "@" + manifest.branch();
    }

    public Optional<LanguageUpstreamSync.Status> upstreamStatus() {
        return sync == null ? Optional.empty() : Optional.of(sync.status());
    }

    public boolean isRepositoryLocale(String locale) {
        return manifest != null && manifest.contains(locale);
    }

    public BukkitLanguageSwitcher.LanguageUpdateAction updateAction() {
        return this::checkUpstream;
    }

    public BukkitLanguageSwitcher.LocaleDisplayNames displayNames() {
        return locale -> metas.getOrDefault(locale, LanguageMeta.empty()).name().orElse(null);
    }

    public CompletableFuture<LanguageUpstreamSync.SyncReport> startUpstreamCheck() {
        return checkUpstream();
    }

    public CompletableFuture<String> requestInstall(String locale) {
        String requiredLocale = canonicalLocale(locale);
        if (languageFile(requiredLocale).isFile()) {
            try {
                return CompletableFuture.completedFuture(directory.read(requiredLocale));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        if (sync == null || !isRepositoryLocale(requiredLocale)) {
            return CompletableFuture.failedFuture(new IOException("Language " + requiredLocale
                    + " is not available from " + sourceDescription()));
        }
        return sync.installAsync(requiredLocale);
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
        drainSelfWrites();
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
                || pendingAutomaticContent == null && pendingPersonalContent.isEmpty()
                || nowNanos < nextAutomaticReloadNanos) {
            return;
        }
        nextAutomaticReloadNanos = saturatingAdd(nowNanos, AUTOMATIC_RELOAD_INTERVAL_NANOS);
        if (pendingAutomaticContent != null) {
            byte[] candidate = pendingAutomaticContent;
            if (applyLanguageContent(configuredLocale, activeFile.get(), candidate)) {
                appliedLanguageContent = candidate.clone();
                pendingAutomaticContent = null;
            }
        }
        for (AutomaticReadResult candidate : List.copyOf(pendingPersonalContent.values())) {
            if (applyPersonalContent(candidate)) {
                appliedPersonalContent.put(candidate.locale(), candidate.content().clone());
                pendingPersonalContent.remove(candidate.locale());
            }
        }
    }

    public synchronized boolean reload() {
        drainSelfWrites();
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
        pendingPersonalContent.clear();
        appliedPersonalContent.clear();
        personalReadFailures.clear();
        observedLocales.clear();
        selfWrites.clear();
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
        if (sync != null) {
            sync.close();
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

    private UpstreamState loadUpstream(UpstreamEndpoints upstream) {
        try {
            LanguageSourceManifest loadedManifest = LanguageSourceManifest.load(
                    BileLocalization.class.getClassLoader(), MANIFEST_RESOURCE);
            GitHubLanguageSource source = new GitHubLanguageSource(loadedManifest, "BileTools",
                    upstream.apiRoot(), upstream.rawRoot());
            LanguageUpstreamStore store = new LanguageUpstreamStore(directory, loadedManifest);
            LanguageUpstreamSync loadedSync = new LanguageUpstreamSync(new LanguageUpstreamSync.Options(
                    "BileTools",
                    CATALOG,
                    directory,
                    loadedManifest,
                    source,
                    store,
                    () -> LanguageReferenceRenderer.render(CATALOG, englishHeader(CATALOG.englishLocale())),
                    this::validateContent,
                    this::commitUpstream,
                    this::noteUpstreamWrite,
                    this::applyUpstream,
                    logger
            ), upstream.minimumCheckInterval());
            return new UpstreamState(loadedManifest, loadedSync, null);
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING,
                    "Language source is unavailable; code-owned English and installed local language files remain active",
                    failure);
            return new UpstreamState(null, null, failure);
        }
    }

    private CompletableFuture<LanguageUpstreamSync.SyncReport> checkUpstream() {
        if (sync == null) {
            return CompletableFuture.completedFuture(LanguageUpstreamSync.SyncReport.failed(
                    sourceDescription(), System.currentTimeMillis()));
        }
        return sync.checkAsync().thenApply(this::logUpstreamReport);
    }

    private LanguageUpstreamSync.SyncReport logUpstreamReport(LanguageUpstreamSync.SyncReport report) {
        String revision = report.revision().map(sha -> sha.substring(0, 7)).orElse("unknown");
        for (String locale : report.changedLocales()) {
            LanguageCatalogMerge.MergeResult result = report.merged().get(locale);
            String source = sameLocale(locale, CATALOG.englishLocale())
                    ? "the built-in catalog" : sourceDescription() + " (" + revision + ")";
            logger.info("Updated BileTools language " + locale + " from " + source + ": " + result.summary() + ".");
        }
        if (report.coolingDown()) {
            logger.info("BileTools language source " + sourceDescription()
                    + " was checked recently; the next check is allowed in " + report.cooldownRemaining() + ".");
        } else if (report.failure().isEmpty() && report.changedLocales().isEmpty() && report.failures().isEmpty()
                && report.revision().isPresent()) {
            logger.info("BileTools languages match " + sourceDescription() + " (" + revision + ").");
        }
        return report;
    }

    private <T> T commitUpstream(LanguageUpstreamSync.CommitAction<T> action) throws IOException {
        PluginLanguageService selections = languageService;
        if (selections == null) {
            return action.apply();
        }
        return selections.commitUpdate(action::apply);
    }

    private void noteUpstreamWrite(Path file, String content) {
        selfWrites.add(new SelfWrite(file.toFile(), content.getBytes(StandardCharsets.UTF_8)));
    }

    private void applyUpstream(String locale, String content, LanguageCatalogMerge.MergeResult result)
            throws IOException {
        File file = languageFile(locale);
        LocalizationSnapshot snapshot = createSnapshot(locale, file.getPath(), content);
        refreshAvailableLocales();
        PluginLanguageService selections = languageService;
        if (selections != null) {
            selections.cache(locale, selectionSnapshot(locale, snapshot));
        }
        if (sameLocale(locale, configuredLocale)
                && file.getAbsoluteFile().equals(activeFile.get().getAbsoluteFile())) {
            manager.install(snapshot);
            activeLocale = locale;
        }
    }

    private void drainSelfWrites() {
        SelfWrite written = selfWrites.poll();
        while (written != null) {
            markSelfWrite(written.file(), written.content());
            written = selfWrites.poll();
        }
    }

    private void markSelfWrite(File file, byte[] content) {
        automaticReadGeneration++;
        completedAutomaticRead.set(null);
        if (file.getAbsoluteFile().equals(activeFile.get().getAbsoluteFile())) {
            pendingAutomaticContent = null;
            appliedLanguageContent = content.clone();
            return;
        }
        Optional<String> locale = directory.locale(file.toPath());
        if (locale.isPresent()) {
            pendingPersonalContent.remove(locale.get());
            appliedPersonalContent.put(locale.get(), content.clone());
        }
    }

    private PreparedLanguage prepareInitialLanguage(String locale) {
        try {
            if (!languageFile(locale).exists() && isRepositoryLocale(locale)) {
                logger.info("BileTools language " + canonicalLocale(locale) + " is not installed yet; code-owned English"
                        + " stays active until it downloads from " + sourceDescription() + ".");
                return englishFallback(locale);
            }
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
        directory.prepare();
        ensureEnglish();
        File target = languageFile(requiredLocale);
        if (!target.exists() && isRepositoryLocale(requiredLocale)) {
            if (sync == null) {
                throw new IOException("Language " + requiredLocale + " is not installed and the language source is "
                        + sourceDescription());
            }
            logger.info("Installing BileTools language " + requiredLocale + " from " + sourceDescription() + "...");
            sync.install(requiredLocale);
            logger.info("Installed BileTools language " + requiredLocale + " to "
                    + target.toPath().toAbsolutePath().normalize() + ".");
        }
        PreparedLanguage prepared = prepare(requiredLocale);
        if (!prepared.selectionReady()) {
            throw new IOException("Language file is not installed: " + requiredLocale);
        }
        observedLocales.add(requiredLocale);
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
        try {
            LocaleOverlay edited = LocaleOverlay.builder("editor", edit.locale()).put(edit.key(), edit.value()).build();
            LocalizationValidator.validate(CATALOG, List.of(edited)).throwIfInvalid();
            validateValue("editor:" + edit.key(), edit.value());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid language message: " + edit.key(), invalid);
        }
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
        } else {
            automaticReadGeneration++;
            pendingPersonalContent.remove(edit.locale());
            appliedPersonalContent.put(edit.locale(), readCurrentContent(path.toFile()));
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
        pendingPersonalContent.clear();
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
            selections.cache(locale, selectionSnapshot(locale, result.current()));
        }
        activeLocale = locale;
        return true;
    }

    private boolean applyPersonalContent(AutomaticReadResult result) {
        try {
            LocalizationSnapshot prepared = createSnapshot(result.locale(), result.file().getPath(),
                    decodeUtf8(result.content()));
            PluginLanguageService selections = languageService;
            if (selections != null) {
                selections.cache(result.locale(), selectionSnapshot(result.locale(), prepared));
            }
            personalReadFailures.remove(result.locale());
            return true;
        } catch (IOException | RuntimeException failure) {
            reportPersonalReadFailure(result.locale(), failure);
            return false;
        }
    }

    private void reportPersonalReadFailure(String locale, Throwable failure) {
        String message = failure.toString();
        if (!Objects.equals(personalReadFailures.put(locale, message), message)) {
            logger.log(Level.WARNING, "Language hotload retained the previous " + locale + " messages", failure);
        }
    }

    private LocalizationSnapshot createSnapshot(String locale, String source, String content) throws IOException {
        try {
            return LocalizationSnapshot.create(createCandidate(locale, source, content));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid language selection " + locale, exception);
        }
    }

    private LocalizationCandidate createCandidate(String locale, String source, String content) throws IOException {
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        overlays.add(createOverlay(locale, source, content));
        overlays.addAll(baseOverlays(locale, content));
        return new LocalizationCandidate(CATALOG, overlays, ENGLISH_PLURALS);
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
            values = TomlLanguageParser.parseValidValues(content, CATALOG);
        } catch (IOException exception) {
            throw new IOException("Invalid TOML in " + new File(source).getName() + ": " + exception.getMessage(), exception);
        }
        LocaleOverlay.Builder overlay = LocaleOverlay.builder(source, locale);
        for (Map.Entry<String, MessageValue> entry : values.entrySet()) {
            try {
                validateValue("language:" + entry.getKey(), entry.getValue());
                overlay.put(entry.getKey(), entry.getValue());
            } catch (IllegalArgumentException invalid) {
                continue;
            }
        }
        return LocalizationValidator.validValues(CATALOG, overlay.build());
    }

    private List<LocaleOverlay> baseOverlays(String locale, String content) throws IOException {
        ArrayList<LocaleOverlay> overlays = new ArrayList<>();
        HashSet<String> visited = new HashSet<>();
        visited.add(normalizedLocale(locale));
        LanguageMeta meta = metaFromContent(locale, content);
        int depth = 0;
        while (meta.base().isPresent() && depth < MAXIMUM_BASE_DEPTH) {
            String base = canonicalLocale(meta.base().get());
            if (!visited.add(normalizedLocale(base)) || sameLocale(base, CATALOG.englishLocale())) {
                break;
            }
            File baseFile = languageFile(base);
            if (!baseFile.isFile()) {
                break;
            }
            String baseContent = LanguageDirectory.readContent(baseFile.toPath());
            overlays.add(createOverlay(base, baseFile.getPath(), baseContent));
            meta = metaFromContent(base, baseContent);
            depth++;
        }
        return overlays;
    }

    private LanguageMeta metaFromContent(String locale, String content) {
        try {
            return LanguageMeta.parse(content).withoutSelfBase(locale);
        } catch (IOException exception) {
            return LanguageMeta.empty();
        }
    }

    private String readLanguageText(File file) throws IOException {
        return decodeUtf8(readLanguageContent(file));
    }

    private String decodeUtf8(byte[] bytes) throws IOException {
        return LanguageDirectory.decode(bytes);
    }

    private void validateContent(String locale, String content) throws IOException {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_LANGUAGE_BYTES) {
            throw new IOException("Language file exceeds the 2 MiB safety limit");
        }
        LocaleOverlay overlay = createOverlay(locale, "upstream:" + locale, content);
        try {
            LocalizationSnapshot.create(new LocalizationCandidate(CATALOG, List.of(overlay), ENGLISH_PLURALS));
        } catch (RuntimeException exception) {
            throw new IOException("Language content is invalid: " + locale, exception);
        }
    }

    private void validateLanguagePath(File file) throws IOException {
        Path path = file.toPath().toAbsolutePath().normalize();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))) {
            throw new IOException("Language path is not a regular file: " + file.getName());
        }
    }

    private void ensureEnglish() throws IOException {
        if (sync != null) {
            try {
                sync.mergeEnglish().ifPresent(result -> logger.info("Updated BileTools language "
                        + CATALOG.englishLocale() + " from the built-in catalog: " + result.summary() + "."));
                return;
            } catch (IOException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IOException("Could not refresh the English language file", exception);
            }
        }
        File english = languageFile(CATALOG.englishLocale());
        if (!english.exists()) {
            AtomicFileIO.writeString(english.toPath(), LanguageReferenceRenderer.render(
                    CATALOG, englishHeader(CATALOG.englishLocale())));
        }
    }

    private void createCustomLanguageIfMissing(String locale) throws IOException {
        File target = languageFile(locale);
        if (target.exists()) {
            return;
        }
        String content = LanguageReferenceRenderer.render(CATALOG, englishHeader(locale),
                LanguageMeta.of(locale, CATALOG.englishLocale()));
        AtomicFileIO.writeString(target.toPath(), content);
        selfWrites.add(new SelfWrite(target, content.getBytes(StandardCharsets.UTF_8)));
    }

    private void refreshAvailableLocales() throws IOException {
        LinkedHashSet<String> choices = new LinkedHashSet<>();
        choices.add(CATALOG.englishLocale());
        if (manifest != null) {
            choices.addAll(manifest.locales());
        }
        List<String> installed = directory.scan();
        choices.addAll(installed);
        LinkedHashMap<String, LanguageMeta> discoveredMetas = new LinkedHashMap<>();
        for (String locale : installed) {
            try {
                LanguageMeta meta = directory.meta(locale);
                if (!meta.isEmpty()) {
                    discoveredMetas.put(locale, meta);
                }
            } catch (IOException exception) {
                logger.log(Level.FINE, "Could not read language metadata for " + locale, exception);
            }
        }
        ArrayList<String> discovered = new ArrayList<>(choices);
        discovered.sort(String.CASE_INSENSITIVE_ORDER);
        metas = Map.copyOf(discoveredMetas);
        availableLocales = List.copyOf(discovered);
    }

    private String normalizeConfiguredLocale(String locale) {
        String normalized = locale == null ? "" : locale.trim();
        return LanguageDirectory.LOCALE_PATTERN.matcher(normalized).matches() ? normalized : CATALOG.englishLocale();
    }

    private String canonicalLocale(String locale) {
        String requiredLocale = LanguageDirectory.requireLocale(locale);
        if (CATALOG.englishLocale().equalsIgnoreCase(requiredLocale)) {
            return CATALOG.englishLocale();
        }
        if (manifest != null && manifest.contains(requiredLocale)) {
            return manifest.canonical(requiredLocale);
        }
        return requiredLocale;
    }

    private static String normalizedLocale(String locale) {
        return locale.replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private static List<String> englishHeader(String locale) {
        return LanguageFileHeader.render(new LanguageFileHeader.Options(
                "BileTools", locale,
                List.of("The &a[&8Bile&a]: prefix is part of each chat message; edit it there.", "There is no separate prefix setting or prefix placeholder."),
                List.of("Colors and styles: &0-&f, &k-&r.", "RGB colors: &#RRGGBB, &xRRGGBB, &x&R&R&G&G&B&B, [RRGGBB].", "MiniMessage supports custom formatting. Put a backslash before & or [ to display it literally."),
                Map.ofEntries(
                        Map.entry("added", "Messages added by a language update"),
                        Map.entry("after", "Message value after the edit"),
                        Map.entry("argument", "Unexpected command argument"),
                        Map.entry("before", "Message value before the edit"),
                        Map.entry("category", "Configuration editor category"),
                        Map.entry("command", "Command path or submitted command"),
                        Map.entry("context", "Reason or trigger for a plugin reload"),
                        Map.entry("enabled", "Whether the inspected plugin is enabled"),
                        Map.entry("recovery", "Whether a running-version recovery jar is available"),
                        Map.entry("cooperative", "Whether the plugin implements cooperative reload"),
                        Map.entry("dependents", "Plugins included in the reload group"),
                        Map.entry("capability", "Availability of required teardown capabilities"),
                        Map.entry("count", "Configured remote deployment target count and plural selector"),
                        Map.entry("file", "Plugin jar filename"),
                        Map.entry("group", "Language editor message group"),
                        Map.entry("host", "Inbound sender IP address"),
                        Map.entry("installedVersion", "Currently installed plugin version"),
                        Map.entry("kept", "Local edits kept by a language update"),
                        Map.entry("key", "Command parameter key or message key"),
                        Map.entry("latestVersion", "Latest available library version"),
                        Map.entry("line", "Line number within a list message"),
                        Map.entry("locale", "Locale identifier"),
                        Map.entry("maximum", "Maximum editor input length"),
                        Map.entry("milliseconds", "Load, unload, or reload duration in milliseconds"),
                        Map.entry("new", "New setting or message value"),
                        Map.entry("old", "Previous setting or message value"),
                        Map.entry("parameter", "Command parameter name"),
                        Map.entry("permission", "Required permission node"),
                        Map.entry("personal", "Personal locale when different from the server default"),
                        Map.entry("plugin", "Plugin name"),
                        Map.entry("reason", "Failure reason"),
                        Map.entry("remaining", "Time until the next language check is allowed"),
                        Map.entry("section", "Language editor section"),
                        Map.entry("setting", "Setting name or message key"),
                        Map.entry("target", "Language selection target"),
                        Map.entry("type", "Command parameter type"),
                        Map.entry("updated", "Messages changed by a language update"),
                        Map.entry("value", "Current, default, or submitted value"),
                        Map.entry("variables", "Required message variable names"),
                        Map.entry("version", "Requested or listed library plugin version")
                )));
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
        List<String> knownLocales = List.copyOf(observedLocales);
        try {
            automaticReloadIo.execute(() -> captureAutomaticRead(generation, file, locale, knownLocales));
        } catch (RejectedExecutionException rejected) {
            automaticReadInFlight.set(false);
            if (!closed) {
                automaticReadRequested = true;
            }
        }
    }

    private void captureAutomaticRead(long generation, File file, String locale, List<String> knownLocales) {
        ArrayList<AutomaticReadResult> results = new ArrayList<>();
        results.add(readAutomaticFile(file, locale));
        Set<String> capturedLocales = new HashSet<>();
        capturedLocales.add(locale);
        for (String knownLocale : knownLocales) {
            if (!sameLocale(knownLocale, locale)) {
                results.add(readAutomaticFile(languageFile(knownLocale), knownLocale));
                capturedLocales.add(knownLocale);
            }
        }
        Throwable directoryFailure = null;
        try (Stream<Path> files = Files.list(languageDirectory.toPath())) {
            for (Path path : files.toList()) {
                Optional<String> installedLocale = directory.locale(path);
                if (installedLocale.isPresent() && !sameLocale(installedLocale.get(), locale)
                        && !capturedLocales.contains(installedLocale.get())) {
                    results.add(readAutomaticFile(path.toFile(), installedLocale.get()));
                }
            }
        } catch (IOException | RuntimeException failure) {
            directoryFailure = failure;
        }
        completedAutomaticRead.set(new AutomaticReadBatch(generation, List.copyOf(results), directoryFailure));
        automaticReadInFlight.set(false);
    }

    private AutomaticReadResult readAutomaticFile(File file, String locale) {
        AutomaticReadResult result;
        try {
            result = new AutomaticReadResult(file, locale, snapshotReader.read(file), null);
        } catch (NoSuchFileException missing) {
            result = new AutomaticReadResult(file, locale, null, null);
        } catch (IOException | RuntimeException failure) {
            result = new AutomaticReadResult(file, locale, null, failure);
        }
        return result;
    }

    private void consumeAutomaticRead() {
        AutomaticReadBatch batch = completedAutomaticRead.getAndSet(null);
        if (batch != null && batch.generation() == automaticReadGeneration) {
            for (AutomaticReadResult result : batch.files()) {
                consumeAutomaticFile(result);
            }
            if (batch.failure() != null) {
                reportPersonalReadFailure("directory", batch.failure());
            } else {
                personalReadFailures.remove("directory");
            }
        }
        if (automaticReadRequested && !automaticReadInFlight.get()) {
            automaticReadRequested = false;
            queueAutomaticRead();
        }
    }

    private void consumeAutomaticFile(AutomaticReadResult result) {
        observedLocales.add(result.locale());
        if (result.file().equals(activeFile.get()) && sameLocale(result.locale(), configuredLocale)) {
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
        } else if (result.failure() != null) {
            reportPersonalReadFailure(result.locale(), result.failure());
        } else {
            byte[] content = result.content() == null ? new byte[0] : result.content();
            if (Arrays.equals(content, appliedPersonalContent.get(result.locale()))) {
                pendingPersonalContent.remove(result.locale());
            } else {
                pendingPersonalContent.put(result.locale(), new AutomaticReadResult(
                        result.file(), result.locale(), content, null));
            }
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
            File file,
            String locale,
            byte[] content,
            Throwable failure
    ) {
        private AutomaticReadResult {
            content = content == null ? null : content.clone();
        }
    }

    private record AutomaticReadBatch(long generation, List<AutomaticReadResult> files, Throwable failure) {
    }

    record UpstreamEndpoints(URI apiRoot, URI rawRoot, Duration minimumCheckInterval) {
        static final UpstreamEndpoints GITHUB = new UpstreamEndpoints(GitHubLanguageSource.API_ROOT,
                GitHubLanguageSource.RAW_ROOT, LanguageUpstreamSync.MINIMUM_CHECK_INTERVAL);

        UpstreamEndpoints {
            Objects.requireNonNull(apiRoot, "apiRoot");
            Objects.requireNonNull(rawRoot, "rawRoot");
            Objects.requireNonNull(minimumCheckInterval, "minimumCheckInterval");
        }
    }

    private record UpstreamState(LanguageSourceManifest manifest, LanguageUpstreamSync sync, Throwable failure) {
    }

    private record SelfWrite(File file, byte[] content) {
    }
}
