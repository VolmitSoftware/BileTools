package com.volmit.bile;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.bukkit.plugin.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class BileUtils {
    private static final Map<String, File> SOURCE_FILE_OVERRIDES = new ConcurrentHashMap<>();
    private static final Map<String, File> RUNTIME_PLUGIN_FILES = new ConcurrentHashMap<>();

    private static String key(String pluginName) {
        return pluginName.toLowerCase(Locale.ROOT);
    }

    private static void registerLoadedFileOverride(String pluginName, File sourceFile, File runtimeFile) {
        if (pluginName == null || sourceFile == null) {
            return;
        }

        SOURCE_FILE_OVERRIDES.put(key(pluginName), sourceFile);
        if (runtimeFile != null) {
            RUNTIME_PLUGIN_FILES.put(key(pluginName), runtimeFile);
        }
    }

    private static void clearLoadedFileOverride(String pluginName) {
        if (pluginName == null) {
            return;
        }

        SOURCE_FILE_OVERRIDES.remove(key(pluginName));
        File runtime = RUNTIME_PLUGIN_FILES.remove(key(pluginName));

        if (runtime != null && runtime.exists() && runtime.isFile()) {
            if (!runtime.delete()) {
                runtime.deleteOnExit();
            }
        }
    }

    public static void delete(Plugin p) throws IOException {
        File f = getPluginFile(p);
        if (!BileTools.cfg.has("archive-plugins") || BileTools.cfg.getBoolean("archive-plugins")) {
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

        if (!BileTools.cfg.has("archive-plugins") || BileTools.cfg.getBoolean("archive-plugins")) {
            PluginDescriptionFile fx = getPluginDescription(f);
            copy(f, new File(getBackupLocation(fx.getName()), fx.getVersion() + ".jar"));
        }
        f.delete();
    }

    public static void reload(Plugin p) throws IOException, UnknownDependencyException, InvalidPluginException, InvalidDescriptionException, InvalidConfigurationException {
        File f = getPluginFile(p);

        if (!BileTools.cfg.has("archive-plugins") || BileTools.cfg.getBoolean("archive-plugins")) {
            backup(p);
        }
        Set<File> x = unload(p);

        for (File i : x) {
            load(i);
        }

        load(f);
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

        stp("Loading " + getPluginName(file) + " " + getPluginVersion(file) + " from " + file.getName());
        PluginDescriptionFile f = getPluginDescription(file);
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

            Set<File> dependents = unload(existing);
            for (File dep : dependents) {
                if (dep != null && !sameFile(dep, file)) {
                    deferredDependents.add(dep);
                }
            }
        }

        for (String i : f.getDepend()) {
            if (Bukkit.getPluginManager().getPlugin(i) == null) {
                stp(getPluginName(file) + " depends on " + i);
                File fx = getPluginFile(i);

                if (fx != null) {
                    load(fx);
                } else {
                    stp("Missing dependency " + i + " for " + getPluginName(file) + ", aborting load");
                    return;
                }
            }
        }

        for (String i : f.getSoftDepend()) {
            if (Bukkit.getPluginManager().getPlugin(i) == null) {
                File fx = getPluginFile(i);

                if (fx != null) {
                    stp(getPluginName(file) + " soft depends on " + i);
                    load(fx);
                }
            }
        }

        stp("Calling loadPlugin for " + file.getName());
        Plugin target = null;
        boolean usedForcePaperLoader = false;

        try {
            target = Bukkit.getPluginManager().loadPlugin(file);
        } catch (Throwable e) {
            if (e.getCause() instanceof IllegalStateException
                    && e.getCause().getMessage() != null
                    && e.getCause().getMessage().contains("paper plugin")) {
                stp("Paper blocked runtime loading, attempting compatibility runtime load for " + file.getName());
                usedForcePaperLoader = true;
                target = loadForcePaper(file);
            } else {
                throw e;
            }
        }

        if (target == null) {
            stp("loadPlugin returned null for " + file.getName());
            throw new InvalidPluginException("Unable to load plugin providers for " + file.getName());
        }

        boolean explicitOnLoad = !usedForcePaperLoader && shouldCallExplicitOnLoad();
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

        try {
            PluginManager pm = Bukkit.getPluginManager();
            Field pluginsField = pm.getClass().getDeclaredField("plugins");
            pluginsField.setAccessible(true);
            Object pluginsObj = pluginsField.get(pm);

            if (pluginsObj instanceof List) {
                List<Plugin> plugins = (List<Plugin>) pluginsObj;
                if (!plugins.contains(target)) {
                    plugins.add(target);
                }
            }

            Field lookupField = pm.getClass().getDeclaredField("lookupNames");
            lookupField.setAccessible(true);
            Object lookupObj = lookupField.get(pm);

            if (lookupObj instanceof Map) {
                Map<String, Plugin> lookup = (Map<String, Plugin>) lookupObj;
                lookup.put(target.getName().toLowerCase(), target);
            }
        } catch (Throwable ignored) {
        }

        stp("Enabled " + target.getName() + " successfully");

        if (!deferredDependents.isEmpty()) {
            stp("Reloading " + deferredDependents.size() + " dependent plugin(s) after replacement of " + target.getName());
            for (File dependent : deferredDependents) {
                if (dependent != null && dependent.exists()) {
                    load(dependent);
                }
            }
        }
    }

    private static Plugin loadForcePaper(File file) {
        Throwable runtimeError = null;

        try {
            stp("Trying Paper runtime internals for " + file.getName());
            Plugin plugin = loadPaperViaRuntimeInternals(file);
            if (plugin != null) {
                registerLoadedFileOverride(plugin.getName(), file, null);
                stp("Paper runtime load succeeded for " + plugin.getName());
                return plugin;
            }
        } catch (Throwable e) {
            runtimeError = e;
            stp("Paper runtime load failed for " + file.getName() + ": " + rootMessage(e));
        }

        try {
            stp("Trying compatibility shim for " + file.getName());
            Plugin plugin = loadPaperViaCompatibilityShim(file);
            if (plugin != null) {
                stp("Compatibility shim load succeeded for " + plugin.getName());
            }
            return plugin;
        } catch (Throwable e) {
            stp("Compatibility shim load failed for " + file.getName() + ": " + rootMessage(e));

            if (runtimeError != null) {
                runtimeError.printStackTrace();
            }
            e.printStackTrace();

            return null;
        }
    }

    private static Plugin loadPaperViaRuntimeInternals(File file) throws Exception {
        Class<?> pluginManagerClass = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
        Method getInstance = pluginManagerClass.getMethod("getInstance");
        Object pluginManager = getInstance.invoke(null);

        if (pluginManager == null) {
            throw new IllegalStateException("PaperPluginManagerImpl.getInstance() returned null");
        }

        Field instanceManagerField = pluginManagerClass.getDeclaredField("instanceManager");
        instanceManagerField.setAccessible(true);
        Object instanceManager = instanceManagerField.get(pluginManager);

        Field dependencyTreeField = instanceManager.getClass().getDeclaredField("dependencyTree");
        dependencyTreeField.setAccessible(true);
        Object dependencyTree = dependencyTreeField.get(instanceManager);

        Class<?> singularStorageClass = Class.forName("io.papermc.paper.plugin.manager.SingularRuntimePluginProviderStorage");
        Constructor<?> singularCtor = singularStorageClass.getDeclaredConstructors()[0];
        singularCtor.setAccessible(true);
        Object pluginStorage = singularCtor.newInstance(dependencyTree);

        Class<?> bootstrapStorageClass = Class.forName("io.papermc.paper.plugin.storage.BootstrapProviderStorage");
        Object bootstrapStorage = bootstrapStorageClass.getDeclaredConstructor().newInstance();

        Class<?> entrypointClass = Class.forName("io.papermc.paper.plugin.entrypoint.Entrypoint");
        Class<?> entrypointHandlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.EntrypointHandler");
        Object bootstrapEntrypoint = entrypointClass.getField("BOOTSTRAPPER").get(null);
        Object pluginEntrypoint = entrypointClass.getField("PLUGIN").get(null);

        InvocationHandler invocationHandler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return "BileRuntimeEntrypointHandler";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0];
                }
            }

            String methodName = method.getName();
            if ("register".equals(methodName) && args != null && args.length == 2) {
                Object entrypoint = args[0];
                Object provider = args[1];

                if (entrypoint == bootstrapEntrypoint) {
                    invokeCompatibleMethod(bootstrapStorage, "register", provider);
                    return null;
                }

                if (entrypoint == pluginEntrypoint) {
                    invokeCompatibleMethod(pluginStorage, "register", wrapPaperProviderIfNeeded(provider));
                    return null;
                }

                throw new IllegalArgumentException("Unsupported entrypoint during runtime load: " + entrypoint);
            }

            if ("enter".equals(methodName) && args != null && args.length == 1) {
                Object entrypoint = args[0];

                if (entrypoint == bootstrapEntrypoint) {
                    invokeCompatibleMethod(bootstrapStorage, "enter");
                    return null;
                }

                if (entrypoint == pluginEntrypoint) {
                    invokeCompatibleMethod(pluginStorage, "enter");
                    return null;
                }

                throw new IllegalArgumentException("Unsupported entrypoint enter during runtime load: " + entrypoint);
            }

            throw new UnsupportedOperationException("Unsupported EntrypointHandler method: " + methodName);
        };

        Object entrypointHandler = Proxy.newProxyInstance(
                entrypointHandlerClass.getClassLoader(),
                new Class[]{entrypointHandlerClass},
                invocationHandler
        );

        Class<?> paperInstanceManagerClass = Class.forName("io.papermc.paper.plugin.manager.PaperPluginInstanceManager");
        Field fileProviderSourceField = paperInstanceManagerClass.getDeclaredField("FILE_PROVIDER_SOURCE");
        fileProviderSourceField.setAccessible(true);
        Object fileProviderSource = fileProviderSourceField.get(null);

        Path prepared = (Path) invokeCompatibleMethod(fileProviderSource, "prepareContext", file.toPath());
        invokeCompatibleMethod(fileProviderSource, "registerProviders", entrypointHandler, prepared);
        invokeCompatibleMethod(entrypointHandler, "enter", bootstrapEntrypoint);
        invokeCompatibleMethod(entrypointHandler, "enter", pluginEntrypoint);

        @SuppressWarnings("unchecked")
        Optional<Plugin> loaded = (Optional<Plugin>) invokeCompatibleMethod(pluginStorage, "getSingleLoaded");
        return loaded.orElse(null);
    }

    private static Object wrapPaperProviderIfNeeded(Object provider) {
        if (provider == null) {
            return null;
        }

        if (!provider.getClass().getName().contains("PaperPluginParent$PaperServerPluginProvider")) {
            return provider;
        }

        Set<Class<?>> interfaces = collectAllInterfaces(provider.getClass());
        if (interfaces.isEmpty()) {
            return provider;
        }

        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                String name = method.getName();
                if ("toString".equals(name)) {
                    return provider.toString();
                }
                if ("hashCode".equals(name)) {
                    return provider.hashCode();
                }
                if ("equals".equals(name) && args != null && args.length == 1) {
                    return proxy == args[0];
                }
            }

            Method target = findCompatibleMethod(provider.getClass(), method.getName(), args == null ? new Object[0] : args);
            return target.invoke(provider, args);
        };

        return Proxy.newProxyInstance(
                provider.getClass().getClassLoader(),
                interfaces.toArray(new Class[0]),
                handler
        );
    }

    private static Set<Class<?>> collectAllInterfaces(Class<?> type) {
        Set<Class<?>> interfaces = new LinkedHashSet<>();

        while (type != null && type != Object.class) {
            interfaces.addAll(Arrays.asList(type.getInterfaces()));
            type = type.getSuperclass();
        }

        return interfaces;
    }

    private static Plugin loadPaperViaCompatibilityShim(File sourceFile) throws IOException, InvalidDescriptionException, InvalidPluginException {
        File shim = createPaperCompatibilityShim(sourceFile);
        Plugin loaded = Bukkit.getPluginManager().loadPlugin(shim);

        if (loaded != null) {
            registerLoadedFileOverride(loaded.getName(), sourceFile, shim);
        } else if (shim.exists() && !shim.delete()) {
            shim.deleteOnExit();
        }

        return loaded;
    }

    private static File createPaperCompatibilityShim(File sourceFile) throws IOException, InvalidDescriptionException {
        File shimDir = new File(new File(BileTools.bile.getDataFolder(), "temp"), "paper-shims");
        shimDir.mkdirs();

        File shim = new File(shimDir, sourceFile.getName().replace(".jar", "") + "-shim-" + UUID.randomUUID() + ".jar");
        byte[] paperPluginYml = null;
        boolean hasPluginYml = false;

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(sourceFile));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(shim))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if ("paper-plugin.yml".equalsIgnoreCase(name)) {
                    paperPluginYml = readAllBytes(zis);
                    continue;
                }

                if ("plugin.yml".equalsIgnoreCase(name)) {
                    hasPluginYml = true;
                }

                ZipEntry out = new ZipEntry(name);
                out.setTime(entry.getTime());
                zos.putNextEntry(out);

                int read;
                while ((read = zis.read(buffer)) != -1) {
                    zos.write(buffer, 0, read);
                }

                zos.closeEntry();
            }

            if (!hasPluginYml) {
                if (paperPluginYml == null) {
                    throw new InvalidDescriptionException("No plugin.yml or paper-plugin.yml found in " + sourceFile.getName());
                }

                String pluginYml = buildPluginYmlFromPaperPluginYml(paperPluginYml, sourceFile.getName());
                ZipEntry out = new ZipEntry("plugin.yml");
                zos.putNextEntry(out);
                zos.write(pluginYml.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }

        return shim;
    }

    private static String buildPluginYmlFromPaperPluginYml(byte[] paperPluginYml, String sourceName) throws InvalidDescriptionException {
        try {
            YamlConfiguration paper = new YamlConfiguration();
            paper.loadFromString(new String(paperPluginYml, StandardCharsets.UTF_8));

            String name = paper.getString("name");
            String main = paper.getString("main");
            String version = paper.getString("version");

            if (name == null || name.isEmpty() || main == null || main.isEmpty()) {
                throw new InvalidDescriptionException("paper-plugin.yml missing required name/main in " + sourceName);
            }

            YamlConfiguration plugin = new YamlConfiguration();
            plugin.set("name", name);
            plugin.set("main", main);
            plugin.set("version", version == null || version.isEmpty() ? "1.0.0" : version);

            if (paper.isString("api-version")) {
                plugin.set("api-version", paper.getString("api-version"));
            }

            if (paper.isString("load")) {
                plugin.set("load", paper.getString("load"));
            }

            if (paper.isString("description")) {
                plugin.set("description", paper.getString("description"));
            }

            if (paper.isString("website")) {
                plugin.set("website", paper.getString("website"));
            }

            if (paper.isString("prefix")) {
                plugin.set("prefix", paper.getString("prefix"));
            }

            if (paper.isList("authors")) {
                plugin.set("authors", paper.getStringList("authors"));
            } else if (paper.isString("author")) {
                plugin.set("author", paper.getString("author"));
            }

            if (paper.isList("provides")) {
                plugin.set("provides", paper.getStringList("provides"));
            }

            if (paper.isList("libraries")) {
                plugin.set("libraries", paper.getStringList("libraries"));
            }

            if (paper.isString("loader")) {
                plugin.set("paper-plugin-loader", paper.getString("loader"));
            }

            if (paper.contains("commands")) {
                plugin.set("commands", paper.get("commands"));
            }

            if (paper.contains("permissions")) {
                plugin.set("permissions", paper.get("permissions"));
            }

            if (paper.contains("default-permission")) {
                plugin.set("default-permission", paper.get("default-permission"));
            }

            if (paper.contains("folia-supported")) {
                plugin.set("folia-supported", paper.get("folia-supported"));
            }

            List<String> depend = new ArrayList<>();
            List<String> softDepend = new ArrayList<>();
            List<String> loadBefore = new ArrayList<>();

            ConfigurationSection serverDependencies = paper.getConfigurationSection("dependencies.server");
            if (serverDependencies != null) {
                for (String dependencyName : serverDependencies.getKeys(false)) {
                    ConfigurationSection dependency = serverDependencies.getConfigurationSection(dependencyName);
                    boolean required = dependency == null || dependency.getBoolean("required", true);
                    String load = dependency == null ? null : dependency.getString("load");

                    if ("BEFORE".equalsIgnoreCase(load)) {
                        loadBefore.add(dependencyName);
                    }

                    if (required) {
                        depend.add(dependencyName);
                    } else {
                        softDepend.add(dependencyName);
                    }
                }
            }

            if (paper.isList("depend")) {
                depend.addAll(paper.getStringList("depend"));
            }

            if (paper.isList("softdepend")) {
                softDepend.addAll(paper.getStringList("softdepend"));
            }

            if (paper.isList("loadbefore")) {
                loadBefore.addAll(paper.getStringList("loadbefore"));
            }

            if (!depend.isEmpty()) {
                plugin.set("depend", dedupe(depend));
            }

            if (!softDepend.isEmpty()) {
                plugin.set("softdepend", dedupe(softDepend));
            }

            if (!loadBefore.isEmpty()) {
                plugin.set("loadbefore", dedupe(loadBefore));
            }

            return plugin.saveToString();
        } catch (InvalidConfigurationException e) {
            throw new InvalidDescriptionException(e);
        }
    }

    private static List<String> dedupe(List<String> values) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                set.add(value);
            }
        }
        return new ArrayList<>(set);
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
        if (findFieldInHierarchy(pluginManager.getClass(), "paperPluginManager") != null) {
            return true;
        }

        String className = pluginManager.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("paper") || className.contains("purpur")) {
            return true;
        }

        try {
            Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
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

    @SuppressWarnings("unchecked")
    public static Set<File> unload(Plugin plugin) {
        Set<File> deps = new HashSet<>();
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
            deps.addAll(unload(getPlugin(i)));
        }

        Bukkit.getScheduler().cancelTasks(plugin);
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
            pluginManager.disablePlugin(plugin);

            try {
                Field pluginsField = Bukkit.getPluginManager().getClass().getDeclaredField("plugins");
                Field lookupNamesField = Bukkit.getPluginManager().getClass().getDeclaredField("lookupNames");
                pluginsField.setAccessible(true);
                plugins = (List<Plugin>) pluginsField.get(pluginManager);
                lookupNamesField.setAccessible(true);
                names = (Map<String, Plugin>) lookupNamesField.get(pluginManager);

                try {
                    Field listenersField = Bukkit.getPluginManager().getClass().getDeclaredField("listeners");
                    listenersField.setAccessible(true);
                    listeners = (Map<Event, SortedSet<RegisteredListener>>) listenersField.get(pluginManager);
                } catch (Exception e) {
                    reloadlisteners = false;
                }

                Field commandMapField = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
                Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
                commandMapField.setAccessible(true);
                commandMap = (SimpleCommandMap) commandMapField.get(pluginManager);
                knownCommandsField.setAccessible(true);
                commands = (Map<String, Command>) knownCommandsField.get(commandMap);
            } catch (Throwable e) {
                e.printStackTrace();
                return new HashSet<>();
            }
        }

        pluginManager.disablePlugin(plugin);

        if (plugins != null) {
            plugins.remove(plugin);
        }

        if (names != null) {
            names.remove(name);
        }

        if (listeners != null && reloadlisteners) {
            for (SortedSet<RegisteredListener> set : listeners.values()) {
                set.removeIf(value -> value.getPlugin() == plugin);
            }
        }

        if (commandMap != null) {
            List<String> toRemove = new ArrayList<>();

            for (Map.Entry<String, Command> entry : commands.entrySet()) {
                if (entry.getValue() instanceof PluginCommand) {
                    PluginCommand c = (PluginCommand) entry.getValue();

                    if (c.getPlugin() == plugin) {
                        c.unregister(commandMap);
                        toRemove.add(entry.getKey());
                    }
                }
            }

            for (String key : toRemove) {
                commands.remove(key);
            }
        }

        removePaperPluginTracking(plugin);

        ClassLoader cl = plugin.getClass().getClassLoader();

        if (cl instanceof java.io.Closeable) {
            try {
                ((java.io.Closeable) cl).close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }

        String idx = UUID.randomUUID().toString();
        File ff = new File(new File(BileTools.bile.getDataFolder(), "temp"), idx);
        System.gc();

        if (file != null) {
            try {
                copy(file, ff);
                file.delete();
                copy(ff, file);
                BileTools.bile.reset(file);
                ff.deleteOnExit();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        clearLoadedFileOverride(plugin.getName());
        return deps;
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

        for (File i : getPluginsFolder().listFiles()) {
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

        for (File i : getPluginsFolder().listFiles()) {
            if (isPluginJar(i) && i.isFile() && i.getName().equalsIgnoreCase(name)) {
                return i;
            }
        }

        for (File i : getPluginsFolder().listFiles()) {
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
        return BileTools.bile.getDataFolder().getParentFile();
    }

    public static List<String> getDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getDepend();
    }

    public static List<String> getSoftDependencies(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getSoftDepend();
    }

    public static String getPluginVersion(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getVersion();
    }

    public static String getPluginName(File file) throws IOException, InvalidConfigurationException, InvalidDescriptionException {
        return getPluginDescription(file).getName();
    }

    public static PluginDescriptionFile getPluginDescription(File file) throws IOException, InvalidDescriptionException {
        try (ZipFile z = new ZipFile(file)) {
            ZipEntry pluginYml = z.getEntry("plugin.yml");
            if (pluginYml != null) {
                try (InputStream is = z.getInputStream(pluginYml)) {
                    return new PluginDescriptionFile(is);
                }
            }

            ZipEntry paperYml = z.getEntry("paper-plugin.yml");
            if (paperYml == null) {
                throw new InvalidDescriptionException("No plugin.yml or paper-plugin.yml found in " + file.getName());
            }

            byte[] paperBytes;
            try (InputStream is = z.getInputStream(paperYml)) {
                paperBytes = readAllBytes(is);
            }

            try {
                return new PluginDescriptionFile(new ByteArrayInputStream(paperBytes));
            } catch (InvalidDescriptionException invalidPaperAsPluginYml) {
                String converted = buildPluginYmlFromPaperPluginYml(paperBytes, file.getName());
                return new PluginDescriptionFile(new ByteArrayInputStream(converted.getBytes(StandardCharsets.UTF_8)));
            }
        }
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
