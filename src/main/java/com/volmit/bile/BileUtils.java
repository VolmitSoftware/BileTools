package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import com.volmit.bile.watch.JarSnapshotStager;
import com.volmit.bile.paper.NativePaperSupport;
import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.UnknownDependencyException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BileUtils {
    private static final AtomicBoolean COMMAND_REFRESH_QUEUED = new AtomicBoolean();
    private static final int ZIP_READ_RETRY_LIMIT = 2;
    private static final Pattern RUNTIME_ARCHIVE_NAME = Pattern.compile(
            "^([A-Za-z0-9_-]+)-([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:-[0-9]+)?\\.jar$");
    private static final Map<String, File> SOURCE_FILE_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, WeakReference<Plugin>> RECOVERY_CAPTURED_INSTANCES = new ConcurrentHashMap<>();
    private static final Map<String, RunningPluginMetadata> RUNNING_PLUGIN_METADATA = new ConcurrentHashMap<>();
    private static final Map<String, File> RUNTIME_PLUGIN_FILES = new ConcurrentHashMap<>();
    private static final Map<String, CachedJarMeta> JAR_META_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<String>> LOAD_VISITING = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Set<String>> UNLOAD_VISITING = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Map<String, RecoveryEntry>> RECOVERY_SOURCES = new ThreadLocal<>();
    private static PluginRecoveryStore recoveryStore;
    private static final Method PLUGINS_FOLDER_API = findPublicMethod(Bukkit.class, "getPluginsFolder");

    private static String key(String pluginName) {
        return pluginName.toLowerCase(Locale.ROOT);
    }

    public static void initializeRecoveryStore() {
        String session = ProcessHandle.current().pid() + "-" + ManagementFactory.getRuntimeMXBean().getStartTime();
        recoveryStore = new PluginRecoveryStore(BileTools.bile.getDataFolder().toPath()
                .resolve("recovery").resolve(session));
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled()) {
                rememberRunningPlugin(plugin);
            }
        }
    }

    public static void rememberRunningPlugin(Plugin plugin) {
        if (recoveryStore == null) {
            return;
        }
        String pluginKey = key(plugin.getName());
        WeakReference<Plugin> captured = RECOVERY_CAPTURED_INSTANCES.get(pluginKey);
        if (captured != null && captured.get() == plugin && recoveryStore.contains(plugin.getName())) {
            return;
        }
        try {
            recoveryStore.forget(plugin.getName());
            RECOVERY_CAPTURED_INSTANCES.remove(pluginKey);
            File loadedFile = loadedPluginFile(plugin);
            if (loadedFile == null || !loadedFile.isFile()) {
                throw new IOException("Cannot locate loaded jar for " + plugin.getName());
            }
            File sourceFile = loadedFile;
            File parent = loadedFile.getParentFile();
            File retainedSource = SOURCE_FILE_OVERRIDES.get(pluginKey);
            String runtimeSourceName = runtimeSourceBaseName(loadedFile);
            if (retainedSource != null
                    && (runtimeSourceName != null || runningMetadata(plugin) != null)) {
                sourceFile = retainedSource;
            } else if (runtimeSourceName != null) {
                sourceFile = new File(getPluginsFolder(), runtimeSourceName);
            } else if (parent != null && parent.getName().equals(".paper-remapped")) {
                sourceFile = new File(parent.getParentFile(), loadedFile.getName());
            }
            registerLoadedFileOverride(plugin.getName(), sourceFile);
            PluginJarMetadata metadata = readRuntimePluginMetadata(loadedFile);
            PluginDescriptionFile description = metadata.description();
            if (!plugin.getName().equals(description.getName())
                    || !plugin.getDescription().getMain().equals(description.getMain())
                    || !plugin.getDescription().getVersion().equals(description.getVersion())) {
                throw new IOException("Loaded jar identity changed before recovery capture for " + plugin.getName());
            }
            if (runningMetadata(plugin) == null) {
                rememberRunningMetadata(plugin, metadata);
            }
            recoveryStore.remember(plugin.getName(), loadedFile.toPath());
            RECOVERY_CAPTURED_INSTANCES.put(pluginKey, new WeakReference<>(plugin));
        } catch (IOException | InvalidDescriptionException exception) {
            BileTools.warn("Could not retain the running version of " + plugin.getName(), exception);
        }
    }

    private static void rememberRunningMetadata(Plugin plugin, PluginJarMetadata metadata) {
        RUNNING_PLUGIN_METADATA.put(key(plugin.getName()),
                new RunningPluginMetadata(new WeakReference<>(plugin), metadata));
    }

    private static PluginJarMetadata runningMetadata(Plugin plugin) {
        RunningPluginMetadata captured = RUNNING_PLUGIN_METADATA.get(key(plugin.getName()));
        return captured != null && captured.plugin().get() == plugin ? captured.metadata() : null;
    }

    private record RunningPluginMetadata(WeakReference<Plugin> plugin, PluginJarMetadata metadata) {
    }

    private static File loadedPluginFile(Plugin plugin) {
        try {
            Field field = findFieldInHierarchy(plugin.getClass(), "file");
            if (field != null && field.get(plugin) instanceof File file) {
                return file;
            }
        } catch (ReflectiveOperationException exception) {
            BileTools.warn("Could not inspect the loaded jar of " + plugin.getName(), exception);
        }
        return getPluginFile(plugin);
    }

    public static List<Plugin> unloadOrder(Plugin root) throws IOException, InvalidDescriptionException {
        List<Plugin> ordered = new ArrayList<>();
        collectUnloadOrder(root, ordered, new HashSet<>());
        return List.copyOf(ordered);
    }

    public static ReloadInspection inspect(Plugin plugin) throws IOException, InvalidDescriptionException {
        List<String> dependents = new ArrayList<>();
        for (Plugin affected : unloadOrder(plugin)) {
            if (affected != plugin) {
                dependents.add(affected.getName());
            }
        }
        String capability = "available";
        try {
            validateUnload(plugin);
        } catch (InvalidPluginException exception) {
            capability = exception.getMessage();
        }
        return new ReloadInspection(plugin.getName(), plugin.isEnabled(),
                recoveryStore != null && recoveryStore.contains(plugin.getName()),
                BukkitLifecycle.isParticipant(plugin), List.copyOf(dependents), capability);
    }

    public record ReloadInspection(String plugin, boolean enabled, boolean recovery, boolean cooperative,
                                   List<String> dependents, String capability) {
    }

    public static CompletionStage<Void> reloadAsync(Plugin plugin) {
        return reloadFromSnapshotAsync(plugin, null, getPluginFile(plugin), Map.of(), Set.of())
                .thenApply(ignored -> null);
    }

    public static CompletionStage<Set<String>> reloadFromSnapshotAsync(Plugin plugin, File snapshot, File source) {
        return reloadFromSnapshotAsync(plugin, snapshot, source, Map.of(), Set.of());
    }

    public static CompletionStage<Set<String>> reloadFromSnapshotAsync(Plugin plugin, File snapshot, File source,
            Map<String, SnapshotLoadSource> snapshots, Set<String> protectedPlugins) {
        return BukkitLifecycle.execute(() -> createReloadPlan(plugin, snapshot, source, snapshots, protectedPlugins, false));
    }

    public static CompletionStage<Set<String>> replaceProvidedIdentityFromSnapshotAsync(Plugin plugin, File snapshot,
            File source, Map<String, SnapshotLoadSource> snapshots, Set<String> protectedPlugins) {
        return BukkitLifecycle.execute(() -> createReloadPlan(plugin, snapshot, source, snapshots, protectedPlugins, true));
    }

    public static CompletionStage<Void> unloadAsync(Plugin plugin) {
        return BukkitLifecycle.execute(() -> createLifecyclePlan(plugin, ReloadAware.PreUnloadReason.HOT_UNLOAD,
                () -> {
                    unload(plugin);
                    return null;
                }));
    }

    public static CompletionStage<Void> deleteAsync(File source) {
        return BukkitLifecycle.execute(() -> createLifecyclePlan(getPlugin(source),
                ReloadAware.PreUnloadReason.HOT_UNLOAD, () -> {
                    delete(source);
                    return null;
                }));
    }

    public static CompletionStage<Void> loadAsync(File source) {
        return loadFromSnapshotAsync(source, source);
    }

    public static CompletionStage<Void> loadFromSnapshotAsync(File snapshot, File source) {
        return BukkitLifecycle.execute(() -> {
            if (snapshot == null || source == null) {
                throw new InvalidPluginException("Cannot load without a staged jar and authoritative source path");
            }
            PluginDescriptionFile description = getPluginDescription(snapshot);
            Plugin existing = resolveLoadedIdentity(description.getName(), Bukkit.getPluginManager());
            if (existing != null) {
                BukkitLifecycle.Plan<Set<String>> replacement = createReloadPlan(existing, snapshot, source,
                        Map.of(), Set.of(), false);
                return new BukkitLifecycle.Plan<>(replacement.plugins(), replacement.reason(), () -> {
                    replacement.mutation().call();
                    return null;
                }, replacement.recovery(), replacement.close());
            }
            return createLoadPlan(snapshot, source);
        });
    }

    static Plugin resolveLoadedIdentity(String name, PluginManager manager) throws InvalidPluginException {
        for (Plugin plugin : manager.getPlugins()) {
            if (plugin.getName().equalsIgnoreCase(name)) {
                return plugin;
            }
        }
        Plugin owner = manager.getPlugin(name);
        if (owner != null) {
            throw new InvalidPluginException("Plugin identity " + name + " is already provided by " + owner.getName()
                    + "; use an explicit provided-identity replacement");
        }
        return null;
    }

    private static BukkitLifecycle.Plan<Void> createLoadPlan(File snapshot, File source) throws Exception {
        List<JarSnapshotStager.StagedJar> staged = new ArrayList<>();
        Map<String, RuntimeLoadArtifact> artifacts = new LinkedHashMap<>();
        try {
            File pinned = stageReplacement(snapshot, staged).staged().toFile();
            RuntimeLoadArtifact artifact = prepareSnapshotRuntimeLoadArtifact(pinned, source, false);
            artifacts.put(cacheKey(pinned), artifact);
            prepareMissingDependencyArtifacts(pinned, artifacts, new HashSet<>());
            validatePreparedDependencies(List.of(), artifacts);
            return new BukkitLifecycle.Plan<>(List.of(), ReloadAware.PreUnloadReason.HOT_RELOAD, () -> {
                load(pinned, false, artifact, artifacts, source);
                return null;
            }, failure -> {}, () -> {
                discardArtifacts(artifacts);
                staged.forEach(JarSnapshotStager.StagedJar::delete);
            });
        } catch (Exception | Error failure) {
            discardArtifacts(artifacts);
            staged.forEach(JarSnapshotStager.StagedJar::delete);
            throw failure;
        }
    }

    private static BukkitLifecycle.Plan<Set<String>> createReloadPlan(Plugin plugin, File snapshot, File source,
            Map<String, SnapshotLoadSource> snapshots, Set<String> protectedPlugins, boolean replaceIdentity) throws Exception {
        File incoming = snapshot == null ? getPluginFile(plugin) : snapshot;
        if (incoming == null || source == null) {
            throw new InvalidPluginException("Cannot resolve replacement jar for " + plugin.getName());
        }
        String replacementName = validateReloadIdentity(plugin.getDescription(), getPluginDescription(incoming), replaceIdentity);
        boolean changedIdentity = !plugin.getName().equalsIgnoreCase(replacementName);
        Plugin identityOwner = Bukkit.getPluginManager().getPlugin(replacementName);
        if (changedIdentity && identityOwner != null && identityOwner != plugin) {
            throw new InvalidPluginException("Replacement identity " + replacementName + " is already loaded");
        }
        List<JarSnapshotStager.StagedJar> staged = new ArrayList<>();
        Map<String, RuntimeLoadArtifact> artifacts = new LinkedHashMap<>();
        RecoveryGroup recovery = null;
        try {
            JarSnapshotStager.StagedJar root = stageReplacement(incoming, staged);
            Map<String, SnapshotLoadSource> pinned = new LinkedHashMap<>(normalizeSnapshotSources(snapshots));
            Set<String> protectedNames = normalizePluginNames(protectedPlugins);
            List<Plugin> plugins = unloadOrder(plugin);
            for (Plugin affected : plugins) {
                validateUnload(affected);
                if (affected == plugin || pinned.containsKey(key(affected.getName()))) {
                    continue;
                }
                if (protectedNames.contains(key(affected.getName()))) {
                    throw new SnapshotUnavailableException("The staged snapshot for dependent " + affected.getName() + " was superseded");
                }
                File affectedSource = getPluginFile(affected);
                if (affectedSource == null) {
                    throw new IOException("Cannot locate dependent " + affected.getName());
                }
                JarSnapshotStager.StagedJar copy = stageReplacement(affectedSource, staged);
                pinned.put(key(affected.getName()), new SnapshotLoadSource(affected.getName(), copy.staged().toFile(), affectedSource));
            }
            File rootFile = root.staged().toFile();
            RuntimeLoadArtifact artifact = prepareSnapshotRuntimeLoadArtifact(rootFile, source, true);
            artifacts.put(cacheKey(rootFile), artifact);
            prepareMissingDependencyArtifacts(rootFile, artifacts, new HashSet<>());
            prepareDependentReloadArtifacts(plugin, artifacts, new HashSet<>(), pinned, protectedNames);
            validatePreparedDependencies(plugins, artifacts);
            recovery = retainRecoveryGroup(plugin);
            RecoveryGroup retained = recovery;
            return new BukkitLifecycle.Plan<>(plugins, ReloadAware.PreUnloadReason.HOT_RELOAD,
                    () -> reload(plugin, rootFile, source, pinned, replaceIdentity, artifacts), failure -> {
                        if (changedIdentity) {
                            Plugin replacement = getPluginByExactName(replacementName);
                            if (replacement != null) {
                                try {
                                    unload(replacement, ReloadAware.PreUnloadReason.HOT_RELOAD);
                                } catch (Throwable cleanupFailure) {
                                    failure.addSuppressed(cleanupFailure);
                                    BileTools.warn("Could not remove failed replacement " + replacementName, cleanupFailure);
                                }
                            }
                        }
                        retained.restore(failure);
                    },
                    () -> {
                        retained.close();
                        discardArtifacts(artifacts);
                        staged.forEach(JarSnapshotStager.StagedJar::delete);
                    });
        } catch (Exception | Error failure) {
            if (recovery != null) {
                recovery.close();
            }
            discardArtifacts(artifacts);
            staged.forEach(JarSnapshotStager.StagedJar::delete);
            throw failure;
        }
    }

    private static JarSnapshotStager.StagedJar stageReplacement(File source, List<JarSnapshotStager.StagedJar> staged)
            throws IOException {
        JarSnapshotStager.StagedJar copy = JarSnapshotStager.stage(source.toPath(),
                BileTools.bile.getDataFolder().toPath().resolve("replacement-stage"), 0L,
                JarSnapshotStager.BUKKIT_DESCRIPTOR_ENTRIES);
        staged.add(copy);
        return copy;
    }

    private static <T> BukkitLifecycle.Plan<T> createLifecyclePlan(Plugin plugin,
            ReloadAware.PreUnloadReason reason, Callable<T> operation) throws Exception {
        List<Plugin> plugins = plugin == null ? List.of() : unloadOrder(plugin);
        for (Plugin affected : plugins) {
            validateUnload(affected);
        }
        RecoveryGroup recovery = plugin == null ? null : retainRecoveryGroup(plugin);
        return new BukkitLifecycle.Plan<>(plugins, reason, operation,
                failure -> {
                    if (recovery != null) {
                        recovery.restore(failure);
                    }
                }, () -> {
                    if (recovery != null) {
                        recovery.close();
                    }
                });
    }

    private static void discardArtifacts(Map<String, RuntimeLoadArtifact> artifacts) {
        for (RuntimeLoadArtifact artifact : artifacts.values()) {
            artifact.discard();
        }
    }

    private static void validatePreparedDependencies(List<Plugin> unloading,
            Map<String, RuntimeLoadArtifact> artifacts) throws InvalidPluginException {
        Set<String> surviving = new HashSet<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!unloading.contains(plugin)) {
                surviving.add(plugin.getName());
                surviving.addAll(plugin.getDescription().getProvides());
            }
        }
        List<PluginDependencyPlan.Identity> replacements = new ArrayList<>();
        for (RuntimeLoadArtifact artifact : new LinkedHashSet<>(artifacts.values())) {
            PluginJarMetadata metadata = artifact.metadata;
            replacements.add(new PluginDependencyPlan.Identity(metadata.description().getName(),
                    metadata.description().getProvides(), metadata.requiredDependencies()));
        }
        PluginDependencyPlan.validate(replacements, surviving);
    }

    private static void validateUnload(Plugin plugin) throws InvalidPluginException {
        NativePaperSupport.validateLoaded(plugin);
        try {
            if (!ServerPlatform.isPaperRuntime()) {
                SpigotPluginLoaderCleanup.validate(plugin);
            }
            PlatformTasks.validatePluginTaskCancellation(plugin);
            if (ServerPlatform.isPaperRuntime()) {
                paperClassloaderRegistration(plugin);
                PaperPluginTracking tracking = paperPluginTracking();
                findCompatibleMethod(tracking.dependencyTree().getClass(), "remove",
                        Plugin.class.getMethod("getPluginMeta").invoke(plugin));
                paperProviders("PLUGIN");
                paperProviders("BOOTSTRAPPER");
            }
            PluginManager manager = Bukkit.getPluginManager();
            if (readPluginList(manager) == null || readLookupNames(manager) == null || readCommandMap(manager) == null
                    || !(requiredFieldValue(readCommandMap(manager), "knownCommands") instanceof Map<?, ?>)) {
                throw new InvalidPluginException("Required plugin or command registries are unavailable; cannot unload " + plugin.getName());
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new InvalidPluginException("Cannot inspect plugin registries before unloading " + plugin.getName(), exception);
        }
    }

    private static void collectUnloadOrder(Plugin root, List<Plugin> ordered, Set<String> visited)
            throws IOException, InvalidDescriptionException {
        if (!visited.add(key(root.getName()))) {
            return;
        }
        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate != root && dependsOn(candidate, root)) {
                collectUnloadOrder(candidate, ordered, visited);
            }
        }
        ordered.add(root);
    }

    private static RecoveryGroup retainRecoveryGroup(Plugin root) throws IOException, InvalidDescriptionException {
        if (recoveryStore == null) {
            throw new IOException("The running-version recovery store is unavailable");
        }
        List<RecoveryEntry> entries = new ArrayList<>();
        try {
            for (Plugin plugin : unloadOrder(root)) {
                File source = getPluginFile(plugin);
                if (source == null) {
                    throw new IOException("Cannot resolve the source of " + plugin.getName());
                }
                PluginJarMetadata metadata = runningMetadata(plugin);
                if (metadata == null) {
                    throw new IOException("Running dependency metadata is unavailable for " + plugin.getName());
                }
                entries.add(new RecoveryEntry(plugin.getName(), source, recoveryStore.retain(plugin.getName()), metadata));
            }
            return new RecoveryGroup(entries);
        } catch (IOException | InvalidDescriptionException exception) {
            for (RecoveryEntry entry : entries) {
                entry.snapshot().delete();
            }
            throw exception;
        }
    }

    private record RecoveryEntry(String name, File source, JarSnapshotStager.StagedJar snapshot, PluginJarMetadata metadata) {
    }

    private record RecoveryGroup(List<RecoveryEntry> entries) implements AutoCloseable {
        private void restore(Throwable failure) {
            Map<String, RecoveryEntry> sources = new LinkedHashMap<>();
            for (RecoveryEntry entry : entries) {
                sources.put(key(entry.name()), entry);
                for (String provided : entry.metadata().description().getProvides()) {
                    sources.putIfAbsent(key(provided), entry);
                }
                try {
                    recoveryStore.remember(entry.name(), entry.snapshot().staged());
                } catch (IOException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                    BileTools.warn("Could not preserve the recovery copy of " + entry.name(), restoreFailure);
                }
            }
            RECOVERY_SOURCES.set(sources);
            try {
                for (RecoveryEntry entry : entries) {
                    Plugin current = getPluginByExactName(entry.name());
                    if (current != null) {
                        try {
                            unload(current, ReloadAware.PreUnloadReason.HOT_RELOAD);
                        } catch (Throwable cleanupFailure) {
                            failure.addSuppressed(cleanupFailure);
                            BileTools.warn("Recovery teardown failed for " + entry.name(), cleanupFailure);
                        }
                    }
                }
                for (int index = entries.size() - 1; index >= 0; index--) {
                    RecoveryEntry entry = entries.get(index);
                    if (getPluginByExactName(entry.name()) != null) {
                        continue;
                    }
                    try {
                        File snapshot = entry.snapshot().staged().toFile();
                        RuntimeLoadArtifact artifact = prepareSnapshotRuntimeLoadArtifact(snapshot, entry.source(), true);
                        artifact.metadata = entry.metadata();
                        load(snapshot, true, artifact, Map.of(), entry.source());
                        BileTools.info("Restored running version of " + entry.name() + " after failed replacement.");
                    } catch (Throwable restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                        BileTools.severe("Could not restore " + entry.name() + "; a server restart is required.", restoreFailure);
                    }
                }
            } finally {
                RECOVERY_SOURCES.remove();
            }
        }

        @Override
        public void close() {
            for (RecoveryEntry entry : entries) {
                entry.snapshot().delete();
            }
        }
    }

    private record CachedJarMeta(long length, long lastModified, String pluginName, String pluginVersion) {
    }

    private record PluginJarMetadata(PluginDescriptionFile description,
                                     List<String> requiredDependencies,
                                     List<String> optionalDependencies) {
        private PluginJarMetadata {
            requiredDependencies = List.copyOf(requiredDependencies);
            optionalDependencies = List.copyOf(optionalDependencies);
        }
    }

    private static final class RuntimeLoadArtifact {
        private final File sourceFile;
        private final File runtimeFile;
        private final RuntimeArtifactLease lease;
        private File authoritativeFile;
        private PluginJarMetadata metadata;
        private boolean retained;

        private RuntimeLoadArtifact(File sourceFile, File runtimeFile) throws IOException, InvalidDescriptionException {
            this.sourceFile = sourceFile;
            this.runtimeFile = runtimeFile;
            this.authoritativeFile = sourceFile;
            try {
                lease = RuntimeArtifactLease.acquire(runtimeFile.toPath());
            } catch (IOException | RuntimeException exception) {
                if (!sameFile(sourceFile, runtimeFile)) {
                    deleteRuntimePluginFile(runtimeFile);
                }
                throw exception;
            }
            try {
                metadata = readRuntimePluginMetadata(sourceFile);
            } catch (IOException | InvalidDescriptionException exception) {
                try {
                    lease.close();
                } catch (IOException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                if (!sameFile(sourceFile, runtimeFile)) {
                    deleteRuntimePluginFile(runtimeFile);
                }
                throw exception;
            }
        }

        private File runtimeFile() {
            return runtimeFile;
        }

        private boolean temporary() {
            return !sameFile(sourceFile, runtimeFile);
        }

        private void retain(String pluginName) {
            if (!temporary()) {
                releaseLease();
                return;
            }

            File previous = RUNTIME_PLUGIN_FILES.put(key(pluginName), runtimeFile);
            retained = true;
            runtimeFile.deleteOnExit();
            releaseLease();
            if (previous != null && !sameFile(previous, runtimeFile)) {
                deleteRuntimePluginFile(previous);
            }
        }

        private void discard() {
            releaseLease();
            if (!retained && temporary()) {
                deleteRuntimePluginFile(runtimeFile);
            }
        }

        private void releaseLease() {
            try {
                lease.close();
            } catch (IOException exception) {
                throw new IllegalStateException("Could not release runtime artifact lease for " + runtimeFile, exception);
            }
        }
    }

    public static final class RestartRequiredException extends InvalidPluginException {
        public RestartRequiredException(String message) {
            super(message);
        }
    }

    private static void registerLoadedFileOverride(String pluginName, File sourceFile) {
        if (pluginName == null || sourceFile == null) {
            return;
        }

        SOURCE_FILE_OVERRIDES.put(key(pluginName), sourceFile);
    }

    private static void clearLoadedFileOverride(String pluginName) {
        if (pluginName == null) {
            return;
        }

        SOURCE_FILE_OVERRIDES.remove(key(pluginName));
    }

    private static void releaseRuntimePluginFile(String pluginName) {
        if (pluginName == null) {
            return;
        }

        deleteRuntimePluginFile(RUNTIME_PLUGIN_FILES.remove(key(pluginName)));
    }

    private static void deleteRuntimePluginFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            file.deleteOnExit();
            if (BileTools.bile != null) {
                BileTools.bile.getLogger().warning("Could not delete runtime plugin file " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    public static void recoverRuntimePluginFiles() {
        if (BileTools.bile == null) {
            return;
        }

        File runtimeDirectory = new File(BileTools.bile.getDataFolder(), "runtime-plugins");
        File[] runtimeFiles = runtimeDirectory.listFiles();
        if (runtimeFiles == null) {
            return;
        }

        Field pluginFileField = findFieldInHierarchy(JavaPlugin.class, "file");
        if (pluginFileField == null) {
            for (File runtimeFile : runtimeFiles) {
                runtimeFile.deleteOnExit();
            }
            return;
        }

        Set<String> activeRuntimeFiles = new HashSet<>();
        try {
            for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (!(plugin instanceof JavaPlugin)) {
                    continue;
                }

                Object pluginFile = pluginFileField.get(plugin);
                if (!(pluginFile instanceof File)) {
                    continue;
                }

                File loadedFile = (File) pluginFile;
                File runtimeFile = managedRuntimeArchive(loadedFile, runtimeDirectory);
                if (runtimeFile == null) {
                    continue;
                }

                activeRuntimeFiles.add(cacheKey(loadedFile));
                activeRuntimeFiles.add(cacheKey(runtimeFile));
                runtimeFile.deleteOnExit();
                RUNTIME_PLUGIN_FILES.put(key(plugin.getName()), runtimeFile);
                clearLoadedFileOverride(plugin.getName());
                File sourceFile = findRuntimeSourceFile(plugin.getName(), runtimeFile);
                if (sourceFile != null) {
                    registerLoadedFileOverride(plugin.getName(), sourceFile);
                } else {
                    BileTools.bile.getLogger().warning("Could not recover the source jar for active runtime plugin " + plugin.getName());
                }
            }
        } catch (Throwable e) {
            for (File runtimeFile : runtimeFiles) {
                runtimeFile.deleteOnExit();
            }
            BileTools.bile.getLogger().log(Level.WARNING, "Could not inspect active runtime plugin files", e);
            return;
        }

        int removed = 0;
        for (File runtimeFile : runtimeFiles) {
            if (runtimeFile.isDirectory()) {
                continue;
            }
            if (activeRuntimeFiles.contains(cacheKey(runtimeFile))) {
                continue;
            }
            try {
                if (RuntimeArtifactLease.isActive(runtimeFile.toPath())) {
                    continue;
                }
            } catch (IOException | RuntimeException exception) {
                BileTools.bile.getLogger().log(Level.WARNING,
                        "Could not inspect runtime artifact lease for " + runtimeFile.getName(), exception);
                continue;
            }
            deleteRuntimePluginFile(runtimeFile);
            removed++;
        }

        if (!activeRuntimeFiles.isEmpty() || removed > 0) {
            int removedCount = removed;
            BileTools.debug(() -> "Runtime plugin files: active=" + activeRuntimeFiles.size()
                    + ", staleRemoved=" + removedCount + ".");
        }
    }

    private static void delete(Plugin p) throws IOException {
        File f = getPluginFile(p);
        if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
            backup(p);
        }
        unload(p);
        Files.deleteIfExists(f.toPath());
    }

    private static void delete(File f) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        if (getPlugin(f) != null) {
            delete(getPlugin(f));
            return;
        }

        if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
            PluginDescriptionFile fx = getPluginDescription(f);
            copy(f, new File(getBackupLocation(fx.getName()), fx.getVersion() + ".jar"));
        }
        Files.deleteIfExists(f.toPath());
    }

    private static Set<String> reload(Plugin p,
                                      File snapshotFile,
                                      File authoritativeFile,
                                      Map<String, SnapshotLoadSource> availableSnapshots,
                                      boolean allowProvidedIdentityReplacement,
                                      Map<String, RuntimeLoadArtifact> runtimeArtifacts) throws IOException, UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, InvalidConfigurationException {
        if (p == null) {
            throw new InvalidPluginException("Cannot reload null plugin");
        }

        String pluginName = p.getName();
        long startNs = System.nanoTime();
        File currentSource = getPluginFile(p);
        File loadSource = snapshotFile == null ? currentSource : snapshotFile;
        File retainedSource = authoritativeFile == null ? loadSource : authoritativeFile;
        if (loadSource == null || !loadSource.isFile()) {
            throw new InvalidPluginException("Cannot resolve plugin jar for " + pluginName);
        }
        PluginDescriptionFile loadDescription = getPluginDescription(loadSource);
        String loadedPluginName = validateReloadIdentity(
                p.getDescription(), loadDescription, allowProvidedIdentityReplacement);
        Map<String, SnapshotLoadSource> snapshots = normalizeSnapshotSources(availableSnapshots);
        Set<String> loadedSnapshots = new LinkedHashSet<>();
        Plugin reloaded = null;

        try {
            RuntimeLoadArtifact runtimeArtifact = runtimeArtifacts.get(cacheKey(loadSource));

            if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
                if (snapshotFile == null) {
                    backup(p);
                } else {
                    backupSnapshot(loadSource);
                }
            }

            long unloadStartNs = System.nanoTime();
            Set<File> x = unload(p, ReloadAware.PreUnloadReason.HOT_RELOAD);
            long unloadMs = nanosToMillis(System.nanoTime() - unloadStartNs);

            long loadStartNs = System.nanoTime();
            load(loadSource, true, runtimeArtifact, runtimeArtifacts, retainedSource);
            long loadMs = nanosToMillis(System.nanoTime() - loadStartNs);
            reloaded = getPluginByExactName(loadedPluginName);
            if (reloaded == null) {
                throw new InvalidPluginException("Reloaded plugin " + loadedPluginName + " was not registered");
            }

            long dependentsStartNs = System.nanoTime();
            for (File i : x) {
                if (i == null) {
                    continue;
                }
                SnapshotLoadSource dependentSnapshot = snapshotSourceFor(i, snapshots);
                if (dependentSnapshot != null && dependentSnapshot.snapshotFile().isFile()) {
                    load(
                            dependentSnapshot.snapshotFile(),
                            true,
                            runtimeArtifacts.get(cacheKey(dependentSnapshot.authoritativeFile())),
                            runtimeArtifacts,
                            dependentSnapshot.authoritativeFile());
                    loadedSnapshots.add(dependentSnapshot.pluginName());
                } else if (i.isFile()) {
                    load(i, true, runtimeArtifacts.get(cacheKey(i)), runtimeArtifacts);
                }
            }
            long dependentsMs = nanosToMillis(System.nanoTime() - dependentsStartNs);

            HealthCheckResult health = verifyPluginHealth(reloaded, runtimeArtifact.runtimeFile());
            if (!health.ok()) {
                throw new InvalidPluginException("Post-reload health check failed for " + pluginName + ": " + health.summary());
            }

            long totalMs = nanosToMillis(System.nanoTime() - startNs);
            logTiming("reload " + pluginName, totalMs,
                    "unload=" + unloadMs + "ms",
                    "dependents=" + dependentsMs + "ms",
                    "load=" + loadMs + "ms",
                    "health=ok");
            if (snapshotFile != null) {
                loadedSnapshots.add(loadedPluginName);
            }
            return Set.copyOf(loadedSnapshots);
        } finally {
            for (RuntimeLoadArtifact runtimeArtifact : runtimeArtifacts.values()) {
                runtimeArtifact.discard();
            }
        }
    }

    static String validateReloadIdentity(PluginDescriptionFile currentDescription,
                                         PluginDescriptionFile loadDescription,
                                         boolean allowProvidedIdentityReplacement) throws InvalidPluginException {
        if (currentDescription == null || loadDescription == null) {
            throw new InvalidPluginException("Cannot validate a missing plugin identity");
        }
        String currentPluginName = currentDescription.getName();
        String loadedPluginName = loadDescription.getName();
        boolean identityChanged = !currentPluginName.equalsIgnoreCase(loadedPluginName);
        if (identityChanged && !allowProvidedIdentityReplacement) {
            throw new RestartRequiredException(
                    "Cannot hot-reload " + currentPluginName + " as " + loadedPluginName
                            + "; a full server restart is required");
        }
        if (identityChanged && !providesIdentity(loadDescription, currentPluginName)) {
            throw new RestartRequiredException(
                    "Cannot hot-replace " + currentPluginName + " with " + loadedPluginName
                            + " unless the replacement declares provides: [" + currentPluginName
                            + "]; a full server restart is required");
        }
        for (String providedName : currentDescription.getProvides()) {
            if (!providesIdentity(loadDescription, providedName)) {
                throw new RestartRequiredException(
                        "Cannot hot-reload " + currentPluginName + " because the replacement no longer provides "
                                + providedName + "; a full server restart is required");
            }
        }
        return loadedPluginName;
    }

    private static boolean providesIdentity(PluginDescriptionFile description, String pluginName) {
        return description.getName().equalsIgnoreCase(pluginName)
                || containsPluginName(description.getProvides(), pluginName);
    }

    public record HealthCheckResult(boolean ok, List<String> failures) {
        public String summary() {
            if (failures == null || failures.isEmpty()) {
                return "ok";
            }
            return String.join("; ", failures);
        }
    }

    public static HealthCheckResult verifyPluginHealth(Plugin plugin, File sourceFile) {
        List<String> failures = new ArrayList<>();
        if (BileTools.cfg != null && !BileTools.cfg.isHealthCheck()) {
            return new HealthCheckResult(true, failures);
        }

        if (plugin == null) {
            failures.add("plugin instance is null");
            return new HealthCheckResult(false, failures);
        }

        if (!plugin.isEnabled()) {
            failures.add("plugin is not enabled");
        }

        Plugin registered = Bukkit.getPluginManager().getPlugin(plugin.getName());
        if (registered == null) {
            failures.add("not registered in PluginManager");
        } else if (registered != plugin) {
            failures.add("PluginManager holds a different instance");
        }

        if (!Bukkit.getPluginManager().isPluginEnabled(plugin)) {
            failures.add("PluginManager reports disabled");
        }

        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            if (loader == null) {
                failures.add("classloader is null");
            }
        } catch (Throwable t) {
            failures.add("classloader inaccessible: " + t.getClass().getSimpleName());
        }

        // Command map ownership is advisory: many plugins register brigadier/dynamic commands only.
        try {
            Map<String, Map<String, Object>> declaredCommands = plugin.getDescription().getCommands();
            if (declaredCommands != null) {
                for (String commandName : declaredCommands.keySet()) {
                    PluginCommand command = Bukkit.getPluginCommand(commandName);
                    if (command == null) {
                        BileTools.warn("Health advisory for " + plugin.getName()
                                + ": declared command not yet in map: /" + commandName);
                    } else if (command.getPlugin() != plugin) {
                        BileTools.warn("Health advisory for " + plugin.getName() + ": /" + commandName
                                + " owned by " + command.getPlugin().getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (sourceFile != null && !sourceFile.exists()) {
            failures.add("source jar missing: " + sourceFile.getName());
        }

        return new HealthCheckResult(failures.isEmpty(), failures);
    }

    public static void logTiming(String operation, long totalMs, String... parts) {
        if (BileTools.cfg != null && !BileTools.cfg.isLogTimings()) {
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append("Timing ").append(operation).append(": total=").append(totalMs).append("ms");
        if (parts != null) {
            for (String part : parts) {
                if (part != null && !part.isEmpty()) {
                    message.append(" ").append(part);
                }
            }
        }
        BileTools.info(message.toString());
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    public static boolean isPaperPlugin(File file) {
        try {
            ZipFile z = new ZipFile(file);
            boolean hasPaperYml = z.getEntry("paper-plugin.yml") != null;
            z.close();
            return hasPaperYml;
        } catch (IOException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void load(File file,
                             boolean reloadContext,
                             RuntimeLoadArtifact preparedArtifact,
                             Map<String, RuntimeLoadArtifact> preparedArtifacts) throws UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, IOException, InvalidConfigurationException {
        load(file, reloadContext, preparedArtifact, preparedArtifacts, file);
    }

    @SuppressWarnings("unchecked")
    private static void load(File sourceFile,
                             boolean reloadContext,
                             RuntimeLoadArtifact preparedArtifact,
                             Map<String, RuntimeLoadArtifact> preparedArtifacts,
                             File retainedSourceFile) throws UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, IOException, InvalidConfigurationException {
        File file = preparedArtifact == null ? sourceFile : preparedArtifact.runtimeFile();
        if (getPlugin(file) != null) {
            BileTools.debug(() -> "Skipping " + file.getName() + " because it is already loaded.");
            if (preparedArtifact != null) {
                preparedArtifact.discard();
            }
            return;
        }

        long startNs = System.nanoTime();
        PluginJarMetadata metadata = preparedArtifact == null
                ? readRuntimePluginMetadata(file) : preparedArtifact.metadata;
        PluginDescriptionFile f = metadata.description();
        String cycleKey = key(f.getName());
        Set<String> visiting = LOAD_VISITING.get();
        if (!visiting.add(cycleKey)) {
            BileTools.debug(() -> "Skipping cyclic load for " + f.getName() + ".");
            return;
        }

        RuntimeLoadArtifact runtimeArtifact = preparedArtifact;
        Plugin target = null;
        boolean loadComplete = false;
        try {
            invalidateJarMeta(file);
            File displayFile = retainedSourceFile == null ? sourceFile : retainedSourceFile;
            BileTools.info("Loading " + f.getName() + " " + f.getVersion() + " from " + displayFile.getName() + ".");

            String baseName = displayFile.getName().toLowerCase(Locale.ROOT).replace(".jar", "");
            String declaredName = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
            if (!declaredName.isEmpty() && !baseName.contains(declaredName)) {
                BileTools.warn(displayFile.getName() + " declares plugin name " + f.getName()
                        + "; its filename does not match the plugin id.");
            }

            Plugin existing = resolveLoadedIdentity(f.getName(), Bukkit.getPluginManager());
            if (existing != null) {
                throw new InvalidPluginException("Plugin " + existing.getName()
                        + " is still loaded; replacement requires a prepared lifecycle operation");
            }
            if (runtimeArtifact == null) {
                runtimeArtifact = prepareRuntimeLoadArtifact(file, reloadContext);
            }

            Map<String, RuntimeLoadArtifact> operationArtifacts = new LinkedHashMap<>();
            if (preparedArtifacts != null) {
                operationArtifacts.putAll(preparedArtifacts);
            }

            for (String i : metadata.requiredDependencies()) {
                if (Bukkit.getPluginManager().getPlugin(i) == null) {
                    BileTools.debug(() -> f.getName() + " requires unloaded dependency " + i + ".");
                    Map<String, RecoveryEntry> recoverySources = RECOVERY_SOURCES.get();
                    RecoveryEntry recovery = recoverySources == null ? null : recoverySources.get(key(i));
                    if (recovery != null) {
                        File snapshot = recovery.snapshot().staged().toFile();
                        RuntimeLoadArtifact recoveryArtifact = prepareSnapshotRuntimeLoadArtifact(snapshot, recovery.source(), true);
                        recoveryArtifact.metadata = recovery.metadata();
                        load(snapshot, true, recoveryArtifact, operationArtifacts, recovery.source());
                        continue;
                    }
                    RuntimeLoadArtifact dependencyArtifact = preparedDependencyArtifact(i, operationArtifacts);
                    File dependencySource = dependencyArtifact == null ? getPluginFile(i) : dependencyArtifact.sourceFile;
                    if (dependencySource == null) {
                        throw new UnknownDependencyException("Missing dependency " + i + " for " + f.getName());
                    }
                    load(dependencySource, dependencyArtifact != null, dependencyArtifact, operationArtifacts,
                            dependencyArtifact == null ? dependencySource : dependencyArtifact.authoritativeFile);
                }
            }

            File runtimeFile = runtimeArtifact.runtimeFile();
            boolean nativePaper = ServerPlatform.isPaperRuntime() && NativePaperSupport.isRequested(runtimeFile);
            if (runtimeArtifact.temporary() && !nativePaper && hasPaperDescriptor(file)) {
                BileTools.info("Paper startup entrypoints are not rerun; reloading " + file.getName()
                        + " through its authored plugin.yml.");
                BileTools.debug(() -> "Calling loadPlugin for " + file.getName()
                        + " through its runtime plugin.yml view.");
            } else {
                BileTools.debug(() -> "Calling loadPlugin for " + file.getName() + ".");
            }

            try {
                if (nativePaper) {
                    target = NativePaperSupport.load(runtimeFile);
                } else if (ServerPlatform.isPaperRuntime()) {
                    try (PaperLoadFailureMonitor monitor = PaperLoadFailureMonitor.observe()) {
                        target = Bukkit.getPluginManager().loadPlugin(runtimeFile);
                        monitor.throwIfFailed(target);
                    }
                } else {
                    target = Bukkit.getPluginManager().loadPlugin(runtimeFile);
                }
            } catch (InvalidPluginException | RuntimeException | Error failure) {
                if (target == null) {
                    target = Bukkit.getPluginManager().getPlugin(f.getName());
                }
                throw failure;
            } finally {
                if (target == null && ServerPlatform.isPaperRuntime()) {
                    removePaperRuntimeProvider(f.getName(), runtimeFile);
                }
            }

            if (target == null) {
                BileTools.warn("The server returned no plugin instance for " + file.getName() + ".");
                throw new InvalidPluginException("Unable to load plugin providers for " + file.getName());
            }

            registerLoadedFileOverride(target.getName(), retainedSourceFile == null ? file : retainedSourceFile);
            rememberRunningMetadata(target, metadata);
            boolean explicitOnLoad = !nativePaper && shouldCallExplicitOnLoad();

            if (explicitOnLoad) {
                Plugin loadedTarget = target;
                BileTools.debug(() -> "Calling onLoad for " + loadedTarget.getName() + ".");
                target.onLoad();
            } else {
                Plugin loadedTarget = target;
                BileTools.debug(() -> "Skipping explicit onLoad for " + loadedTarget.getName()
                        + " because the server plugin loader already handled it.");
            }

            Plugin loadedTarget = target;
            BileTools.debug(() -> "Enabling " + loadedTarget.getName() + ".");
            try (LifecycleFailureMonitor monitor = LifecycleFailureMonitor.observe(new LifecycleFailureMonitor.Options(
                    target.getDescription().getFullName(), LifecycleFailureMonitor.Phase.ENABLE,
                    List.of(Bukkit.getLogger(), target.getLogger())))) {
                if (nativePaper) {
                    NativePaperSupport.enable(target);
                } else {
                    Bukkit.getPluginManager().enablePlugin(target);
                }
                monitor.throwIfFailed();
            }

            Plugin registered = Bukkit.getPluginManager().getPlugin(target.getName());
            if (registered == null || !Bukkit.getPluginManager().isPluginEnabled(registered)) {
                throw new InvalidPluginException("Plugin " + target.getName() + " did not enable successfully");
            }

            ensurePluginRegistered(target);

            invalidateJarMeta(file);
            BileTools.info("Enabled " + target.getName() + " successfully.");

            HealthCheckResult health = verifyPluginHealth(target, runtimeFile);
            if (!health.ok()) {
                throw new InvalidPluginException("Post-load health check failed for " + target.getName() + ": " + health.summary());
            }

            rebuildServerCommandGraph();
            logTiming("load " + target.getName(), nanosToMillis(System.nanoTime() - startNs), "health=ok");
            if (recoveryStore != null) {
                recoveryStore.remember(target.getName(), runtimeFile.toPath());
            }
            runtimeArtifact.retain(target.getName());
            loadComplete = true;
        } finally {
            if (!loadComplete && target != null) {
                cleanupFailedPluginLoad(target);
            }
            if (runtimeArtifact != null) {
                runtimeArtifact.discard();
            }
            visiting.remove(cycleKey);
            if (visiting.isEmpty()) {
                LOAD_VISITING.remove();
            }
        }
    }

    private static void cleanupFailedPluginLoad(Plugin plugin) {
        try {
            unload(plugin, ReloadAware.PreUnloadReason.HOT_UNLOAD);
        } catch (Throwable e) {
            BileTools.severe("Could not clean up failed plugin load for " + plugin.getName() + ".", e);
        } finally {
            clearLoadedFileOverride(plugin.getName());
            releaseRuntimePluginFile(plugin.getName());
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) {
            return false;
        }

        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException ignored) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
    }

    private static RuntimeLoadArtifact prepareRuntimeLoadArtifact(File sourceFile,
                                                                  boolean reloadContext) throws IOException, InvalidPluginException, InvalidDescriptionException {
        return prepareRuntimeLoadArtifact(sourceFile, reloadContext, sourceFile == null ? null : sourceFile.getName());
    }

    private static RuntimeLoadArtifact prepareSnapshotRuntimeLoadArtifact(File snapshotFile,
                                                                          File authoritativeFile,
                                                                          boolean reloadContext) throws IOException, InvalidPluginException, InvalidDescriptionException {
        String sourceName = authoritativeFile == null ? snapshotFile.getName() : authoritativeFile.getName();
        RuntimeLoadArtifact artifact = prepareRuntimeLoadArtifact(snapshotFile, reloadContext, sourceName);
        artifact.authoritativeFile = authoritativeFile;
        return artifact;
    }

    private static RuntimeLoadArtifact prepareRuntimeLoadArtifact(File sourceFile,
                                                                  boolean reloadContext,
                                                                  String runtimeSourceName) throws IOException, InvalidPluginException, InvalidDescriptionException {
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new InvalidPluginException("Cannot resolve plugin jar for runtime load");
        }

        boolean paperDescriptor;
        boolean pluginDescriptor;
        try (ZipFile zipFile = new ZipFile(sourceFile)) {
            paperDescriptor = zipFile.getEntry("paper-plugin.yml") != null;
            pluginDescriptor = zipFile.getEntry("plugin.yml") != null;
        }

        boolean paperRuntime = ServerPlatform.isPaperRuntime();
        if (paperRuntime && paperDescriptor && NativePaperSupport.isRequested(sourceFile)) {
            NativePaperSupport.validate(sourceFile);
            return new RuntimeLoadArtifact(sourceFile,
                    createRuntimePluginCopy(sourceFile, runtimePluginDirectory(), runtimeSourceName));
        }
        validateRuntimeCompatibility(paperDescriptor, pluginDescriptor, paperRuntime, reloadContext, sourceFile.getName());
        if (paperRuntime) {
            PaperLoadFailureMonitor.validate();
        }
        if (!paperRuntime || !paperDescriptor) {
            File runtimeDirectory = runtimePluginDirectory();
            if (!paperRuntime) {
                String pluginName = getPluginDescription(sourceFile).getName();
                RuntimeDataDirectory.prepare(runtimeDirectory.toPath(), getPluginsFolder().toPath(), pluginName);
            }
            return new RuntimeLoadArtifact(sourceFile,
                    createRuntimePluginCopy(sourceFile, runtimeDirectory, runtimeSourceName));
        }

        PluginDescriptionFile sourceDescription = readPluginMetadata(sourceFile).description();
        validateNoPendingPaperUpdate(sourceDescription.getName(), sourceFile.getName());
        if (ServerPlatform.isFoliaFamily()) {
            validateFoliaRuntimeCompatibility(
                    true,
                    readPluginDescriptorFlag(sourceFile, "folia-supported"),
                    sourceFile.getName());
        }
        if (BileTools.bile == null) {
            throw new InvalidPluginException("Cannot prepare runtime plugin view before BileTools is initialized");
        }

        File runtimeDirectory = runtimePluginDirectory();
        File runtimeFile = createRuntimePluginView(sourceFile, runtimeDirectory, runtimeSourceName);
        try {
            PluginDescriptionFile runtimeDescription = readPluginMetadata(runtimeFile).description();
            if (!sourceDescription.getName().equals(runtimeDescription.getName())
                    || !sourceDescription.getMain().equals(runtimeDescription.getMain())
                    || !sourceDescription.getVersion().equals(runtimeDescription.getVersion())) {
                throw new InvalidPluginException("Runtime plugin.yml view does not match " + sourceFile.getName());
            }
            return new RuntimeLoadArtifact(sourceFile, runtimeFile);
        } catch (IOException | InvalidPluginException | InvalidDescriptionException e) {
            deleteRuntimePluginFile(runtimeFile);
            throw e;
        }
    }

    static File createRuntimePluginView(File sourceFile, File runtimeDirectory) throws IOException {
        return createRuntimePluginView(sourceFile, runtimeDirectory, sourceFile.getName());
    }

    private static boolean hasPaperDescriptor(File source) throws IOException {
        try (ZipFile archive = new ZipFile(source)) {
            return archive.getEntry("paper-plugin.yml") != null;
        }
    }

    private static File createRuntimePluginView(File sourceFile,
                                                File runtimeDirectory,
                                                String runtimeSourceName) throws IOException {
        Files.createDirectories(runtimeDirectory.toPath());
        String baseName = encodeRuntimeSourceName(runtimeSourceName);
        String runtimeName = baseName + "-" + UUID.randomUUID() + ".jar";
        File partialFile = new File(runtimeDirectory, runtimeName + ".part");
        File runtimeFile = new File(runtimeDirectory, runtimeName);
        boolean paperDescriptorRemoved = false;
        boolean complete = false;

        try (ZipFile input = new ZipFile(sourceFile);
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(partialFile.toPath()))) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if ("paper-plugin.yml".equals(entry.getName())) {
                    paperDescriptorRemoved = true;
                    continue;
                }

                ZipEntry runtimeEntry = new ZipEntry(entry.getName());
                if (entry.getTime() >= 0L) {
                    runtimeEntry.setTime(entry.getTime());
                }
                if (entry.getComment() != null) {
                    runtimeEntry.setComment(entry.getComment());
                }
                if (entry.getExtra() != null) {
                    runtimeEntry.setExtra(entry.getExtra());
                }
                output.putNextEntry(runtimeEntry);
                if (!entry.isDirectory()) {
                    try (InputStream inputStream = input.getInputStream(entry)) {
                        int read;
                        while ((read = inputStream.read(buffer)) != -1) {
                            output.write(buffer, 0, read);
                        }
                    }
                }
                output.closeEntry();
            }
        } catch (IOException e) {
            deleteRuntimePluginFile(partialFile);
            throw e;
        }

        try {
            if (!paperDescriptorRemoved) {
                throw new IOException("No paper-plugin.yml found in " + sourceFile.getName());
            }
            try {
                Files.move(partialFile.toPath(), runtimeFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partialFile.toPath(), runtimeFile.toPath());
            }
            complete = true;
            return runtimeFile;
        } finally {
            deleteRuntimePluginFile(partialFile);
            if (!complete) {
                deleteRuntimePluginFile(runtimeFile);
            }
        }
    }

    private static File createRuntimePluginCopy(File sourceFile,
                                                File runtimeDirectory,
                                                String runtimeSourceName) throws IOException {
        Files.createDirectories(runtimeDirectory.toPath());
        String baseName = encodeRuntimeSourceName(runtimeSourceName);
        String runtimeName = baseName + "-" + UUID.randomUUID() + ".jar";
        File partialFile = new File(runtimeDirectory, runtimeName + ".part");
        File runtimeFile = new File(runtimeDirectory, runtimeName);
        boolean complete = false;

        try {
            Files.copy(sourceFile.toPath(), partialFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partialFile.toPath(), runtimeFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(partialFile.toPath(), runtimeFile.toPath());
            }
            complete = true;
            return runtimeFile;
        } finally {
            deleteRuntimePluginFile(partialFile);
            if (!complete) {
                deleteRuntimePluginFile(runtimeFile);
            }
        }
    }

    private static File runtimePluginDirectory() throws InvalidPluginException {
        if (BileTools.bile == null) {
            throw new InvalidPluginException("Cannot prepare a runtime plugin copy before BileTools is initialized");
        }
        return new File(BileTools.bile.getDataFolder(), "runtime-plugins");
    }

    private static RuntimeLoadArtifact prepareReloadArtifact(Plugin plugin,
                                                             Map<String, RuntimeLoadArtifact> artifacts) throws IOException, InvalidPluginException, InvalidDescriptionException {
        File sourceFile = getPluginFile(plugin);
        if (sourceFile == null || !sourceFile.isFile()) {
            throw new InvalidPluginException("Cannot safely reload " + plugin.getName() + ": source jar could not be resolved");
        }

        String artifactKey = cacheKey(sourceFile);
        RuntimeLoadArtifact existingArtifact = artifacts.get(artifactKey);
        if (existingArtifact != null) {
            return existingArtifact;
        }

        RuntimeLoadArtifact runtimeArtifact = prepareRuntimeLoadArtifact(sourceFile, true);
        try {
            PluginDescriptionFile runtimeDescription = readPluginMetadata(runtimeArtifact.runtimeFile()).description();
            if (!plugin.getName().equalsIgnoreCase(runtimeDescription.getName())) {
                throw new InvalidPluginException("Cannot replace dependent " + plugin.getName() + " with plugin "
                        + runtimeDescription.getName() + " from " + sourceFile.getName());
            }
            artifacts.put(artifactKey, runtimeArtifact);
            prepareMissingDependencyArtifacts(sourceFile, artifacts, new HashSet<>());
            return runtimeArtifact;
        } catch (IOException | InvalidPluginException | InvalidDescriptionException e) {
            artifacts.remove(artifactKey);
            runtimeArtifact.discard();
            throw e;
        }
    }

    private static RuntimeLoadArtifact prepareSnapshotReloadArtifact(
            Plugin plugin,
            SnapshotLoadSource snapshot,
            Map<String, RuntimeLoadArtifact> artifacts) throws IOException, InvalidPluginException, InvalidDescriptionException {
        if (!snapshot.snapshotFile().isFile()) {
            throw new SnapshotUnavailableException("Missing staged snapshot for dependent " + plugin.getName());
        }

        String artifactKey = cacheKey(snapshot.authoritativeFile());
        RuntimeLoadArtifact existingArtifact = artifacts.get(artifactKey);
        if (existingArtifact != null) {
            return existingArtifact;
        }

        RuntimeLoadArtifact runtimeArtifact = prepareSnapshotRuntimeLoadArtifact(
                snapshot.snapshotFile(), snapshot.authoritativeFile(), true);
        try {
            PluginDescriptionFile runtimeDescription = readPluginMetadata(runtimeArtifact.runtimeFile()).description();
            if (!plugin.getName().equalsIgnoreCase(runtimeDescription.getName())) {
                throw new InvalidPluginException("Cannot replace dependent " + plugin.getName() + " with plugin "
                        + runtimeDescription.getName() + " from " + snapshot.snapshotFile().getName());
            }
            artifacts.put(artifactKey, runtimeArtifact);
            prepareMissingDependencyArtifacts(snapshot.snapshotFile(), artifacts, new HashSet<>());
            return runtimeArtifact;
        } catch (IOException | InvalidPluginException | InvalidDescriptionException exception) {
            artifacts.remove(artifactKey);
            runtimeArtifact.discard();
            throw exception;
        }
    }

    private static RuntimeLoadArtifact prepareFreshLoadArtifact(File sourceFile,
                                                                Map<String, RuntimeLoadArtifact> artifacts) throws IOException, InvalidPluginException, InvalidDescriptionException {
        String artifactKey = cacheKey(sourceFile);
        RuntimeLoadArtifact existingArtifact = artifacts.get(artifactKey);
        if (existingArtifact != null) {
            return existingArtifact;
        }

        List<JarSnapshotStager.StagedJar> staged = new ArrayList<>(1);
        try {
            File pinned = stageReplacement(sourceFile, staged).staged().toFile();
            RuntimeLoadArtifact runtimeArtifact = prepareSnapshotRuntimeLoadArtifact(pinned, sourceFile, false);
            artifacts.put(artifactKey, runtimeArtifact);
            return runtimeArtifact;
        } finally {
            staged.forEach(JarSnapshotStager.StagedJar::delete);
        }
    }

    private static void prepareMissingDependencyArtifacts(File sourceFile,
                                                          Map<String, RuntimeLoadArtifact> artifacts,
                                                          Set<String> visited) throws IOException, InvalidPluginException, InvalidDescriptionException {
        RuntimeLoadArtifact prepared = artifacts.get(cacheKey(sourceFile));
        PluginJarMetadata metadata = prepared == null ? readRuntimePluginMetadata(sourceFile) : prepared.metadata;
        if (!visited.add(key(metadata.description().getName()))) {
            return;
        }

        for (String dependencyName : metadata.requiredDependencies()) {
            if (Bukkit.getPluginManager().getPlugin(dependencyName) != null) {
                continue;
            }
            File dependencyFile = getPluginFile(dependencyName);
            if (dependencyFile == null || !dependencyFile.isFile()) {
                throw new InvalidPluginException("Missing dependency " + dependencyName
                        + " for " + metadata.description().getName());
            }
            prepareFreshLoadArtifact(dependencyFile, artifacts);
            prepareMissingDependencyArtifacts(dependencyFile, artifacts, visited);
        }

    }

    private static File findRuntimeSourceFile(String pluginName, File runtimeFile) {
        String sourceName = runtimeSourceBaseName(runtimeFile);
        if (sourceName != null && sourceName.equals(new File(sourceName).getName())) {
            return new File(getPluginsFolder(), sourceName);
        }
        List<File> candidates = new ArrayList<>();
        for (File candidate : listPluginFiles()) {
            if (!isPluginJar(candidate)) {
                continue;
            }
            try {
                if (!pluginArchiveMatchesName(candidate, pluginName, ServerPlatform.isPaperRuntime())) {
                    continue;
                }
                candidates.add(candidate);
                if (sourceName != null && sourceName.equals(candidate.getName())) {
                    return candidate;
                }
            } catch (IOException | InvalidConfigurationException | InvalidDescriptionException ignored) {
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    static File managedRuntimeArchive(File loadedFile, File runtimeDirectory) {
        if (sameFile(loadedFile.getParentFile(), runtimeDirectory)) {
            return loadedFile;
        }
        File parent = loadedFile.getParentFile();
        if (parent == null || !parent.getName().equals(".paper-remapped")
                || !sameFile(parent.getParentFile(), runtimeDirectory)) {
            return null;
        }
        Matcher matcher = RUNTIME_ARCHIVE_NAME.matcher(loadedFile.getName());
        if (!matcher.matches() || runtimeSourceBaseName(loadedFile) == null) {
            return null;
        }
        File original = new File(runtimeDirectory, matcher.group(1) + "-" + matcher.group(2) + ".jar");
        return original.isFile() ? original : loadedFile;
    }

    static String runtimeSourceBaseName(File runtimeFile) {
        if (runtimeFile == null) {
            return null;
        }
        Matcher matcher = RUNTIME_ARCHIVE_NAME.matcher(runtimeFile.getName());
        if (!matcher.matches()) {
            return null;
        }
        try {
            String encodedSourceName = matcher.group(1);
            String sourceName = new String(Base64.getUrlDecoder().decode(encodedSourceName), StandardCharsets.UTF_8);
            return encodedSourceName.equals(encodeRuntimeSourceName(sourceName))
                    && sourceName.equals(new File(sourceName).getName()) ? sourceName : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String encodeRuntimeSourceName(String sourceName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sourceName.getBytes(StandardCharsets.UTF_8));
    }

    private static void prepareDependentReloadArtifacts(
            Plugin root,
            Map<String, RuntimeLoadArtifact> artifacts,
            Set<String> visited,
            Map<String, SnapshotLoadSource> snapshots,
            Set<String> protectedSnapshots) throws IOException, InvalidPluginException, InvalidDescriptionException {
        if (root == null || !visited.add(key(root.getName()))) {
            return;
        }

        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate == root || !dependsOn(candidate, root)) {
                continue;
            }

            String candidateKey = key(candidate.getName());
            SnapshotLoadSource snapshot = snapshots.get(candidateKey);
            if (snapshot == null && protectedSnapshots.contains(candidateKey)) {
                throw new SnapshotUnavailableException(
                        "The staged snapshot for dependent " + candidate.getName() + " was superseded");
            }
            if (snapshot == null) {
                prepareReloadArtifact(candidate, artifacts);
            } else {
                prepareSnapshotReloadArtifact(candidate, snapshot, artifacts);
            }
            prepareDependentReloadArtifacts(candidate, artifacts, visited, snapshots, protectedSnapshots);
        }
    }

    private static Map<String, SnapshotLoadSource> normalizeSnapshotSources(
            Map<String, SnapshotLoadSource> availableSnapshots) {
        if (availableSnapshots == null || availableSnapshots.isEmpty()) {
            return Map.of();
        }

        Map<String, SnapshotLoadSource> normalized = new LinkedHashMap<>();
        for (SnapshotLoadSource snapshot : availableSnapshots.values()) {
            if (snapshot != null) {
                normalized.put(key(snapshot.pluginName()), snapshot);
            }
        }
        return normalized;
    }

    private static Set<String> normalizePluginNames(Set<String> pluginNames) {
        if (pluginNames == null || pluginNames.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String pluginName : pluginNames) {
            if (pluginName != null && !pluginName.trim().isEmpty()) {
                normalized.add(key(pluginName));
            }
        }
        return normalized;
    }

    private static SnapshotLoadSource snapshotSourceFor(
            File authoritativeFile,
            Map<String, SnapshotLoadSource> snapshots) {
        for (SnapshotLoadSource snapshot : snapshots.values()) {
            if (sameFile(authoritativeFile, snapshot.authoritativeFile())) {
                return snapshot;
            }
        }
        return null;
    }

    private static RuntimeLoadArtifact preparedDependencyArtifact(String name,
            Map<String, RuntimeLoadArtifact> artifacts) throws IOException, InvalidDescriptionException {
        for (RuntimeLoadArtifact artifact : artifacts.values()) {
            PluginDescriptionFile description = artifact.metadata.description();
            if (name.equalsIgnoreCase(description.getName()) || containsPluginName(description.getProvides(), name)) {
                return artifact;
            }
        }
        return null;
    }

    private static boolean dependsOn(Plugin candidate,
                                     Plugin dependency) throws IOException, InvalidDescriptionException {
        String dependencyName = dependency.getName();
        PluginJarMetadata metadata = runningMetadata(candidate);
        if (declaresDependency(candidate.getDescription(), metadata, dependencyName)) {
            return true;
        }
        for (String providedName : dependency.getDescription().getProvides()) {
            if (declaresDependency(candidate.getDescription(), metadata, providedName)) {
                return true;
            }
        }
        return dependencyName.equals("WorldEdit") && candidate.getName().equals("FastAsyncWorldEdit");
    }

    private static boolean declaresDependency(PluginDescriptionFile runtimeDescription,
                                               PluginJarMetadata metadata,
                                               String dependencyName) {
        return (metadata != null
                && (containsPluginName(metadata.requiredDependencies(), dependencyName)
                || containsPluginName(metadata.optionalDependencies(), dependencyName)))
                || containsPluginName(runtimeDescription.getDepend(), dependencyName)
                || containsPluginName(runtimeDescription.getSoftDepend(), dependencyName);
    }

    static boolean declaresDependency(PluginDescriptionFile runtimeDescription,
                                      File sourceFile,
                                      String dependencyName) throws IOException, InvalidDescriptionException {
        return declaresDependency(
                runtimeDescription,
                sourceFile,
                dependencyName,
                ServerPlatform.isPaperRuntime());
    }

    static boolean declaresDependency(PluginDescriptionFile runtimeDescription,
                                      File sourceFile,
                                      String dependencyName,
                                      boolean paperRuntime) throws IOException, InvalidDescriptionException {
        if (paperRuntime && sourceFile != null && sourceFile.isFile()) {
            PluginJarMetadata sourceMetadata = readPluginMetadata(sourceFile);
            if (containsPluginName(sourceMetadata.requiredDependencies(), dependencyName)
                    || containsPluginName(sourceMetadata.optionalDependencies(), dependencyName)) {
                return true;
            }
        }

        return runtimeDescription != null
                && (containsPluginName(runtimeDescription.getDepend(), dependencyName)
                || containsPluginName(runtimeDescription.getSoftDepend(), dependencyName));
    }

    private static boolean containsPluginName(List<String> pluginNames, String pluginName) {
        if (pluginName == null) {
            return false;
        }

        for (String candidate : pluginNames) {
            if (pluginName.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean readPluginDescriptorFlag(File sourceFile,
                                            String path) throws IOException, InvalidDescriptionException {
        try (ZipFile zipFile = new ZipFile(sourceFile)) {
            ZipEntry pluginDescriptor = zipFile.getEntry("plugin.yml");
            if (pluginDescriptor == null) {
                return false;
            }

            YamlConfiguration configuration = new YamlConfiguration();
            try (InputStream input = zipFile.getInputStream(pluginDescriptor)) {
                configuration.loadFromString(new String(readAllBytes(input), StandardCharsets.UTF_8));
            } catch (InvalidConfigurationException e) {
                throw new InvalidDescriptionException(e);
            }

            Object value = configuration.get(path);
            if (value == null) {
                return false;
            }
            if (!(value instanceof Boolean)) {
                throw new InvalidDescriptionException(path + " must be true or false in " + sourceFile.getName());
            }
            return (Boolean) value;
        }
    }

    private static void validateNoPendingPaperUpdate(String pluginName, String sourceName) throws RestartRequiredException {
        File updateFolder;
        try {
            updateFolder = Bukkit.getUpdateFolderFile();
        } catch (Throwable ignored) {
            return;
        }

        if (updateFolder == null || !updateFolder.isDirectory() || sameFile(updateFolder, getPluginsFolder())) {
            return;
        }

        File[] updates = updateFolder.listFiles();
        if (updates == null) {
            return;
        }

        for (File update : updates) {
            if (update == null || !update.isFile()) {
                continue;
            }
            try {
                if (pluginName.equalsIgnoreCase(readPaperPreferredPluginName(update))) {
                    throw new RestartRequiredException("Cannot reload " + sourceName
                            + " while Paper has a pending update for " + pluginName + "; a full server restart is required");
                }
            } catch (IOException | InvalidDescriptionException ignored) {
            }
        }
    }

    static String readPaperPreferredPluginName(File file) throws IOException, InvalidDescriptionException {
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry descriptor = zipFile.getEntry("paper-plugin.yml");
            if (descriptor == null) {
                descriptor = zipFile.getEntry("plugin.yml");
            }
            if (descriptor == null) {
                throw new InvalidDescriptionException("No plugin.yml or paper-plugin.yml found in " + file.getName());
            }
            try (InputStream input = zipFile.getInputStream(descriptor)) {
                return new PluginDescriptionFile(input).getName();
            }
        }
    }

    private static boolean shouldCallExplicitOnLoad() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager == null) {
            return true;
        }

        // On modern Paper/Purpur, loadPlugin already triggers onLoad through provider storage.
        return !isPaperRuntimePluginManager(pluginManager);
    }

    private static boolean isPaperRuntimePluginManager(PluginManager pluginManager) {
        if (!ServerPlatform.isPaperRuntime()) {
            return false;
        }

        if (pluginManager != null && findFieldInHierarchy(pluginManager.getClass(), "paperPluginManager") != null) {
            return true;
        }

        if (pluginManager != null) {
            String className = pluginManager.getClass().getName().toLowerCase(Locale.ROOT);
            if (className.contains("paper") || className.contains("purpur") || className.contains("folia") || className.contains("canvas") || className.contains("leaf")) {
                return true;
            }
        }

        return ServerPlatform.isPaperFamily();
    }

    private static Field findFieldInHierarchy(Class<?> type, String fieldName) {
        Class<?> current = type;

        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }

    private static void removePaperPluginTracking(Plugin plugin) {
        if (!ServerPlatform.isPaperRuntime()) {
            return;
        }
        try {
            PaperPluginTracking tracking = paperPluginTracking();
            tracking.plugins().remove(plugin);
            tracking.lookupNames().entrySet().removeIf(entry -> entry.getValue() == plugin);
            Object metadata = Plugin.class.getMethod("getPluginMeta").invoke(plugin);
            invokeCompatibleMethod(tracking.dependencyTree(), "remove", metadata);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not remove Paper plugin tracking for " + plugin.getName(), failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static PaperPluginTracking paperPluginTracking() throws ReflectiveOperationException {
        Object manager = requiredFieldValue(Bukkit.getPluginManager(), "paperPluginManager");
        Object instances = requiredFieldValue(manager, "instanceManager");
        Object plugins = requiredFieldValue(instances, "plugins");
        Object lookup = requiredFieldValue(instances, "lookupNames");
        Object dependencies = requiredFieldValue(instances, "dependencyTree");
        if (!(plugins instanceof List<?>) || !(lookup instanceof Map<?, ?>)) {
            throw new IllegalStateException("Paper plugin tracking registries have unsupported types");
        }
        return new PaperPluginTracking((List<Plugin>) plugins, (Map<String, Plugin>) lookup, dependencies);
    }

    private static Object requiredFieldValue(Object target, String name) throws ReflectiveOperationException {
        if (target == null) {
            throw new IllegalStateException("Missing registry containing " + name);
        }
        Field field = findFieldInHierarchy(target.getClass(), name);
        if (field == null) {
            throw new NoSuchFieldException(target.getClass().getName() + "." + name);
        }
        Object value = field.get(target);
        if (value == null) {
            throw new IllegalStateException("Missing registry " + name);
        }
        return value;
    }

    private record PaperPluginTracking(List<Plugin> plugins, Map<String, Plugin> lookupNames, Object dependencyTree) {
    }

    private static PaperClassloaderRegistration paperClassloaderRegistration(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader serverLoader = Plugin.class.getClassLoader();
        Class<?> storageType = Class.forName("io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage", false, serverLoader);
        Class<?> loaderType = Class.forName("io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader", false, serverLoader);
        ClassLoader pluginLoader = plugin.getClass().getClassLoader();
        if (!loaderType.isInstance(pluginLoader)) {
            throw new IllegalStateException("Unsupported Paper classloader for " + plugin.getName());
        }
        Object storage = storageType.getMethod("instance").invoke(null);
        return new PaperClassloaderRegistration(storage, storageType.getMethod("unregisterClassloader", loaderType), pluginLoader);
    }

    private static void removePaperClassloader(Plugin plugin) {
        try {
            PaperClassloaderRegistration registration = paperClassloaderRegistration(plugin);
            registration.unregister().invoke(registration.storage(), registration.loader());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not detach Paper classloader for " + plugin.getName(), failure);
        }
    }

    private record PaperClassloaderRegistration(Object storage, Method unregister, ClassLoader loader) {
    }

    private static void removePaperRuntimeProvider(String pluginName, File runtimeFile) {
        removePaperLaunchProviders(pluginName, runtimeFile, true, false);
    }

    private static void removePaperLaunchProviders(String pluginName,
                                                   File runtimeFile,
                                                   boolean closeProviderFile,
                                                   boolean includeBootstrapper) {
        if (!ServerPlatform.isPaperRuntime()) {
            return;
        }
        List<Throwable> failures = new ArrayList<>();
        for (String entrypoint : includeBootstrapper ? List.of("PLUGIN", "BOOTSTRAPPER") : List.of("PLUGIN")) {
            try {
                Iterator<Object> iterator = paperProviders(entrypoint).iterator();
                while (iterator.hasNext()) {
                    Object provider = iterator.next();
                    if (!paperProviderMatches(provider, pluginName, runtimeFile)) {
                        continue;
                    }
                    iterator.remove();
                    if (closeProviderFile) {
                        try {
                            removePaperProviderDependency(provider);
                        } catch (Exception failure) {
                            failures.add(failure);
                        }
                        try {
                            closePaperProviderFile(provider);
                        } catch (Exception failure) {
                            failures.add(failure);
                        }
                    }
                }
            } catch (Exception failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("Could not remove Paper provider tracking for " + pluginName);
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> paperProviders(String entrypointName) throws ReflectiveOperationException {
        Class<?> handlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
        Object handler = handlerClass.getField("INSTANCE").get(null);
        Class<?> entrypointClass = Class.forName("io.papermc.paper.plugin.entrypoint.Entrypoint");
        Object entrypoint = entrypointClass.getField(entrypointName).get(null);
        Object storage = handlerClass.getMethod("get", entrypointClass).invoke(handler, entrypoint);
        Object providers = requiredFieldValue(storage, "providers");
        if (!(providers instanceof List<?>)) {
            throw new IllegalStateException("Paper " + entrypointName + " providers are unavailable");
        }
        return (List<Object>) providers;
    }

    private static void closePaperProviderFile(Object provider) throws Exception {
        Object file = invokeCompatibleMethod(provider, "file");
        if (!(file instanceof AutoCloseable closeable)) {
            throw new IllegalStateException("Paper provider file cannot be closed for " + provider.getClass().getName());
        }
        closeable.close();
    }

    private static void removePaperProviderDependency(Object provider) throws Exception {
        invokeCompatibleMethod(paperPluginTracking().dependencyTree(), "remove", provider);
    }

    private static boolean paperProviderMatches(Object provider, String pluginName, File runtimeFile) throws Exception {
        if (pluginName != null) {
            Object metadata = invokeCompatibleMethod(provider, "getMeta");
            Object name = invokeCompatibleMethod(metadata, "getName");
            if (name != null && pluginName.equalsIgnoreCase(name.toString())) {
                return true;
            }
        }
        if (runtimeFile != null) {
            Object source = invokeCompatibleMethod(provider, "getSource");
            return source != null && sameFile(new File(source.toString()), runtimeFile);
        }
        return false;
    }

    private static Object invokeCompatibleMethod(Object target, String methodName, Object... args) throws Exception {
        Method method = findCompatibleMethod(target.getClass(), methodName, args);
        return method.invoke(target, args);
    }

    private static Method findCompatibleMethod(Class<?> type, String methodName, Object... args) {
        for (Method method : getAllMethods(type)) {
            if (!method.getName().equals(methodName)) {
                continue;
            }

            Class<?>[] params = method.getParameterTypes();
            if (params.length != args.length) {
                continue;
            }

            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (!isCompatibleParam(params[i], args[i])) {
                    match = false;
                    break;
                }
            }

            if (match) {
                method.setAccessible(true);
                return method;
            }
        }

        throw new IllegalStateException("No compatible method " + methodName + " on " + type.getName());
    }

    private static List<Method> getAllMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        methods.addAll(Arrays.asList(type.getMethods()));

        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            methods.addAll(Arrays.asList(cursor.getDeclaredMethods()));
            cursor = cursor.getSuperclass();
        }

        return methods;
    }

    private static boolean isCompatibleParam(Class<?> paramType, Object arg) {
        if (arg == null) {
            return !paramType.isPrimitive();
        }

        Class<?> inputType = arg.getClass();
        if (paramType.isPrimitive()) {
            paramType = wrap(paramType);
        }

        return paramType.isAssignableFrom(inputType);
    }

    private static Class<?> wrap(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == short.class) return Short.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == char.class) return Character.class;
        return primitive;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    private static Set<File> unload(Plugin plugin) {
        return unload(plugin, ReloadAware.PreUnloadReason.HOT_UNLOAD);
    }

    @SuppressWarnings("unchecked")
    private static Set<File> unload(Plugin plugin, ReloadAware.PreUnloadReason reason) {
        Set<File> deps = new LinkedHashSet<>();
        if (plugin == null) {
            return deps;
        }

        String cycleKey = key(plugin.getName());
        Set<String> visiting = UNLOAD_VISITING.get();
        if (!visiting.add(cycleKey)) {
            BileTools.debug(() -> "Skipping cyclic unload for " + plugin.getName() + ".");
            return deps;
        }

        try {
            long startNs = System.nanoTime();
            File file = getPluginFile(plugin);
            File runtimeFile = RUNTIME_PLUGIN_FILES.get(key(plugin.getName()));
            List<Throwable> teardownFailures = new ArrayList<>();
            BileTools.info("Unloading " + plugin.getName() + ".");

            if (file == null) {
                BileTools.warn("Could not resolve the source jar for " + plugin.getName()
                        + "; skipping its file reset.");
            }

            for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
                if (candidate.equals(plugin)) {
                    continue;
                }

                boolean dependent;
                try {
                    dependent = dependsOn(candidate, plugin);
                } catch (IOException | InvalidDescriptionException e) {
                    String message = "Could not inspect dependencies for " + candidate.getName()
                            + " while unloading " + plugin.getName();
                    BileTools.warn(message, e);
                    throw new IllegalStateException(message, e);
                }
                if (!dependent) {
                    continue;
                }

                File dependentFile = getPluginFile(candidate);
                if (dependentFile == null) {
                    String message = "Could not resolve source jar for dependent plugin " + candidate.getName()
                            + " while unloading " + plugin.getName();
                    BileTools.warn(message);
                    throw new IllegalStateException(message);
                }
                BileTools.debug(() -> candidate.getName() + " depends on " + plugin.getName()
                        + "; unloading it first.");
                deps.add(dependentFile);
            }

            for (File i : new ArrayList<>(deps)) {
                Plugin dependent = getPlugin(i);
                if (dependent != null) {
                    deps.addAll(unload(dependent, reason));
                }
            }

            teardownStep(plugin, "task cancellation", () -> PlatformTasks.cancelPluginTasks(plugin), teardownFailures);
            teardownStep(plugin, "event cleanup", () -> HandlerList.unregisterAll(plugin), teardownFailures);
            String name = plugin.getName();
            PluginManager pluginManager = Bukkit.getPluginManager();
            SimpleCommandMap commandMap = null;
            List<Plugin> plugins = null;
            Map<String, Plugin> names = null;
            Map<String, Command> commands = null;
            Map<Event, SortedSet<RegisteredListener>> listeners = null;
            boolean reloadlisteners = true;

            if (pluginManager != null) {
                try (LifecycleFailureMonitor monitor = LifecycleFailureMonitor.observe(new LifecycleFailureMonitor.Options(
                        plugin.getDescription().getFullName(), LifecycleFailureMonitor.Phase.DISABLE,
                        List.of(Bukkit.getLogger(), plugin.getLogger())))) {
                    monitor.run(() -> pluginManager.disablePlugin(plugin));
                } catch (Throwable t) {
                    BileTools.warn("disablePlugin failed for " + name
                            + "; continuing teardown so it is still fully unregistered.", t);
                    teardownFailures.add(t);
                }

                try {
                    plugins = readPluginList(pluginManager);
                    names = readLookupNames(pluginManager);

                    try {
                        Field listenersField = findFieldInHierarchy(pluginManager.getClass(), "listeners");
                        if (listenersField != null) {
                            Object listenersObj = listenersField.get(pluginManager);
                            if (listenersObj instanceof Map) {
                                listeners = (Map<Event, SortedSet<RegisteredListener>>) listenersObj;
                            }
                        } else {
                            reloadlisteners = false;
                        }
                    } catch (Exception e) {
                        reloadlisteners = false;
                    }

                    commandMap = readCommandMap(pluginManager);
                    if (commandMap != null) {
                        Field knownCommandsField = findFieldInHierarchy(SimpleCommandMap.class, "knownCommands");
                        if (knownCommandsField != null) {
                            Object commandsObj = knownCommandsField.get(commandMap);
                            if (commandsObj instanceof Map) {
                                commands = (Map<String, Command>) commandsObj;
                            }
                        }
                    }
                } catch (Throwable e) {
                    BileTools.severe("Could not inspect server plugin registries while unloading " + name + ".", e);
                    throw new IllegalStateException("Could not inspect registries for " + name, e);
                }
            }

            scrubBrigadierNodes(plugin);
            SimpleCommandMap cleanupCommandMap = commandMap;
            Map<String, Command> cleanupCommands = commands;
            teardownStep(plugin, "command cleanup",
                    () -> scrubPluginCommands(plugin, cleanupCommandMap, cleanupCommands), teardownFailures);
            try {
                PluginHelpCleanup.remove(Bukkit.getHelpMap().getHelpTopics(), plugin);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                teardownFailures.add(exception);
                BileTools.warn("Help topic cleanup failed for " + name, exception);
            }
            if (!ServerPlatform.isRegionizedThreading() && BileTools.bile != null && BileTools.bile.isEnabled()) {
                Bukkit.getScheduler().runTask(BileTools.bile, BileUtils::schedulerQueueAdvanced);
            }

            if (plugins != null) {
                plugins.remove(plugin);
            }

            if (names != null) {
                names.remove(name, plugin);
                names.remove(name.toLowerCase(Locale.ROOT), plugin);
                try {
                    for (String provided : plugin.getDescription().getProvides()) {
                        names.remove(provided, plugin);
                        names.remove(provided.toLowerCase(Locale.ROOT), plugin);
                    }
                } catch (Throwable ignored) {
                }
            }

            if (listeners != null && reloadlisteners) {
                for (SortedSet<RegisteredListener> set : listeners.values()) {
                    set.removeIf(value -> value.getPlugin() == plugin);
                }
            }

            teardownStep(plugin, "service cleanup", () -> Bukkit.getServicesManager().unregisterAll(plugin), teardownFailures);
            teardownStep(plugin, "incoming channel cleanup",
                    () -> Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin), teardownFailures);
            teardownStep(plugin, "outgoing channel cleanup",
                    () -> Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin), teardownFailures);
            try {
                NativePaperSupport.cleanup(plugin);
            } catch (InvalidPluginException exception) {
                teardownFailures.add(exception);
                BileTools.warn("Native Paper cleanup failed for " + name, exception);
            }
            teardownStep(plugin, "Paper registry cleanup", () -> removePaperPluginTracking(plugin), teardownFailures);

            if (ServerPlatform.isPaperRuntime() && !NativePaperSupport.isNative(plugin)) {
                teardownStep(plugin, "Paper classloader cleanup", () -> removePaperClassloader(plugin), teardownFailures);
            }
            if (!ServerPlatform.isPaperRuntime()) {
                teardownStep(plugin, "Spigot classloader cleanup",
                        () -> SpigotPluginLoaderCleanup.cleanup(plugin), teardownFailures);
            }
            ClassLoader cl = plugin.getClass().getClassLoader();

            if (cl instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException ex) {
                    BileTools.warn("Could not close the classloader for " + name + ".", ex);
                    teardownFailures.add(ex);
                }
            }

            teardownStep(plugin, "Paper provider cleanup",
                    () -> removePaperLaunchProviders(plugin.getName(), runtimeFile, true, true), teardownFailures);
            releaseRuntimePluginFile(plugin.getName());
            if (file != null && (runtimeFile == null || sameFile(file, runtimeFile))) {
                refreshPluginJarHandle(file);
            }

            clearLoadedFileOverride(plugin.getName());
            if (file != null) {
                invalidateJarMeta(file);
            }
            rebuildServerCommandGraph();
            if (plugin.isEnabled() || Bukkit.getPluginManager().getPlugin(name) == plugin
                    || Arrays.asList(Bukkit.getPluginManager().getPlugins()).contains(plugin)) {
                teardownFailures.add(new IllegalStateException("Plugin " + name + " remains registered or enabled after teardown"));
            }
            if (!teardownFailures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("Incomplete teardown for " + name);
                teardownFailures.forEach(failure::addSuppressed);
                throw failure;
            }
            logTiming("unload " + name, nanosToMillis(System.nanoTime() - startNs));
            return deps;
        } finally {
            visiting.remove(cycleKey);
            if (visiting.isEmpty()) {
                UNLOAD_VISITING.remove();
            }
        }
    }

    private static void teardownStep(Plugin plugin, String operation, Runnable action, List<Throwable> failures) {
        try {
            action.run();
        } catch (Throwable failure) {
            failures.add(failure);
            BileTools.warn("Failed " + operation + " for " + plugin.getName(), failure);
        }
    }

    private static void schedulerQueueAdvanced() {
    }

    /**
     * Removes the plugin's Brigadier-API command nodes from Paper's internal dispatcher
     * (the authoritative store CraftServer#syncCommands copies from). Must run BEFORE the
     * plugin becomes unresolvable and BEFORE any walk of the SimpleCommandMap view: on modern
     * Paper, knownCommands is a live BukkitBrigForwardingMap whose iteration wraps every node
     * via APICommandMeta.plugin() -> PluginManager.getPlugin(name) -> requireNonNull, so a
     * node owned by an unresolvable plugin NPEs the whole walk. Nodes whose owner no longer
     * resolves are also dropped so one bad unload cannot poison every later command-map walk.
     * Safe no-op on Spigot or when internals move.
     */
    private static void scrubBrigadierNodes(Plugin plugin) {
        if (plugin == null || !ServerPlatform.isPaperFamily()) {
            return;
        }

        Object root = resolveApiDispatcherRoot();
        if (root == null) {
            return;
        }

        java.util.function.Predicate<String> ownerResolvable = (String ownerName) -> {
            try {
                PluginManager pluginManager = Bukkit.getPluginManager();
                return pluginManager == null || pluginManager.getPlugin(ownerName) != null;
            } catch (Throwable ignored) {
                return true;
            }
        };

        List<String> removed = removeApiNodesFromRoot(root, plugin.getName(), ownerResolvable);
        if (!removed.isEmpty()) {
            BileTools.debug(() -> "Scrubbed Brigadier nodes for " + plugin.getName()
                    + ": " + String.join(", ", removed));
        }
    }

    /**
     * Locates the root node of Paper's API command dispatcher, or null when unavailable.
     */
    private static Object resolveApiDispatcherRoot() {
        try {
            Object dispatcher = null;
            Object paperCommands = readStaticFieldNoThrow("io.papermc.paper.command.brigadier.PaperCommands", "INSTANCE");
            if (paperCommands != null) {
                dispatcher = invokeNoThrow(paperCommands, "getDispatcherInternal");
            }
            if (dispatcher == null) {
                Object forwardingMap = readStaticFieldNoThrow("io.papermc.paper.command.brigadier.bukkit.BukkitBrigForwardingMap", "INSTANCE");
                if (forwardingMap != null) {
                    dispatcher = invokeNoThrow(forwardingMap, "getDispatcher");
                }
            }
            return dispatcher == null ? null : invokeNoThrow(dispatcher, "getRoot");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Walks a Brigadier root node reflectively and removes every child whose apiCommandMeta
     * names the given plugin, plus orphaned children whose owner fails ownerResolvable.
     * Children without apiCommandMeta (vanilla / classic Bukkit commands) are never touched.
     * Returns the removed node names.
     */
    static List<String> removeApiNodesFromRoot(Object root, String pluginName, java.util.function.Predicate<String> ownerResolvable) {
        List<String> removed = new ArrayList<>();
        if (root == null || pluginName == null || ownerResolvable == null) {
            return removed;
        }

        try {
            Object children = invokeNoThrow(root, "getChildren");
            if (!(children instanceof java.util.Collection<?> nodes)) {
                return removed;
            }

            List<String> names = new ArrayList<>();
            for (Object node : nodes) {
                if (node == null) {
                    continue;
                }
                String owner = readApiNodeOwner(node);
                if (owner == null) {
                    continue;
                }
                if (!owner.equalsIgnoreCase(pluginName) && ownerResolvable.test(owner)) {
                    continue;
                }
                Object nodeName = invokeNoThrow(node, "getName");
                if (nodeName instanceof String name) {
                    names.add(name);
                }
            }

            for (String name : names) {
                try {
                    invokeCompatibleMethod(root, "removeCommand", name);
                    removed.add(name);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    /**
     * Reads the owning plugin name from a Brigadier node's apiCommandMeta, or null for
     * nodes without API meta (vanilla / classic commands).
     */
    private static String readApiNodeOwner(Object node) {
        try {
            Field metaField = findFieldInHierarchy(node.getClass(), "apiCommandMeta");
            Object meta = metaField == null ? null : metaField.get(node);
            Object pluginMeta = meta == null ? null : invokeNoThrow(meta, "pluginMeta");
            Object name = pluginMeta == null ? null : invokeNoThrow(pluginMeta, "getName");
            return name instanceof String owner ? owner : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoThrow(Object target, String methodName) {
        try {
            return invokeCompatibleMethod(target, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readStaticFieldNoThrow(String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className);
            Field field = type.getField(fieldName);
            return field.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Rebuilds the server command dispatcher (Paper/Spigot CraftServer#syncCommands when present)
     * and pushes updated trees to online players.
     */
    public static void rebuildServerCommandGraph() {
        Plugin host = BileTools.bile;
        if (host == null || !host.isEnabled()) {
            return;
        }
        queueCommandGraphRefresh(task -> PlatformTasks.runGlobal(host, task, 1L),
                BileUtils::refreshServerCommandGraph);
    }

    static void queueCommandGraphRefresh(Predicate<Runnable> scheduler, Runnable refresh) {
        if (!COMMAND_REFRESH_QUEUED.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = false;
        try {
            scheduled = scheduler.test(() -> {
                COMMAND_REFRESH_QUEUED.set(false);
                refresh.run();
            });
        } finally {
            if (!scheduled) {
                COMMAND_REFRESH_QUEUED.set(false);
            }
        }
    }

    private static void refreshServerCommandGraph() {
        try {
            Object server = Bukkit.getServer();
            Method syncCommands = findPublicMethod(server.getClass(), "syncCommands");
            if (syncCommands == null) {
                syncCommands = findDeclaredMethod(server.getClass(), "syncCommands");
            }
            if (syncCommands != null) {
                syncCommands.setAccessible(true);
                syncCommands.invoke(server);
                return;
            }
        } catch (Throwable ignored) {
        }

        resyncPlayerCommands();
    }

    private static Method findPublicMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    static void scrubPluginCommands(Plugin plugin, SimpleCommandMap commandMap, Map<String, Command> commands) {
        if (plugin == null || commandMap == null || commands == null) {
            throw new IllegalStateException("Required command registries are unavailable");
        }
        List<Throwable> failures = new ArrayList<>();

        List<String> toRemove = new ArrayList<>();
        String pluginKey = plugin.getName().toLowerCase(Locale.ROOT);

        try {
            // On modern Paper this map is a live BukkitBrigForwardingMap; walking it wraps every
            // dispatcher node and can throw if a node's owner is unresolvable. Never let that
            // abort the unload - the declared-name removal below still covers the plugin.
            for (Map.Entry<String, Command> entry : commands.entrySet()) {
                Command command = entry.getValue();
                if (command instanceof PluginIdentifiableCommand identifiableCommand) {
                    if (identifiableCommand.getPlugin() == plugin) {
                        toRemove.add(entry.getKey());
                    }
                    continue;
                }

                String mapKey = entry.getKey();
                if (mapKey != null) {
                    String lower = mapKey.toLowerCase(Locale.ROOT);
                    if (lower.startsWith(pluginKey + ":")) {
                        toRemove.add(mapKey);
                    }
                }
            }
        } catch (Throwable t) {
            failures.add(t);
            BileTools.warn("Command map walk for " + plugin.getName()
                    + " failed; falling back to declared command names.", t);
        }

        try {
            for (Map.Entry<String, Map<String, Object>> declaration : plugin.getDescription().getCommands().entrySet()) {
                String commandName = declaration.getKey();
                toRemove.add(commandName);
                toRemove.add(commandName.toLowerCase(Locale.ROOT));
                toRemove.add(pluginKey + ":" + commandName.toLowerCase(Locale.ROOT));
                Object aliases = declaration.getValue().get("aliases");
                if (aliases instanceof List<?> declaredAliases) {
                    for (Object alias : declaredAliases) {
                        if (alias instanceof String aliasName) {
                            toRemove.add(aliasName);
                            toRemove.add(aliasName.toLowerCase(Locale.ROOT));
                            toRemove.add(pluginKey + ":" + aliasName.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Throwable failure) {
            failures.add(failure);
        }

        for (String key : toRemove) {
            Command command;
            try {
                command = commands.get(key);
                if (command == null) {
                    continue;
                }
                if (command instanceof PluginIdentifiableCommand identifiableCommand) {
                    if (identifiableCommand.getPlugin() != plugin) {
                        continue;
                    }
                } else if (!key.toLowerCase(Locale.ROOT).startsWith(pluginKey + ":")) {
                    continue;
                }
                if (!commands.remove(key, command)) {
                    if (commands.get(key) == command) {
                        throw new IllegalStateException("Command remains registered: " + key);
                    }
                    continue;
                }
            } catch (Throwable failure) {
                failures.add(failure);
                continue;
            }
            try {
                if (!command.unregister(commandMap)) {
                    throw new IllegalStateException("Command refused unregistration: " + key);
                }
            } catch (Throwable failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("Command cleanup failed for " + plugin.getName());
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    /**
     * Forces clients to rebuild their command trees after plugin command map mutations.
     */
    public static void resyncPlayerCommands() {
        try {
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                Plugin host = BileTools.bile;
                Runnable update = () -> {
                    try {
                        player.updateCommands();
                    } catch (Throwable ignored) {
                    }
                };

                if (host != null && host.isEnabled()) {
                    PlatformTasks.runForPlayer(host, player, update);
                } else {
                    update.run();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    static void validateRuntimeCompatibility(boolean paperDescriptor,
                                             boolean pluginDescriptor,
                                             boolean paperRuntime,
                                             boolean reloadContext,
                                             String sourceName) throws InvalidPluginException {
        if (!paperDescriptor) {
            return;
        }
        if (!pluginDescriptor && !paperRuntime) {
            throw new InvalidPluginException("Cannot load " + sourceName
                    + ": paper-plugin.yml-only jars require a Paper-compatible server startup");
        }
        if (!pluginDescriptor) {
            throw new RestartRequiredException("Cannot reload " + sourceName
                    + ": Paper-only plugins cannot register during runtime; a full server restart is required");
        }
        if (paperRuntime && !reloadContext) {
            throw new RestartRequiredException("Cannot hot-load " + sourceName
                    + ": Paper plugin entrypoints require startup; install the jar and perform a full server restart");
        }
    }

    static void validateFoliaRuntimeCompatibility(boolean foliaRuntime,
                                                  boolean foliaSupported,
                                                  String sourceName) throws RestartRequiredException {
        if (foliaRuntime && !foliaSupported) {
            throw new RestartRequiredException("Cannot reload " + sourceName
                    + " through plugin.yml on Folia: folia-supported is not true; a full server restart is required");
        }
    }

    @SuppressWarnings("unchecked")
    private static void ensurePluginRegistered(Plugin target) {
        if (target == null) {
            return;
        }

        try {
            PluginManager pm = Bukkit.getPluginManager();
            List<Plugin> plugins = readPluginList(pm);
            if (plugins != null && !plugins.contains(target)) {
                plugins.add(target);
            }

            Map<String, Plugin> lookup = readLookupNames(pm);
            if (lookup != null) {
                lookup.put(target.getName().toLowerCase(Locale.ROOT), target);
                lookup.put(target.getName(), target);
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Plugin> readPluginList(PluginManager pluginManager) throws IllegalAccessException {
        if (pluginManager == null) {
            return null;
        }
        Field pluginsField = findFieldInHierarchy(pluginManager.getClass(), "plugins");
        if (pluginsField == null) {
            return null;
        }
        Object pluginsObj = pluginsField.get(pluginManager);
        if (pluginsObj instanceof List) {
            return (List<Plugin>) pluginsObj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Plugin> readLookupNames(PluginManager pluginManager) throws IllegalAccessException {
        if (pluginManager == null) {
            return null;
        }
        Field lookupField = findFieldInHierarchy(pluginManager.getClass(), "lookupNames");
        if (lookupField == null) {
            return null;
        }
        Object lookupObj = lookupField.get(pluginManager);
        if (lookupObj instanceof Map) {
            return (Map<String, Plugin>) lookupObj;
        }
        return null;
    }

    private static SimpleCommandMap readCommandMap(PluginManager pluginManager) throws IllegalAccessException {
        if (pluginManager == null) {
            return null;
        }
        Field commandMapField = findFieldInHierarchy(pluginManager.getClass(), "commandMap");
        if (commandMapField == null) {
            return null;
        }
        Object map = commandMapField.get(pluginManager);
        if (map instanceof SimpleCommandMap simpleCommandMap) {
            return simpleCommandMap;
        }
        return null;
    }

    /**
     * On Windows (or when the jar appears locked), rewrite the file via temp copy so the
     * classloader handle is released for the next load. Skipped on Unix when a simple reset works.
     */
    private static void refreshPluginJarHandle(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            try {
                BileTools.bile.reset(file);
            } catch (Throwable ignored) {
            }
            return;
        }

        File tempDir = new File(BileTools.bile.getDataFolder(), "temp");
        tempDir.mkdirs();
        File temp = new File(tempDir, UUID.randomUUID() + ".jar");

        try {
            copy(file, temp);
            if (!file.delete()) {
                // still attempt rewrite
            }
            copy(temp, file);
            BileTools.bile.reset(file);
        } catch (IOException e) {
            BileTools.warn("Could not refresh the plugin jar handle for " + file.getName() + ".", e);
        } finally {
            if (temp.exists() && !temp.delete()) {
                temp.deleteOnExit();
            }
        }
    }

    public static void invalidateJarMeta(File file) {
        if (file == null) {
            return;
        }
        JAR_META_CACHE.remove(cacheKey(file));
    }

    private static String cacheKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private static CachedJarMeta getCachedJarMeta(File file) throws IOException, InvalidDescriptionException {
        String key = cacheKey(file);
        long length = file.length();
        long lastModified = file.lastModified();
        CachedJarMeta cached = JAR_META_CACHE.get(key);
        if (cached != null && cached.length() == length && cached.lastModified() == lastModified) {
            return cached;
        }

        PluginDescriptionFile description = readPluginDescription(file);
        CachedJarMeta meta = new CachedJarMeta(length, lastModified, description.getName(), description.getVersion());
        JAR_META_CACHE.put(key, meta);
        return meta;
    }

    public static File getBackupLocation(Plugin p) {
        return new File(new File(BileTools.bile.getDataFolder(), "library"), p.getName());
    }

    public static File getBackupLocation(String n) {
        return new File(new File(BileTools.bile.getDataFolder(), "library"), n);
    }

    public List<String> getBackedUpVersions(Plugin p) {
        List<String> s = new ArrayList<>();

        if (getBackupLocation(p).exists()) {
            for (File i : getBackupLocation(p).listFiles()) {
                s.add(i.getName().replace(".jar", ""));
            }
        }

        return s;
    }

    public static void backup(Plugin p) throws IOException {
        BileTools.bile.getLogger().info("Backed up " + p.getName() + " " + p.getDescription().getVersion());
        copy(getPluginFile(p), new File(getBackupLocation(p), p.getDescription().getVersion() + ".jar"));
    }

    private static void backupSnapshot(File snapshotFile) throws IOException, InvalidDescriptionException {
        PluginDescriptionFile description = getPluginDescription(snapshotFile);
        BileTools.bile.getLogger().info("Backed up " + description.getName() + " " + description.getVersion());
        copy(snapshotFile, new File(getBackupLocation(description.getName()), description.getVersion() + ".jar"));
    }

    public static void copy(File a, File b) throws IOException {
        b.getParentFile().mkdirs();
        Files.copy(a.toPath(), b.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static long hash(File file) throws NoSuchAlgorithmException {
        ByteBuffer buf = ByteBuffer.wrap(MessageDigest.getInstance("MD5").digest((file.lastModified() + "" + file.length()).getBytes()));
        return buf.getLong() + buf.getLong();
    }

    public static Plugin getPlugin(File file) {
        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            try {
                if (getPluginFile(i).equals(file)) {
                    return i;
                }
            } catch (Throwable ignored) {

            }
        }

        return null;
    }

    public static File getPluginFile(Plugin plugin) {
        if (plugin == null) {
            return null;
        }

        File override = SOURCE_FILE_OVERRIDES.get(key(plugin.getName()));
        if (override != null) {
            return override;
        }

        for (File i : listPluginFiles()) {
            if (isPluginJar(i)) {
                try {
                    if (pluginArchiveMatchesName(i, plugin.getName(), ServerPlatform.isPaperRuntime())) {
                        return i;
                    }
                } catch (Throwable ignored) {

                }
            }
        }

        return null;
    }

    public static File getPluginFile(String name) {
        if (name == null) {
            return null;
        }

        Map<String, RecoveryEntry> recoverySources = RECOVERY_SOURCES.get();
        if (recoverySources != null && recoverySources.containsKey(key(name))) {
            return recoverySources.get(key(name)).snapshot().staged().toFile();
        }

        File override = SOURCE_FILE_OVERRIDES.get(key(name));
        if (override != null && override.exists()) {
            return override;
        }

        for (File i : listPluginFiles()) {
            if (isPluginJar(i) && i.isFile() && i.getName().equalsIgnoreCase(name)) {
                return i;
            }
        }

        for (File i : listPluginFiles()) {
            try {
                if (isPluginJar(i) && i.isFile()
                        && pluginArchiveMatchesName(i, name, ServerPlatform.isPaperRuntime())) {
                    return i;
                }
            } catch (Throwable ignored) {

            }
        }

        return null;
    }

    public static boolean isPluginJar(File f) {
        return f != null && f.exists() && f.isFile() && f.getName().toLowerCase().endsWith(".jar");
    }

    public static File getPluginsFolder() {
        File apiFolder = invokePluginsFolderApi(PLUGINS_FOLDER_API);
        if (apiFolder != null) {
            return apiFolder;
        }

        File updateFolder;
        try {
            updateFolder = Bukkit.getUpdateFolderFile();
        } catch (Throwable ignored) {
            updateFolder = null;
        }

        return derivePluginsFolder(updateFolder);
    }

    static File invokePluginsFolderApi(Method method) {
        if (method == null) {
            return null;
        }

        try {
            Object result = method.invoke(null);
            return result instanceof File file ? file : null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    // Spigot fallback: the update folder resolves inside the plugins folder
    static File derivePluginsFolder(File updateFolder) {
        if (updateFolder != null) {
            File parent = updateFolder.getParentFile();
            if (parent != null) {
                return parent;
            }
        }
        return new File("plugins");
    }

    private static File[] listPluginFiles() {
        File pluginsFolder = getPluginsFolder();
        if (pluginsFolder == null) {
            return new File[0];
        }

        File[] files = pluginsFolder.listFiles();
        if (files == null) {
            return new File[0];
        }

        return files;
    }

    public static List<String> getDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return readPluginMetadata(file).requiredDependencies();
    }

    public static List<String> getSoftDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return readPluginMetadata(file).optionalDependencies();
    }

    public static String getPluginVersion(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getCachedJarMeta(file).pluginVersion();
    }

    public static String getPluginName(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getCachedJarMeta(file).pluginName();
    }

    static boolean pluginArchiveMatchesName(File file,
                                            String pluginName,
                                            boolean paperRuntime) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        if (pluginName == null) {
            return false;
        }
        if (pluginName.equalsIgnoreCase(getPluginName(file))) {
            return true;
        }
        return paperRuntime && pluginName.equalsIgnoreCase(readPaperPreferredPluginName(file));
    }

    public static PluginDescriptionFile getPluginDescription(File file) throws IOException, InvalidDescriptionException {
        return readPluginDescription(file);
    }

    /**
     * Reads plugin.yml / paper-plugin.yml without sleeping. Callers that race partial jar writes
     * should reschedule (hot-drop retries) instead of blocking the main thread.
     */
    private static PluginDescriptionFile readPluginDescription(File file) throws IOException, InvalidDescriptionException {
        return readPluginMetadata(file).description();
    }

    private static PluginJarMetadata readPluginMetadata(File file) throws IOException, InvalidDescriptionException {
        return readPluginMetadata(file, true);
    }

    private static PluginJarMetadata readRuntimePluginMetadata(File file) throws IOException, InvalidDescriptionException {
        return readPluginMetadata(file, ServerPlatform.isPaperRuntime());
    }

    private static PluginJarMetadata readPluginMetadata(File file,
                                                        boolean includePaperMetadata) throws IOException, InvalidDescriptionException {
        IOException lastZipReadError = null;

        for (int attempt = 0; attempt <= ZIP_READ_RETRY_LIMIT; attempt++) {
            try (ZipFile z = new ZipFile(file)) {
                ZipEntry pluginYml = z.getEntry("plugin.yml");
                PluginJarMetadata pluginMetadata = null;
                if (pluginYml != null) {
                    try (InputStream is = z.getInputStream(pluginYml)) {
                        PluginDescriptionFile description = new PluginDescriptionFile(is);
                        pluginMetadata = new PluginJarMetadata(description, description.getDepend(), description.getSoftDepend());
                    }
                }

                ZipEntry paperYml = z.getEntry("paper-plugin.yml");
                if (paperYml == null && pluginMetadata == null) {
                    throw new InvalidDescriptionException("No plugin.yml or paper-plugin.yml found in " + file.getName());
                }
                if (paperYml == null) {
                    return pluginMetadata;
                }
                if (!includePaperMetadata && pluginMetadata != null) {
                    return pluginMetadata;
                }

                byte[] paperBytes;
                try (InputStream is = z.getInputStream(paperYml)) {
                    paperBytes = readAllBytes(is);
                }

                PluginJarMetadata paperMetadata = readPaperPluginMetadata(paperBytes, file.getName());
                return pluginMetadata == null ? paperMetadata : mergePluginMetadata(pluginMetadata, paperMetadata);
            } catch (IOException e) {
                lastZipReadError = e;
                if (!isTransientZipReadError(e) || attempt >= ZIP_READ_RETRY_LIMIT) {
                    throw e;
                }
            }
        }

        if (lastZipReadError != null) {
            throw lastZipReadError;
        }

        throw new IOException("Unable to read plugin jar " + file.getName());
    }

    private static PluginJarMetadata readPaperPluginMetadata(byte[] paperBytes, String sourceName) throws InvalidDescriptionException {
        PluginDescriptionFile description = new PluginDescriptionFile(new ByteArrayInputStream(paperBytes));
        YamlConfiguration paper = new YamlConfiguration();
        try {
            paper.loadFromString(new String(paperBytes, StandardCharsets.UTF_8));
        } catch (InvalidConfigurationException e) {
            throw new InvalidDescriptionException(e);
        }

        LinkedHashSet<String> requiredDependencies = new LinkedHashSet<>(description.getDepend());
        LinkedHashSet<String> optionalDependencies = new LinkedHashSet<>(description.getSoftDepend());
        readPaperDependencies(paper, "dependencies.bootstrap", sourceName, requiredDependencies, optionalDependencies);
        readPaperDependencies(paper, "dependencies.server", sourceName, requiredDependencies, optionalDependencies);
        return new PluginJarMetadata(description, new ArrayList<>(requiredDependencies), new ArrayList<>(optionalDependencies));
    }

    private static void readPaperDependencies(YamlConfiguration paper,
                                              String path,
                                              String sourceName,
                                              LinkedHashSet<String> requiredDependencies,
                                              LinkedHashSet<String> optionalDependencies) throws InvalidDescriptionException {
        Object rawDependencies = paper.get(path);
        if (rawDependencies == null) {
            return;
        }

        ConfigurationSection dependencies = paper.getConfigurationSection(path);
        if (dependencies == null) {
            throw new InvalidDescriptionException(path + " must be a configuration section in " + sourceName);
        }

        for (String dependencyName : dependencies.getKeys(false)) {
            ConfigurationSection dependency = dependencies.getConfigurationSection(dependencyName);
            if (dependency == null) {
                throw new InvalidDescriptionException(path + "." + dependencyName + " must be a configuration section in " + sourceName);
            }

            Object rawRequired = dependency.get("required");
            if (rawRequired != null && !(rawRequired instanceof Boolean)) {
                throw new InvalidDescriptionException(path + "." + dependencyName + ".required must be true or false in " + sourceName);
            }

            boolean required = rawRequired == null || (Boolean) rawRequired;
            if (required) {
                requiredDependencies.add(dependencyName);
                optionalDependencies.remove(dependencyName);
            } else if (!requiredDependencies.contains(dependencyName)) {
                optionalDependencies.add(dependencyName);
            }
        }
    }

    private static PluginJarMetadata mergePluginMetadata(PluginJarMetadata primary, PluginJarMetadata secondary) {
        LinkedHashSet<String> requiredDependencies = new LinkedHashSet<>(primary.requiredDependencies());
        LinkedHashSet<String> optionalDependencies = new LinkedHashSet<>(primary.optionalDependencies());
        for (String dependencyName : secondary.requiredDependencies()) {
            requiredDependencies.add(dependencyName);
            optionalDependencies.remove(dependencyName);
        }
        for (String dependencyName : secondary.optionalDependencies()) {
            if (!requiredDependencies.contains(dependencyName)) {
                optionalDependencies.add(dependencyName);
            }
        }
        return new PluginJarMetadata(
                primary.description(),
                new ArrayList<>(requiredDependencies),
                new ArrayList<>(optionalDependencies));
    }

    private static boolean isTransientZipReadError(IOException e) {
        String message = rootMessage(e);
        if (message == null) {
            return false;
        }

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("zip end header not found")
                || lower.contains("zip file is empty")
                || lower.contains("error in opening zip file")
                || lower.contains("invalid loc header")
                || lower.contains("cannot read");
    }

    public static Plugin getPluginByName(String string) {
        Plugin exact = getPluginByExactName(string);
        if (exact != null) {
            return exact;
        }
        if (string == null || string.trim().isEmpty()) {
            return null;
        }

        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().toLowerCase().contains(string.toLowerCase())) {
                return i;
            }
        }

        return null;
    }

    public static Plugin getPluginByExactName(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) {
            return null;
        }
        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().equalsIgnoreCase(pluginName)) {
                return i;
            }
        }
        return null;
    }

    public record SnapshotLoadSource(String pluginName, File snapshotFile, File authoritativeFile) {
        public SnapshotLoadSource {
            if (pluginName == null || pluginName.trim().isEmpty()) {
                throw new IllegalArgumentException("pluginName must not be blank");
            }
            if (snapshotFile == null || authoritativeFile == null) {
                throw new IllegalArgumentException("snapshot and authoritative files are required");
            }
        }
    }

    public static final class SnapshotUnavailableException extends InvalidPluginException {
        public SnapshotUnavailableException(String message) {
            super(message);
        }
    }
}
