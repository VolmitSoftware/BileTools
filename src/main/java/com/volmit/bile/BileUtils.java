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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BileUtils {
    private static final int ZIP_READ_RETRY_LIMIT = 2;

    private static final Map<String, File> SOURCE_FILE_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, CachedJarMeta> JAR_META_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Set<String>> LOAD_VISITING = ThreadLocal.withInitial(HashSet::new);
    private static final ThreadLocal<Set<String>> UNLOAD_VISITING = ThreadLocal.withInitial(HashSet::new);

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

        if (BileTools.cfg == null || BileTools.cfg.isArchivePlugins()) {
            backup(p);
        }

        long unloadStartNs = System.nanoTime();
        Set<File> x = unload(p, ReloadAware.PreUnloadReason.HOT_RELOAD);
        long unloadMs = nanosToMillis(System.nanoTime() - unloadStartNs);

        long dependentsStartNs = System.nanoTime();
        for (File i : x) {
            load(i);
        }
        long dependentsMs = nanosToMillis(System.nanoTime() - dependentsStartNs);

        long loadStartNs = System.nanoTime();
        load(f);
        long loadMs = nanosToMillis(System.nanoTime() - loadStartNs);

        Plugin reloaded = Bukkit.getPluginManager().getPlugin(pluginName);
        HealthCheckResult health = verifyPluginHealth(reloaded != null ? reloaded : p, f);
        if (!health.ok()) {
            throw new InvalidPluginException("Post-reload health check failed for " + pluginName + ": " + health.summary());
        }

        long totalMs = nanosToMillis(System.nanoTime() - startNs);
        logTiming("reload " + pluginName, totalMs,
                "unload=" + unloadMs + "ms",
                "dependents=" + dependentsMs + "ms",
                "load=" + loadMs + "ms",
                "health=ok");
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
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY + s);
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
        if (getPlugin(file) != null) {
            stp("Skipping " + file.getName() + " (already loaded)");
            return;
        }

        long startNs = System.nanoTime();
        PluginJarMetadata metadata = readPluginMetadata(file);
        PluginDescriptionFile f = metadata.description();
        String cycleKey = key(f.getName());
        Set<String> visiting = LOAD_VISITING.get();
        if (!visiting.add(cycleKey)) {
            stp("Skipping cyclic load for " + f.getName());
            return;
        }

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
            if (existing != null) {
                File existingFile = getPluginFile(existing);

                if (sameFile(existingFile, file)) {
                    stp("Skipping " + file.getName() + " (plugin " + existing.getName() + " already loaded from this jar)");
                    return;
                }

                String existingName = existingFile == null ? "unknown source" : existingFile.getName();
                stp("Plugin " + existing.getName() + " is already loaded from " + existingName + ", replacing with " + file.getName());

                Set<File> dependents = unload(existing, ReloadAware.PreUnloadReason.HOT_RELOAD);
                for (File dep : dependents) {
                    if (dep != null && !sameFile(dep, file)) {
                        deferredDependents.add(dep);
                    }
                }
            }

            for (String i : metadata.requiredDependencies()) {
                if (Bukkit.getPluginManager().getPlugin(i) == null) {
                    stp(f.getName() + " depends on " + i);
                    File fx = getPluginFile(i);

                    if (fx != null) {
                        load(fx);
                    } else {
                        stp("Missing dependency " + i + " for " + f.getName() + ", aborting load");
                        return;
                    }
                }
            }

            for (String i : metadata.optionalDependencies()) {
                if (Bukkit.getPluginManager().getPlugin(i) == null) {
                    File fx = getPluginFile(i);

                    if (fx != null) {
                        stp(f.getName() + " soft depends on " + i);
                        load(fx);
                    }
                }
            }

            stp("Calling loadPlugin for " + file.getName());
            boolean paperOnlyJar = isPaperPlugin(file) && !jarHasPluginYml(file);
            boolean paperRuntime = ServerPlatform.isPaperRuntime();
            validateRuntimeCompatibility(paperOnlyJar, paperRuntime, file.getName());
            Plugin target = Bukkit.getPluginManager().loadPlugin(file);

            if (target == null) {
                stp("loadPlugin returned null for " + file.getName());
                throw new InvalidPluginException("Unable to load plugin providers for " + file.getName());
            }

            boolean explicitOnLoad = shouldCallExplicitOnLoad();
            clearLoadedFileOverride(target.getName());

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

            registerLoadedFileOverride(target.getName(), file);
            invalidateJarMeta(file);
            stp("Enabled " + target.getName() + " successfully");

            if (!deferredDependents.isEmpty()) {
                stp("Reloading " + deferredDependents.size() + " dependent plugin(s) after replacement of " + target.getName());
                for (File dependent : deferredDependents) {
                    if (dependent != null && dependent.exists()) {
                        load(dependent);
                    }
                }
            }

            HealthCheckResult health = verifyPluginHealth(target, file);
            if (!health.ok()) {
                throw new InvalidPluginException("Post-load health check failed for " + target.getName() + ": " + health.summary());
            }

            rebuildServerCommandGraph();
            logTiming("load " + target.getName(), nanosToMillis(System.nanoTime() - startNs), "health=ok");
        } finally {
            visiting.remove(cycleKey);
            if (visiting.isEmpty()) {
                LOAD_VISITING.remove();
            }
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
        Set<File> deps = new HashSet<>();
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
            stp("Unloading " + plugin.getName());

            if (file == null) {
                stp("Could not resolve source jar for " + plugin.getName() + ", skipping file reset");
            }

            for (Plugin i : Bukkit.getPluginManager().getPlugins()) {
                if (i.equals(plugin)) {
                    continue;
                }

                if (i.getDescription().getSoftDepend().contains(plugin.getName())) {
                    stp(i.getName() + " soft depends on " + plugin.getName() + ". Playing it safe.");
                    deps.add(getPluginFile(i));
                }

                if (i.getDescription().getDepend().contains(plugin.getName())) {
                    stp(i.getName() + " depends on " + plugin.getName() + ". Playing it safe.");
                    deps.add(getPluginFile(i));
                }
            }

            if (plugin.getName().equals("WorldEdit")) {
                Plugin fa = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");

                if (fa != null) {
                    stp(fa.getName() + " (kind of) depends on " + plugin.getName() + ". Playing it safe.");
                    deps.add(getPluginFile(fa));
                }
            }

            for (File i : new HashSet<>(deps)) {
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

            scrubPluginCommands(plugin, commandMap, commands);
            scrubPluginServices(plugin);
            scrubPluginMessenger(plugin);
            removePaperPluginTracking(plugin);
            scrubBrigadierNodes(plugin);

            ClassLoader cl = plugin.getClass().getClassLoader();

            if (cl instanceof java.io.Closeable) {
                try {
                    ((java.io.Closeable) cl).close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

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
     * Best-effort removal of plugin command nodes from Paper's Brigadier/dispatcher graph.
     * Safe no-op on Spigot or when internals move.
     */
    private static void scrubBrigadierNodes(Plugin plugin) {
        if (plugin == null || !ServerPlatform.isPaperFamily()) {
            return;
        }

        try {
            Object server = Bukkit.getServer();
            // CraftServer#syncCommands rebuilds brigadier from SimpleCommandMap after our scrub.
            Method syncCommands = findPublicMethod(server.getClass(), "syncCommands");
            if (syncCommands != null) {
                // Deferred to rebuildServerCommandGraph; mark intent only if needed later.
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            // Fallback: walk common Paper command registrant fields and drop plugin-owned nodes.
            Object paperCommands = invokeStaticNoThrow("io.papermc.paper.command.brigadier.PaperCommands", "getInstance");
            if (paperCommands == null) {
                paperCommands = invokeStaticNoThrow("io.papermc.paper.command.brigadier.PaperBrigadier", "get");
            }
            if (paperCommands == null) {
                return;
            }

            for (Field field : getAllFields(paperCommands.getClass())) {
                if (!Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(paperCommands);
                if (!(value instanceof Map<?, ?> map)) {
                    continue;
                }

                List<Object> keys = new ArrayList<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object mapValue = entry.getValue();
                    if (mapValue == null) {
                        continue;
                    }
                    String text = mapValue.toString().toLowerCase(Locale.ROOT);
                    if (text.contains(plugin.getName().toLowerCase(Locale.ROOT))) {
                        keys.add(entry.getKey());
                    }
                }
                for (Object key : keys) {
                    map.remove(key);
                }
            }
        } catch (Throwable ignored) {
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

    private static List<Field> getAllFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    private static Object invokeStaticNoThrow(String className, String methodName) {
        try {
            Class<?> type = Class.forName(className);
            Method method = type.getMethod(methodName);
            return method.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void scrubPluginCommands(Plugin plugin, SimpleCommandMap commandMap, Map<String, Command> commands) {
        if (plugin == null || commandMap == null || commands == null) {
            return;
        }

        List<String> toRemove = new ArrayList<>();
        String pluginKey = plugin.getName().toLowerCase(Locale.ROOT);

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

        try {
            for (String commandName : plugin.getDescription().getCommands().keySet()) {
                toRemove.add(commandName);
                toRemove.add(commandName.toLowerCase(Locale.ROOT));
                toRemove.add(pluginKey + ":" + commandName.toLowerCase(Locale.ROOT));
            }
        } catch (Throwable ignored) {
        }

        for (String key : toRemove) {
            Command removed = commands.remove(key);
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

    private static boolean jarHasPluginYml(File file) {
        try (ZipFile z = new ZipFile(file)) {
            return z.getEntry("plugin.yml") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void validateRuntimeCompatibility(boolean paperOnlyJar, boolean paperRuntime, String sourceName) throws InvalidPluginException {
        if (paperOnlyJar && !paperRuntime) {
            throw new InvalidPluginException("Cannot load " + sourceName + ": paper-plugin.yml-only jars require a Paper-compatible runtime");
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
                    if (plugin.getName().equals(getPluginName(i))) {
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
                if (isPluginJar(i) && i.isFile() && getPluginName(i).equalsIgnoreCase(name)) {
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
        return Bukkit.getPluginsFolder();
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
