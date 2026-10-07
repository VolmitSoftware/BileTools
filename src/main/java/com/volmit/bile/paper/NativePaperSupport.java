package com.volmit.bile.paper;

import com.volmit.bile.ServerPlatform;
import org.bukkit.Bukkit;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class NativePaperSupport {
    private static final String ENTRYPOINT = "plugin.entrypoint.Entrypoint";
    private static final String RUNNER = "plugin.lifecycle.event.LifecycleEventRunner";
    private static final String COMMANDS = "command.brigadier.PaperCommands";
    private static final String OWNER = "plugin.lifecycle.event.LifecycleEventOwner";
    private static final String FILE_SOURCE = "plugin.provider.source.FileProviderSource";
    private static final Map<Plugin, Session> SESSIONS = Collections.synchronizedMap(new IdentityHashMap<>());

    private NativePaperSupport() {
    }

    public static boolean isRequested(File file) throws IOException, InvalidPluginException {
        return NativePaperContract.read(file).requested();
    }

    public static void validate(File file) throws InvalidPluginException {
        try {
            NativePaperContract.read(file).validate();
            validateRuntime();
            new Session().verifyCapabilities();
        } catch (IOException | ReflectiveOperationException | RuntimeException exception) {
            throw new InvalidPluginException("Experimental native Paper runtime loading is unavailable: " + exception.getMessage(), exception);
        }
    }

    public static void validateLoaded(Plugin plugin) throws InvalidPluginException {
        if (!isNative(plugin)) {
            return;
        }
        try {
            validateRuntime();
            verifyLifecycle(PaperReflection.call(plugin, "getPluginMeta"));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new InvalidPluginException("Cannot hot reload native Paper plugin " + plugin.getName() + ": " + exception.getMessage(), exception);
        }
    }

    public static Plugin load(File file) throws InvalidPluginException {
        requireServerThread();
        validate(file);
        Session session = null;
        String name = file.getName();
        try {
            NativePaperContract contract = NativePaperContract.read(file);
            name = contract.name();
            if (Bukkit.getPluginManager().getPlugin(name) != null) {
                throw new InvalidPluginException("Plugin " + name + " is already registered");
            }
            session = new Session();
            session.register(file.toPath());
            session.loadBootstrap();
            verifyLifecycle(session.metadata());
            Plugin plugin = session.loadPlugin();
            SESSIONS.put(plugin, session);
            plugin.onLoad();
            verifyLifecycle(session.metadata());
            return plugin;
        } catch (IOException | ReflectiveOperationException | RuntimeException | Error exception) {
            InvalidPluginException failure = new InvalidPluginException("Native Paper load failed for " + name, exception);
            rollbackUnregistered(session, name, failure);
            throw failure;
        } catch (InvalidPluginException exception) {
            rollbackUnregistered(session, name, exception);
            throw exception;
        }
    }

    public static void enable(Plugin plugin) throws InvalidPluginException {
        requireServerThread();
        try {
            Object commands = PaperReflection.singleton(COMMANDS);
            Field contextField = PaperReflection.field(commands.getClass(), "currentContext");
            Field invalidField = PaperReflection.field(commands.getClass(), "invalid");
            Object previousContext = contextField.get(commands);
            boolean previousInvalid = invalidField.getBoolean(commands);
            try {
                PaperReflection.call(commands, "setValid");
                PaperReflection.call(commands, "setCurrentContext", plugin);
                Bukkit.getPluginManager().enablePlugin(plugin);
                if (!plugin.isEnabled()) {
                    throw new InvalidPluginException("Native Paper plugin " + plugin.getName() + " failed to enable");
                }
                Object metadata = PaperReflection.call(plugin, "getPluginMeta");
                verifyLifecycle(metadata);
                fireCommands(commands, metadata);
            } finally {
                contextField.set(commands, previousContext);
                invalidField.setBoolean(commands, previousInvalid);
            }
        } catch (ReflectiveOperationException | RuntimeException | Error exception) {
            throw new InvalidPluginException("Native Paper enable failed for " + plugin.getName(), exception);
        }
    }

    public static void cleanup(Plugin plugin) throws InvalidPluginException {
        if (!isNative(plugin)) {
            return;
        }
        requireServerThread();
        Session session = SESSIONS.remove(plugin);
        List<Exception> failures = new ArrayList<>();
        try {
            Object metadata = PaperReflection.call(plugin, "getPluginMeta");
            removeLifecycle(metadata);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failures.add(exception);
        }
        try {
            unregisterClassloader(plugin.getClass().getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            failures.add(exception);
        }
        try {
            if (session != null) {
                session.release();
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            failures.add(exception);
        }
        if (!failures.isEmpty()) {
            InvalidPluginException failure = new InvalidPluginException("Native Paper cleanup failed for " + plugin.getName());
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    public static boolean isNative(Plugin plugin) {
        return plugin != null && plugin.getClass().getClassLoader().getClass().getName()
                .equals("io.papermc.paper.plugin.entrypoint.classloader.PaperPluginClassLoader");
    }

    static void validateVersion(String version, int build, boolean regionized) throws InvalidPluginException {
        if (regionized || !"26.3".equals(version) || build != 142) {
            throw new InvalidPluginException("Experimental native Paper loading is limited to Paper 26.3 build 142; this server requires a restart");
        }
    }

    static void visitLifecycle(List<?> eventTypes, Object commandsEvent, Object metadata, boolean remove)
            throws ReflectiveOperationException {
        for (Object eventType : eventTypes) {
            List<Object> unsupported = new ArrayList<>();
            Predicate<Object> predicate = handler -> {
                try {
                    Object owner = PaperReflection.call(handler, "owner");
                    boolean owned = PaperReflection.call(owner, "getPluginMeta") == metadata;
                    if (owned && eventType != commandsEvent && !remove) {
                        unsupported.add(eventType);
                    }
                    return remove && owned;
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Cannot identify lifecycle registration owner", exception);
                }
            };
            PaperReflection.call(eventType, "removeMatching", predicate);
            if (!unsupported.isEmpty()) {
                throw new IllegalStateException("Startup-only lifecycle event " + PaperReflection.call(eventType, "name")
                        + " requires a full restart; native runtime loading supports COMMANDS registrations only");
            }
        }
    }

    private static void validateRuntime() throws ReflectiveOperationException, InvalidPluginException {
        if (!ServerPlatform.isPaperRuntime() || !"Paper".equals(Bukkit.getServer().getName())) {
            throw new InvalidPluginException("Experimental native Paper loading requires the tested Paper server runtime");
        }
        Object buildInfo = PaperReflection.call(PaperReflection.type("ServerBuildInfo"), "buildInfo");
        Object build = PaperReflection.call(buildInfo, "buildNumber");
        int buildNumber = build instanceof OptionalInt optional ? optional.orElse(-1) : -1;
        String version = String.valueOf(PaperReflection.call(buildInfo, "minecraftVersionId"));
        validateVersion(version, buildNumber, ServerPlatform.isRegionizedThreading());
    }

    private static void requireServerThread() throws InvalidPluginException {
        if (!Bukkit.isPrimaryThread()) {
            throw new InvalidPluginException("Native Paper lifecycle operations must run on the server thread");
        }
    }

    private static Object commandEvent() throws ReflectiveOperationException {
        return PaperReflection.field(PaperReflection.type("plugin.lifecycle.event.types.LifecycleEvents"), "COMMANDS").get(null);
    }

    private static void verifyLifecycle(Object metadata) throws ReflectiveOperationException {
        Object runner = PaperReflection.singleton(RUNNER);
        visitLifecycle((List<?>) PaperReflection.read(runner, "lifecycleEventTypes"), commandEvent(), metadata, false);
    }

    private static void removeLifecycle(Object metadata) throws ReflectiveOperationException {
        Object runner = PaperReflection.singleton(RUNNER);
        visitLifecycle((List<?>) PaperReflection.read(runner, "lifecycleEventTypes"), commandEvent(), metadata, true);
    }

    private static void fireCommands(Object commands, Object metadata) throws ReflectiveOperationException {
        Class<?> causeType = PaperReflection.type("plugin.lifecycle.event.registrar.ReloadableRegistrarEvent$Cause");
        Object cause = PaperReflection.field(causeType, "RELOAD").get(null);
        Class<?> eventType = PaperReflection.type("plugin.lifecycle.event.registrar.RegistrarEventImpl$ReloadableImpl");
        Constructor<?> constructor = eventType.getConstructor(
                PaperReflection.type("plugin.lifecycle.event.registrar.PaperRegistrar"), Class.class, causeType);
        Object event = constructor.newInstance(commands, PaperReflection.type(OWNER), cause);
        Predicate<Object> ownerFilter = owner -> {
            try {
                return PaperReflection.call(owner, "getPluginMeta") == metadata;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot identify command lifecycle owner", exception);
            }
        };
        PaperReflection.call(PaperReflection.singleton(RUNNER), "callEvent", commandEvent(), event, ownerFilter);
    }

    private static void unregisterClassloader(ClassLoader loader) throws ReflectiveOperationException {
        Object storage = PaperReflection.call(PaperReflection.type("plugin.provider.classloader.PaperClassLoaderStorage"), "instance");
        PaperReflection.call(storage, "unregisterClassloader", loader);
    }

    private static void rollbackUnregistered(Session session, String name, InvalidPluginException failure) {
        if (session == null) {
            return;
        }
        Plugin registered = Bukkit.getPluginManager().getPlugin(name);
        if (registered != null) {
            SESSIONS.put(registered, session);
            return;
        }
        try {
            session.release();
        } catch (ReflectiveOperationException | IOException | RuntimeException exception) {
            failure.addSuppressed(exception);
        }
    }

    private static final class Session implements InvocationHandler {
        private final Object pluginEntrypoint;
        private final Object bootstrapEntrypoint;
        private final Object launchPluginStorage;
        private final Object launchBootstrapStorage;
        private final Object pluginStorage;
        private final Object bootstrapStorage;
        private final List<Object> pluginProviders = new ArrayList<>();
        private final List<Object> bootstrapProviders = new ArrayList<>();

        private Session() throws ReflectiveOperationException {
            Class<?> entrypointType = PaperReflection.type(ENTRYPOINT);
            pluginEntrypoint = PaperReflection.field(entrypointType, "PLUGIN").get(null);
            bootstrapEntrypoint = PaperReflection.field(entrypointType, "BOOTSTRAPPER").get(null);
            Object launch = PaperReflection.singleton("plugin.entrypoint.LaunchEntryPointHandler");
            launchPluginStorage = PaperReflection.call(launch, "get", pluginEntrypoint);
            launchBootstrapStorage = PaperReflection.call(launch, "get", bootstrapEntrypoint);
            pluginStorage = PaperReflection.type("plugin.storage.ServerPluginProviderStorage").getConstructor().newInstance();
            bootstrapStorage = PaperReflection.type("plugin.storage.BootstrapProviderStorage").getConstructor().newInstance();
        }

        private void verifyCapabilities() throws ReflectiveOperationException {
            Class<?> source = PaperReflection.type(FILE_SOURCE);
            source.getConstructor(Function.class);
            source.getMethod("registerProviders", PaperReflection.type("plugin.entrypoint.EntrypointHandler"), Path.class);
            verifyStorage(pluginStorage);
            verifyStorage(bootstrapStorage);
            PaperReflection.field(launchPluginStorage.getClass(), "providers");
            PaperReflection.field(launchBootstrapStorage.getClass(), "providers");
            Object runner = PaperReflection.singleton(RUNNER);
            PaperReflection.field(runner.getClass(), "lifecycleEventTypes");
            PaperReflection.method(runner.getClass(), "callEvent", 3);
            PaperReflection.method(commandEvent().getClass(), "removeMatching", 1);
            Object commands = PaperReflection.singleton(COMMANDS);
            PaperReflection.field(commands.getClass(), "currentContext");
            PaperReflection.field(commands.getClass(), "invalid");
            PaperReflection.method(commands.getClass(), "setCurrentContext", 1);
            PaperReflection.method(commands.getClass(), "setValid", 0);
            PaperReflection.call(commands, "getDispatcherInternal");
            Class<?> parent = PaperReflection.type("plugin.provider.type.paper.PaperPluginParent");
            PaperReflection.field(parent, "classLoader");
            PaperReflection.field(PaperReflection.type("plugin.provider.type.paper.PaperPluginParent$PaperServerPluginProvider"), "this$0");
            Object storage = PaperReflection.call(PaperReflection.type("plugin.provider.classloader.PaperClassLoaderStorage"), "instance");
            PaperReflection.method(storage.getClass(), "unregisterClassloader", 1);
        }

        private void verifyStorage(Object storage) throws ReflectiveOperationException {
            Object strategy = PaperReflection.read(storage, "strategy");
            PaperReflection.method(strategy.getClass(), "loadProviders", 2);
            PaperReflection.method(storage.getClass(), "createDependencyTree", 0);
            PaperReflection.method(storage.getClass(), "register", 1);
        }

        private void register(Path path) throws ReflectiveOperationException, InvalidPluginException {
            Class<?> handlerType = PaperReflection.type("plugin.entrypoint.EntrypointHandler");
            Object handler = Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[]{handlerType}, this);
            Function<Path, String> formatter = source -> "Native Paper plugin " + source;
            Object source = PaperReflection.type(FILE_SOURCE).getConstructor(Function.class).newInstance(formatter);
            PaperReflection.call(source, "registerProviders", handler, path);
            if (pluginProviders.size() != 1) {
                throw new InvalidPluginException("Expected one native Paper plugin provider, found " + pluginProviders.size());
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws ReflectiveOperationException {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "BileTools native Paper provider registration";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new IllegalStateException(method.getName());
                };
            }
            if (!method.getName().equals("register") || arguments == null || arguments.length != 2) {
                throw new UnsupportedOperationException("Unsupported Paper entrypoint operation " + method.getName());
            }
            Object provider = arguments[1];
            if (arguments[0] == pluginEntrypoint) {
                pluginProviders.add(provider);
                PaperReflection.call(launchPluginStorage, "register", provider);
            } else if (arguments[0] == bootstrapEntrypoint) {
                bootstrapProviders.add(provider);
                PaperReflection.call(launchBootstrapStorage, "register", provider);
            } else {
                throw new UnsupportedOperationException("Unsupported Paper entrypoint " + arguments[0]);
            }
            return null;
        }

        private void loadBootstrap() throws ReflectiveOperationException, InvalidPluginException {
            if (!bootstrapProviders.isEmpty()) {
                loadProviders(bootstrapStorage, launchBootstrapStorage, bootstrapProviders);
            }
        }

        private Plugin loadPlugin() throws ReflectiveOperationException, InvalidPluginException {
            List<?> pairs = loadProviders(pluginStorage, launchPluginStorage, pluginProviders);
            Object plugin = PaperReflection.call(pairs.get(0), "provided");
            if (!(plugin instanceof Plugin result)) {
                throw new InvalidPluginException("Paper returned a non-plugin provider result");
            }
            return result;
        }

        private List<?> loadProviders(Object storage, Object launchStorage, List<Object> providers)
                throws ReflectiveOperationException, InvalidPluginException {
            Object tree = PaperReflection.call(storage, "createDependencyTree");
            Iterable<?> existing = (Iterable<?>) PaperReflection.call(launchStorage, "getRegisteredProviders");
            for (Object provider : existing) {
                if (!providers.contains(provider)) {
                    PaperReflection.call(tree, "add", provider);
                }
            }
            Object strategy = PaperReflection.read(storage, "strategy");
            Object result = PaperReflection.call(strategy, "loadProviders", new ArrayList<>(providers), tree);
            if (!(result instanceof List<?> pairs) || pairs.size() != providers.size()) {
                throw new InvalidPluginException("Paper rejected native plugin providers; see the server exception for the lifecycle failure");
            }
            return pairs;
        }

        private Object metadata() throws ReflectiveOperationException {
            return PaperReflection.call(pluginProviders.get(0), "getMeta");
        }

        private void release() throws ReflectiveOperationException, IOException {
            Set<Object> metadata = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Closeable> files = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Object> providers = new ArrayList<>(pluginProviders);
            providers.addAll(bootstrapProviders);
            for (Object provider : providers) {
                metadata.add(PaperReflection.call(provider, "getMeta"));
                Object parent = PaperReflection.read(provider, "this$0");
                loaders.add((ClassLoader) PaperReflection.read(parent, "classLoader"));
                Object file = PaperReflection.call(provider, "file");
                if (file instanceof Closeable closeable) {
                    files.add(closeable);
                }
            }
            for (Object owner : metadata) {
                removeLifecycle(owner);
            }
            removeProviders(launchPluginStorage, pluginProviders);
            removeProviders(launchBootstrapStorage, bootstrapProviders);
            for (ClassLoader loader : loaders) {
                unregisterClassloader(loader);
                if (loader instanceof Closeable closeable) {
                    closeable.close();
                }
            }
            for (Closeable file : files) {
                file.close();
            }
        }

        private void removeProviders(Object storage, List<Object> owned) throws ReflectiveOperationException {
            Object registered = PaperReflection.read(storage, "providers");
            if (!(registered instanceof List<?> providers)) {
                throw new IllegalStateException("Paper provider registry is not a list");
            }
            providers.removeIf(provider -> owned.stream().anyMatch(candidate -> candidate == provider));
        }
    }
}
