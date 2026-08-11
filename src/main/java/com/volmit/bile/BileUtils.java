package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
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
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class BileUtils {
    private static final int ZIP_READ_RETRY_LIMIT = 2;
    private static final int UUID_TEXT_LENGTH = 36;

    private static final Map<String, File> SOURCE_FILE_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, File> RUNTIME_PLUGIN_FILES = new ConcurrentHashMap<>();
    private static final Map<String, CachedJarMeta> JAR_META_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<String>> LOAD_VISITING = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Set<String>> UNLOAD_VISITING = ThreadLocal.withInitial(HashSet::new);
    private static final Method PLUGINS_FOLDER_API = findPublicMethod(Bukkit.class, "getPluginsFolder");

    private static String key(String pluginName) {
        return pluginName.toLowerCase(Locale.ROOT);
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
        private boolean retained;

        private RuntimeLoadArtifact(File sourceFile, File runtimeFile) {
            this.sourceFile = sourceFile;
            this.runtimeFile = runtimeFile;
        }

        private File runtimeFile() {
            return runtimeFile;
        }

        private boolean temporary() {
            return !sameFile(sourceFile, runtimeFile);
        }

        private void retain(String pluginName) {
            if (!temporary()) {
                return;
            }

            File previous = RUNTIME_PLUGIN_FILES.put(key(pluginName), runtimeFile);
            retained = true;
            runtimeFile.deleteOnExit();
            if (previous != null && !sameFile(previous, runtimeFile)) {
                deleteRuntimePluginFile(previous);
            }
        }

        private void discard() {
            if (!retained && temporary()) {
                deleteRuntimePluginFile(runtimeFile);
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

                File runtimeFile = (File) pluginFile;
                if (!sameFile(runtimeFile.getParentFile(), runtimeDirectory)) {
                    continue;
                }

                activeRuntimeFiles.add(cacheKey(runtimeFile));
                runtimeFile.deleteOnExit();
                RUNTIME_PLUGIN_FILES.put(key(plugin.getName()), runtimeFile);
                clearLoadedFileOverride(plugin.getName());
                File sourceFile = findRuntimeSourceFile(plugin.getName(), runtimeFile);
                if (sourceFile != null && sourceFile.isFile()) {
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
            if (activeRuntimeFiles.contains(cacheKey(runtimeFile))) {
                continue;
            }
            deleteRuntimePluginFile(runtimeFile);
            removed++;
        }

        if (!activeRuntimeFiles.isEmpty() || removed > 0) {
            BileTools.bile.getLogger().info("Runtime plugin files: active=" + activeRuntimeFiles.size() + ", staleRemoved=" + removed);
        }
    }

    public static void delete(Plugin p) throws IOException {
        File f = getPluginFile(p);
        if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
            backup(p);
        }
        unload(p);
        f.delete();
    }

    public static void delete(File f) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        if (getPlugin(f) != null) {
            delete(getPlugin(f));
            return;
        }

        if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
            PluginDescriptionFile fx = getPluginDescription(f);
            copy(f, new File(getBackupLocation(fx.getName()), fx.getVersion() + ".jar"));
        }
        f.delete();
    }

    public static void reload(Plugin p) throws IOException, UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, InvalidConfigurationException {
        if (p == null) {
            throw new InvalidPluginException("Cannot reload null plugin");
        }

        String pluginName = p.getName();
        long startNs = System.nanoTime();
        File f = getPluginFile(p);
        if (p.getClass().getClassLoader() == BileUtils.class.getClassLoader()) {
            preloadOwnClasses(p, f);
        }
        Map<String, RuntimeLoadArtifact> runtimeArtifacts = new LinkedHashMap<>();
        Plugin reloaded = null;
        boolean reloadComplete = false;

        try {
            RuntimeLoadArtifact runtimeArtifact = prepareReloadArtifact(p, runtimeArtifacts);
            prepareDependentReloadArtifacts(p, runtimeArtifacts, new HashSet<>());

            if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
                backup(p);
            }

            long unloadStartNs = System.nanoTime();
            Set<File> x = unload(p, ReloadAware.PreUnloadReason.HOT_RELOAD);
            long unloadMs = nanosToMillis(System.nanoTime() - unloadStartNs);

            long loadStartNs = System.nanoTime();
            load(f, true, runtimeArtifact, runtimeArtifacts);
            long loadMs = nanosToMillis(System.nanoTime() - loadStartNs);
            reloaded = Bukkit.getPluginManager().getPlugin(pluginName);
            if (reloaded == null) {
                throw new InvalidPluginException("Reloaded plugin " + pluginName + " was not registered");
            }

            long dependentsStartNs = System.nanoTime();
            for (File i : x) {
                if (i != null && i.isFile()) {
                    load(i, true, runtimeArtifacts.get(cacheKey(i)), runtimeArtifacts);
                }
            }
            long dependentsMs = nanosToMillis(System.nanoTime() - dependentsStartNs);

            HealthCheckResult health = verifyPluginHealth(reloaded, f);
            if (!health.ok()) {
                throw new InvalidPluginException("Post-reload health check failed for " + pluginName + ": " + health.summary());
            }

            long totalMs = nanosToMillis(System.nanoTime() - startNs);
            logTiming("reload " + pluginName, totalMs,
                    "unload=" + unloadMs + "ms",
                    "dependents=" + dependentsMs + "ms",
                    "load=" + loadMs + "ms",
                    "health=ok");
            reloadComplete = true;
        } finally {
            if (!reloadComplete && reloaded != null) {
                cleanupFailedPluginLoad(reloaded);
            }
            for (RuntimeLoadArtifact runtimeArtifact : runtimeArtifacts.values()) {
                runtimeArtifact.discard();
            }
        }
    }

    private static void preloadOwnClasses(Plugin plugin, File file) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        if (loader == null || file == null || !file.isFile()) {
            return;
        }

        long startNs = System.nanoTime();
        int loaded = 0;
        try (ZipFile jar = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) {
                    continue;
                }

                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    Class.forName(className, false, loader);
                    loaded++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        logTiming("preload " + plugin.getName(), nanosToMillis(System.nanoTime() - startNs), "classes=" + loaded);
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
                        stp("Health advisory for " + plugin.getName() + ": declared command not yet in map: /" + commandName);
                    } else if (command.getPlugin() != plugin) {
                        stp("Health advisory for " + plugin.getName() + ": /" + commandName + " owned by " + command.getPlugin().getName());
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
        stp(message.toString());
    }

    private static long nanosToMillis(long nanos) {
        return Math.max(0L, nanos / 1_000_000L);
    }

    public static void stp(String s) {
        try {
            Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY + s);
        } catch (Throwable ignored) {
            System.out.println("[Bile]: " + s);
        }
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
    public static void load(File file) throws UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, IOException, InvalidConfigurationException {
        load(file, false, null, null);
    }

    @SuppressWarnings("unchecked")
    private static void load(File file,
                             boolean reloadContext,
                             RuntimeLoadArtifact preparedArtifact,
                             Map<String, RuntimeLoadArtifact> preparedArtifacts) throws UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, IOException, InvalidConfigurationException {
        if (getPlugin(file) != null) {
            stp("Skipping " + file.getName() + " (already loaded)");
            if (preparedArtifact != null) {
                preparedArtifact.discard();
            }
            return;
        }

        long startNs = System.nanoTime();
        PluginJarMetadata metadata = readRuntimePluginMetadata(file);
        PluginDescriptionFile f = metadata.description();
        String cycleKey = key(f.getName());
        Set<String> visiting = LOAD_VISITING.get();
        if (!visiting.add(cycleKey)) {
            stp("Skipping cyclic load for " + f.getName());
            return;
        }

        RuntimeLoadArtifact runtimeArtifact = preparedArtifact;
        Map<String, RuntimeLoadArtifact> dependentArtifacts = new LinkedHashMap<>();
        Plugin target = null;
        boolean loadComplete = false;
        try {
            invalidateJarMeta(file);
            stp("Loading " + f.getName() + " " + f.getVersion() + " from " + file.getName());
            List<File> deferredDependents = new ArrayList<>();

            String baseName = file.getName().toLowerCase(Locale.ROOT).replace(".jar", "");
            String declaredName = f.getName() == null ? "" : f.getName().toLowerCase(Locale.ROOT);
            if (!declaredName.isEmpty() && !baseName.contains(declaredName)) {
                stp("Warning: " + file.getName() + " declares plugin name " + f.getName() + " (filename does not match plugin id)");
            }

            Plugin existing = Bukkit.getPluginManager().getPlugin(f.getName());
            boolean replacingLoadedPlugin = existing != null;
            if (runtimeArtifact == null) {
                runtimeArtifact = prepareRuntimeLoadArtifact(file, reloadContext || replacingLoadedPlugin);
            }
            if (existing != null) {
                File existingFile = getPluginFile(existing);

                if (sameFile(existingFile, file)) {
                    stp("Skipping " + file.getName() + " (plugin " + existing.getName() + " already loaded from this jar)");
                    return;
                }

                String existingName = existingFile == null ? "unknown source" : existingFile.getName();
                stp("Plugin " + existing.getName() + " is already loaded from " + existingName + ", replacing with " + file.getName());

                prepareMissingDependencyArtifacts(file, dependentArtifacts, new HashSet<>());
                prepareDependentReloadArtifacts(existing, dependentArtifacts, new HashSet<>());
                Set<File> dependents = unload(existing, ReloadAware.PreUnloadReason.HOT_RELOAD);
                for (File dep : dependents) {
                    if (dep != null && !sameFile(dep, file)) {
                        deferredDependents.add(dep);
                    }
                }
            }

            Map<String, RuntimeLoadArtifact> operationArtifacts = new LinkedHashMap<>();
            if (preparedArtifacts != null) {
                operationArtifacts.putAll(preparedArtifacts);
            }
            operationArtifacts.putAll(dependentArtifacts);

            for (String i : metadata.requiredDependencies()) {
                if (Bukkit.getPluginManager().getPlugin(i) == null) {
                    stp(f.getName() + " depends on " + i);
                    File fx = getPluginFile(i);

                    if (fx != null) {
                        RuntimeLoadArtifact dependencyArtifact = preparedArtifactFor(fx, operationArtifacts);
                        load(fx, dependencyArtifact != null, dependencyArtifact, operationArtifacts);
                    } else {
                        throw new UnknownDependencyException(
                                "Missing dependency " + i + " for " + f.getName());
                    }
                }
            }

            File runtimeFile = runtimeArtifact.runtimeFile();
            if (runtimeArtifact.temporary()) {
                stp("Paper startup entrypoints are not rerun; reloading " + file.getName() + " through its authored plugin.yml");
                stp("Calling loadPlugin for " + file.getName() + " through its runtime plugin.yml view");
            } else {
                stp("Calling loadPlugin for " + file.getName());
            }

            try {
                target = Bukkit.getPluginManager().loadPlugin(runtimeFile);
            } finally {
                if (target == null && ServerPlatform.isPaperRuntime()) {
                    removePaperRuntimeProvider(f.getName(), runtimeFile);
                }
            }

            if (target == null) {
                stp("loadPlugin returned null for " + file.getName());
                throw new InvalidPluginException("Unable to load plugin providers for " + file.getName());
            }

            registerLoadedFileOverride(target.getName(), file);
            boolean explicitOnLoad = shouldCallExplicitOnLoad();

            if (explicitOnLoad) {
                stp("Calling onLoad for " + target.getName());
                target.onLoad();
            } else {
                stp("Skipping explicit onLoad for " + target.getName() + " (already handled by server plugin loader)");
            }

            stp("Enabling " + target.getName());
            Bukkit.getPluginManager().enablePlugin(target);

            Plugin registered = Bukkit.getPluginManager().getPlugin(target.getName());
            if (registered == null || !Bukkit.getPluginManager().isPluginEnabled(registered)) {
                throw new InvalidPluginException("Plugin " + target.getName() + " did not enable successfully");
            }

            ensurePluginRegistered(target);

            invalidateJarMeta(file);
            stp("Enabled " + target.getName() + " successfully");

            if (!deferredDependents.isEmpty()) {
                stp("Reloading " + deferredDependents.size() + " dependent plugin(s) after replacement of " + target.getName());
                for (File dependent : deferredDependents) {
                    if (dependent != null && dependent.exists()) {
                        load(dependent, true, dependentArtifacts.get(cacheKey(dependent)), dependentArtifacts);
                    }
                }
            }

            HealthCheckResult health = verifyPluginHealth(target, file);
            if (!health.ok()) {
                throw new InvalidPluginException("Post-load health check failed for " + target.getName() + ": " + health.summary());
            }

            rebuildServerCommandGraph();
            logTiming("load " + target.getName(), nanosToMillis(System.nanoTime() - startNs), "health=ok");
            runtimeArtifact.retain(target.getName());
            loadComplete = true;
        } finally {
            if (!loadComplete && target != null) {
                cleanupFailedPluginLoad(target);
            }
            if (runtimeArtifact != null) {
                runtimeArtifact.discard();
            }
            for (RuntimeLoadArtifact dependentArtifact : dependentArtifacts.values()) {
                dependentArtifact.discard();
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
            if (BileTools.bile != null) {
                BileTools.bile.getLogger().log(Level.SEVERE, "Could not clean up failed plugin load for " + plugin.getName(), e);
            } else {
                e.printStackTrace();
            }
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
        validateRuntimeCompatibility(paperDescriptor, pluginDescriptor, paperRuntime, reloadContext, sourceFile.getName());
        if (!paperRuntime || !paperDescriptor) {
            return new RuntimeLoadArtifact(sourceFile, sourceFile);
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

        File runtimeDirectory = new File(BileTools.bile.getDataFolder(), "runtime-plugins");
        File runtimeFile = createRuntimePluginView(sourceFile, runtimeDirectory);
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
        Files.createDirectories(runtimeDirectory.toPath());
        String baseName = encodeRuntimeSourceName(sourceFile.getName());
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

    private static RuntimeLoadArtifact prepareFreshLoadArtifact(File sourceFile,
                                                                Map<String, RuntimeLoadArtifact> artifacts) throws IOException, InvalidPluginException, InvalidDescriptionException {
        String artifactKey = cacheKey(sourceFile);
        RuntimeLoadArtifact existingArtifact = artifacts.get(artifactKey);
        if (existingArtifact != null) {
            return existingArtifact;
        }

        RuntimeLoadArtifact runtimeArtifact = prepareRuntimeLoadArtifact(sourceFile, false);
        artifacts.put(artifactKey, runtimeArtifact);
        return runtimeArtifact;
    }

    private static void prepareMissingDependencyArtifacts(File sourceFile,
                                                          Map<String, RuntimeLoadArtifact> artifacts,
                                                          Set<String> visited) throws IOException, InvalidPluginException, InvalidDescriptionException {
        PluginJarMetadata metadata = readRuntimePluginMetadata(sourceFile);
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

    static String runtimeSourceBaseName(File runtimeFile) {
        if (runtimeFile == null) {
            return null;
        }

        String runtimeName = runtimeFile.getName();
        int extensionStart = runtimeName.length() - ".jar".length();
        int uuidStart = extensionStart - UUID_TEXT_LENGTH;
        if (uuidStart <= 0 || extensionStart <= uuidStart || runtimeName.charAt(uuidStart - 1) != '-') {
            return null;
        }

        try {
            UUID.fromString(runtimeName.substring(uuidStart, extensionStart));
            String encodedSourceName = runtimeName.substring(0, uuidStart - 1);
            String sourceName = new String(Base64.getUrlDecoder().decode(encodedSourceName), StandardCharsets.UTF_8);
            return encodedSourceName.equals(encodeRuntimeSourceName(sourceName)) ? sourceName : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String encodeRuntimeSourceName(String sourceName) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sourceName.getBytes(StandardCharsets.UTF_8));
    }

    private static void prepareDependentReloadArtifacts(Plugin root,
                                                        Map<String, RuntimeLoadArtifact> artifacts,
                                                        Set<String> visited) throws IOException, InvalidPluginException, InvalidDescriptionException {
        if (root == null || !visited.add(key(root.getName()))) {
            return;
        }

        for (Plugin candidate : Bukkit.getPluginManager().getPlugins()) {
            if (candidate == root || !dependsOn(candidate, root)) {
                continue;
            }

            prepareReloadArtifact(candidate, artifacts);
            prepareDependentReloadArtifacts(candidate, artifacts, visited);
        }
    }

    private static RuntimeLoadArtifact preparedArtifactFor(File file,
                                                           Map<String, RuntimeLoadArtifact> artifacts) {
        if (file == null || artifacts == null) {
            return null;
        }
        return artifacts.get(cacheKey(file));
    }

    private static boolean dependsOn(Plugin candidate,
                                     Plugin dependency) throws IOException, InvalidDescriptionException {
        String dependencyName = dependency.getName();
        File sourceFile = getPluginFile(candidate);
        if (declaresDependency(candidate.getDescription(), sourceFile, dependencyName)) {
            return true;
        }
        for (String providedName : dependency.getDescription().getProvides()) {
            if (declaresDependency(candidate.getDescription(), sourceFile, providedName)) {
                return true;
            }
        }
        return dependencyName.equals("WorldEdit") && candidate.getName().equals("FastAsyncWorldEdit");
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

    @SuppressWarnings("unchecked")
    private static void removePaperPluginTracking(Plugin plugin) {
        try {
            PluginManager pluginManager = Bukkit.getPluginManager();
            if (pluginManager == null) {
                return;
            }

            Field paperPluginManagerField = findFieldInHierarchy(pluginManager.getClass(), "paperPluginManager");
            if (paperPluginManagerField == null) {
                return;
            }

            Object paperPluginManager = paperPluginManagerField.get(pluginManager);

            if (paperPluginManager == null) {
                return;
            }

            Field instanceManagerField = findFieldInHierarchy(paperPluginManager.getClass(), "instanceManager");
            if (instanceManagerField == null) {
                return;
            }

            Object instanceManager = instanceManagerField.get(paperPluginManager);
            if (instanceManager == null) {
                return;
            }

            Field pluginsField = findFieldInHierarchy(instanceManager.getClass(), "plugins");
            Object pluginsObj = pluginsField == null ? null : pluginsField.get(instanceManager);
            if (pluginsObj instanceof List) {
                ((List<Plugin>) pluginsObj).remove(plugin);
            }

            Field lookupNamesField = findFieldInHierarchy(instanceManager.getClass(), "lookupNames");
            Object lookupObj = lookupNamesField == null ? null : lookupNamesField.get(instanceManager);
            if (lookupObj instanceof Map) {
                Map<String, Plugin> lookupNames = (Map<String, Plugin>) lookupObj;
                lookupNames.entrySet().removeIf(e -> e.getValue() == plugin);
                lookupNames.remove(plugin.getName().toLowerCase(Locale.ROOT));

                try {
                    for (String provided : plugin.getDescription().getProvides()) {
                        lookupNames.remove(provided.toLowerCase(Locale.ROOT));
                    }
                } catch (Throwable ignored) {
                }
            }

            try {
                Field dependencyTreeField = findFieldInHierarchy(instanceManager.getClass(), "dependencyTree");
                Object dependencyTree = dependencyTreeField == null ? null : dependencyTreeField.get(instanceManager);

                Method getPluginMeta = plugin.getClass().getMethod("getPluginMeta");
                Object pluginMeta = getPluginMeta.invoke(plugin);
                if (dependencyTree != null && pluginMeta != null) {
                    invokeCompatibleMethod(dependencyTree, "remove", pluginMeta);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void removePaperRuntimeProvider(String pluginName, File runtimeFile) {
        removePaperLaunchProviders(pluginName, runtimeFile, true, false);
    }

    @SuppressWarnings("unchecked")
    private static void removePaperLaunchProviders(String pluginName,
                                                   File runtimeFile,
                                                   boolean closeProviderFile,
                                                   boolean includeBootstrapper) {
        try {
            Class<?> handlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
            Field instanceField = handlerClass.getField("INSTANCE");
            Object handler = instanceField.get(null);
            Class<?> entrypointClass = Class.forName("io.papermc.paper.plugin.entrypoint.Entrypoint");
            Method getMethod = handlerClass.getMethod("get", entrypointClass);
            List<String> entrypointNames = includeBootstrapper
                    ? List.of("PLUGIN", "BOOTSTRAPPER")
                    : List.of("PLUGIN");
            for (String entrypointName : entrypointNames) {
                Field entrypointField = entrypointClass.getField(entrypointName);
                Object entrypoint = entrypointField.get(null);
                Object storage = getMethod.invoke(handler, entrypoint);
                Field providersField = findFieldInHierarchy(storage.getClass(), "providers");
                Object providersObject = providersField == null ? null : providersField.get(storage);
                if (!(providersObject instanceof List)) {
                    continue;
                }

                List<Object> providers = (List<Object>) providersObject;
                Iterator<Object> iterator = providers.iterator();
                while (iterator.hasNext()) {
                    Object provider = iterator.next();
                    if (!paperProviderMatches(provider, pluginName, runtimeFile)) {
                        continue;
                    }

                    iterator.remove();
                    if (closeProviderFile) {
                        removePaperProviderDependency(provider);
                        closePaperProviderFile(provider);
                    }
                }
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable e) {
            if (ServerPlatform.isPaperRuntime() && BileTools.bile != null) {
                BileTools.bile.getLogger().log(Level.WARNING, "Could not remove Paper runtime provider tracking", e);
            }
        }
    }

    private static void closePaperProviderFile(Object provider) {
        try {
            Object providerFile = invokeCompatibleMethod(provider, "file");
            if (providerFile instanceof AutoCloseable) {
                ((AutoCloseable) providerFile).close();
            }
        } catch (Throwable e) {
            if (ServerPlatform.isPaperRuntime() && BileTools.bile != null) {
                BileTools.bile.getLogger().log(Level.WARNING, "Could not close Paper runtime provider file", e);
            }
        }
    }

    private static void removePaperProviderDependency(Object provider) {
        try {
            PluginManager pluginManager = Bukkit.getPluginManager();
            if (pluginManager == null) {
                return;
            }
            Field paperPluginManagerField = findFieldInHierarchy(pluginManager.getClass(), "paperPluginManager");
            Object paperPluginManager = paperPluginManagerField == null ? null : paperPluginManagerField.get(pluginManager);
            Field instanceManagerField = paperPluginManager == null
                    ? null
                    : findFieldInHierarchy(paperPluginManager.getClass(), "instanceManager");
            Object instanceManager = instanceManagerField == null ? null : instanceManagerField.get(paperPluginManager);
            Field dependencyTreeField = instanceManager == null
                    ? null
                    : findFieldInHierarchy(instanceManager.getClass(), "dependencyTree");
            Object dependencyTree = dependencyTreeField == null ? null : dependencyTreeField.get(instanceManager);
            if (dependencyTree != null) {
                invokeCompatibleMethod(dependencyTree, "remove", provider);
            }
        } catch (Throwable e) {
            if (ServerPlatform.isPaperRuntime() && BileTools.bile != null) {
                BileTools.bile.getLogger().log(Level.WARNING, "Could not remove Paper runtime dependency metadata", e);
            }
        }
    }

    private static boolean paperProviderMatches(Object provider, String pluginName, File runtimeFile) {
        if (runtimeFile != null) {
            try {
                Object source = invokeCompatibleMethod(provider, "getSource");
                if (source != null && sameFile(new File(source.toString()), runtimeFile)) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }

        if (pluginName != null) {
            try {
                Object metadata = invokeCompatibleMethod(provider, "getMeta");
                Object name = invokeCompatibleMethod(metadata, "getName");
                return name != null && pluginName.equalsIgnoreCase(name.toString());
            } catch (Throwable ignored) {
            }
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

    public static Set<File> unload(Plugin plugin) {
        return unload(plugin, ReloadAware.PreUnloadReason.HOT_UNLOAD);
    }

    @SuppressWarnings("unchecked")
    public static Set<File> unload(Plugin plugin, ReloadAware.PreUnloadReason reason) {
        Set<File> deps = new LinkedHashSet<>();
        if (plugin == null) {
            return deps;
        }

        String cycleKey = key(plugin.getName());
        Set<String> visiting = UNLOAD_VISITING.get();
        if (!visiting.add(cycleKey)) {
            stp("Skipping cyclic unload for " + plugin.getName());
            return deps;
        }

        try {
            long startNs = System.nanoTime();
            File file = getPluginFile(plugin);
            File runtimeFile = RUNTIME_PLUGIN_FILES.get(key(plugin.getName()));
            stp("Unloading " + plugin.getName());

            if (file == null) {
                stp("Could not resolve source jar for " + plugin.getName() + ", skipping file reset");
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
                    stp(message);
                    throw new IllegalStateException(message, e);
                }
                if (!dependent) {
                    continue;
                }

                File dependentFile = getPluginFile(candidate);
                if (dependentFile == null) {
                    String message = "Could not resolve source jar for dependent plugin " + candidate.getName()
                            + " while unloading " + plugin.getName();
                    stp(message);
                    throw new IllegalStateException(message);
                }
                stp(candidate.getName() + " depends on " + plugin.getName() + ". Playing it safe.");
                deps.add(dependentFile);
            }

            for (File i : new ArrayList<>(deps)) {
                Plugin dependent = getPlugin(i);
                if (dependent != null) {
                    deps.addAll(unload(dependent, reason));
                }
            }

            if (plugin instanceof ReloadAware aware) {
                stp("Invoking pre-unload hook on " + plugin.getName() + " (" + reason + ")");
                try {
                    aware.onPreUnload(reason);
                } catch (Throwable t) {
                    stp("Pre-unload hook for " + plugin.getName() + " threw: " + t);
                    t.printStackTrace();
                }
            }

            PlatformTasks.cancelPluginTasks(plugin);
            HandlerList.unregisterAll(plugin);
            String name = plugin.getName();
            PluginManager pluginManager = Bukkit.getPluginManager();
            SimpleCommandMap commandMap = null;
            List<Plugin> plugins = null;
            Map<String, Plugin> names = null;
            Map<String, Command> commands = null;
            Map<Event, SortedSet<RegisteredListener>> listeners = null;
            boolean reloadlisteners = true;

            if (pluginManager != null) {
                try {
                    pluginManager.disablePlugin(plugin);
                } catch (Throwable t) {
                    stp("disablePlugin threw for " + name + " (continuing teardown so the plugin is still fully unregistered): " + t);
                    t.printStackTrace();
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
                    e.printStackTrace();
                    return new HashSet<>();
                }
            }

            try {
                if (pluginManager != null) {
                    pluginManager.disablePlugin(plugin);
                }
            } catch (Throwable t) {
                stp("disablePlugin (second pass) threw for " + name + " (continuing unregister): " + t);
                t.printStackTrace();
            }

            if (plugins != null) {
                plugins.remove(plugin);
            }

            if (names != null) {
                names.remove(name);
                names.remove(name.toLowerCase(Locale.ROOT));
                try {
                    for (String provided : plugin.getDescription().getProvides()) {
                        names.remove(provided);
                        names.remove(provided.toLowerCase(Locale.ROOT));
                    }
                } catch (Throwable ignored) {
                }
            }

            if (listeners != null && reloadlisteners) {
                for (SortedSet<RegisteredListener> set : listeners.values()) {
                    set.removeIf(value -> value.getPlugin() == plugin);
                }
            }

            scrubBrigadierNodes(plugin);
            scrubPluginCommands(plugin, commandMap, commands);
            scrubPluginServices(plugin);
            scrubPluginMessenger(plugin);
            removePaperPluginTracking(plugin);

            ClassLoader cl = plugin.getClass().getClassLoader();

            if (cl instanceof java.io.Closeable) {
                try {
                    ((java.io.Closeable) cl).close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            removePaperLaunchProviders(plugin.getName(), runtimeFile, true, true);
            releaseRuntimePluginFile(plugin.getName());
            if (file != null) {
                refreshPluginJarHandle(file);
            }

            clearLoadedFileOverride(plugin.getName());
            if (file != null) {
                invalidateJarMeta(file);
            }
            rebuildServerCommandGraph();
            logTiming("unload " + name, nanosToMillis(System.nanoTime() - startNs));
            return deps;
        } finally {
            visiting.remove(cycleKey);
            if (visiting.isEmpty()) {
                UNLOAD_VISITING.remove();
            }
        }
    }

    private static void scrubPluginServices(Plugin plugin) {
        if (plugin == null) {
            return;
        }

        try {
            Bukkit.getServicesManager().unregisterAll(plugin);
        } catch (Throwable t) {
            stp("Service unregister for " + plugin.getName() + " threw: " + t.getClass().getSimpleName());
        }
    }

    private static void scrubPluginMessenger(Plugin plugin) {
        if (plugin == null) {
            return;
        }

        try {
            org.bukkit.plugin.messaging.Messenger messenger = Bukkit.getMessenger();
            try {
                messenger.unregisterIncomingPluginChannel(plugin);
            } catch (Throwable ignored) {
            }
            try {
                messenger.unregisterOutgoingPluginChannel(plugin);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            stp("Messenger channel scrub for " + plugin.getName() + " threw: " + t.getClass().getSimpleName());
        }
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
            stp("Scrubbed brigadier nodes for " + plugin.getName() + ": " + String.join(", ", removed));
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
        try {
            Object server = Bukkit.getServer();
            Method syncCommands = findPublicMethod(server.getClass(), "syncCommands");
            if (syncCommands == null) {
                syncCommands = findDeclaredMethod(server.getClass(), "syncCommands");
            }
            if (syncCommands != null) {
                syncCommands.setAccessible(true);
                syncCommands.invoke(server);
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
            return;
        }

        List<String> toRemove = new ArrayList<>();
        String pluginKey = plugin.getName().toLowerCase(Locale.ROOT);

        try {
            // On modern Paper this map is a live BukkitBrigForwardingMap; walking it wraps every
            // dispatcher node and can throw if a node's owner is unresolvable. Never let that
            // abort the unload - the declared-name removal below still covers the plugin.
            for (Map.Entry<String, Command> entry : commands.entrySet()) {
                Command command = entry.getValue();
                if (command instanceof PluginCommand pluginCommand) {
                    if (pluginCommand.getPlugin() == plugin) {
                        try {
                            pluginCommand.unregister(commandMap);
                        } catch (Throwable ignored) {
                        }
                        toRemove.add(entry.getKey());
                        continue;
                    }
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
            stp("Command map walk for " + plugin.getName() + " aborted (" + t.getClass().getSimpleName() + "); falling back to declared command names");
        }

        try {
            for (String commandName : plugin.getDescription().getCommands().keySet()) {
                toRemove.add(commandName);
                toRemove.add(commandName.toLowerCase(Locale.ROOT));
                toRemove.add(pluginKey + ":" + commandName.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }

        for (String key : toRemove) {
            Command removed;
            try {
                removed = commands.remove(key);
            } catch (Throwable ignored) {
                continue;
            }
            if (removed instanceof PluginCommand pluginCommand && pluginCommand.getPlugin() == plugin) {
                try {
                    pluginCommand.unregister(commandMap);
                } catch (Throwable ignored) {
                }
            }
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
            e.printStackTrace();
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
        if (override != null && override.exists()) {
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
        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().equalsIgnoreCase(string)) {
                return i;
            }
        }

        for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
            if (i.getName().toLowerCase().contains(string.toLowerCase())) {
                return i;
            }
        }

        return null;
    }
}
