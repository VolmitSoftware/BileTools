package com.volmit.bile.velocity;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyInternals {
    static final String CAP_PLUGIN_MAPS = "plugin-manager.maps";
    static final String CAP_LOADER_CANDIDATE = "plugin-loader.candidate";
    static final String CAP_LOADER_CREATE = "plugin-loader.create";
    static final String CAP_LOADER_MODULE = "plugin-loader.module";
    static final String CAP_CLASSLOADER_CLOSE = "classloader.close";
    static final String CAP_CLASSLOADER_REGISTRY = "classloader.registry";
    static final String CAP_REGISTER_INTERNALLY = "event-manager.register-internally";
    static final String CAP_SCOPED_FIRE = "event-manager.scoped-fire";
    static final String CAP_CONTAINER_EXECUTOR = "container.executor";
    static final String CAP_COMMAND_UNREGISTER = "command-manager.unregister";

    static final List<String> CAPABILITY_KEYS = List.of(CAP_PLUGIN_MAPS, CAP_LOADER_CANDIDATE, CAP_LOADER_CREATE,
            CAP_LOADER_MODULE, CAP_CLASSLOADER_CLOSE, CAP_CLASSLOADER_REGISTRY, CAP_REGISTER_INTERNALLY,
            CAP_SCOPED_FIRE, CAP_CONTAINER_EXECUTOR, CAP_COMMAND_UNREGISTER);

    private static final Path DEFAULT_PLUGINS_DIRECTORY = Path.of("plugins");
    private static final long EXECUTOR_DRAIN_SECONDS = 5L;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Handles handles;
    private final ProxyCapabilityReport report;
    private final AtomicBoolean fallbackFireAnnounced = new AtomicBoolean();
    private volatile Path pluginsDirectory;

    private ProxyInternals(ProxyServer proxy, Logger logger, Handles handles, ProxyCapabilityReport report) {
        this.proxy = proxy;
        this.logger = logger;
        this.handles = handles;
        this.report = report;
    }

    public static ProxyInternals resolve(ProxyServer proxy, Logger logger) {
        Objects.requireNonNull(proxy, "proxy");
        Objects.requireNonNull(logger, "logger");
        Resolution resolution = new Resolution(CAPABILITY_KEYS);
        Handles handles = new Handles();
        handles.pluginManager = probe(proxy::getPluginManager);
        handles.eventManager = probe(proxy::getEventManager);
        ClassLoader proxyLoader = handles.pluginManager == null
                ? ProxyInternals.class.getClassLoader()
                : handles.pluginManager.getClass().getClassLoader();
        resolvePluginManager(resolution, handles);
        resolvePluginLoader(resolution, handles, proxyLoader);
        resolveClassLoaderRegistry(resolution, handles, proxyLoader);
        resolveEventManager(resolution, handles, proxyLoader);
        handles.providedIds = quietMethod(PluginDescription.class, "getProvidedIds");
        handles.mainClass = quietDeclaredMethod(proxyLoader,
                "com.velocitypowered.proxy.plugin.loader.java.JavaVelocityPluginDescription", "getMainClass");
        return new ProxyInternals(proxy, logger, handles, resolution.report());
    }

    public ProxyCapabilityReport report() {
        return report;
    }

    @SuppressWarnings("unchecked")
    public Map<String, PluginContainer> pluginsById() {
        Object value = readField(handles.pluginsById);
        return value instanceof Map ? (Map<String, PluginContainer>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    public Map<Object, PluginContainer> pluginInstances() {
        Object value = readField(handles.pluginInstances);
        return value instanceof Map ? (Map<Object, PluginContainer>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    public Optional<Collection<PluginContainer>> pluginList() {
        Object value = readField(handles.plugins);
        return value instanceof Collection ? Optional.of((Collection<PluginContainer>) value) : Optional.empty();
    }

    public PluginContainer createContainer(Path jar) throws HotloadException {
        Objects.requireNonNull(jar, "jar");
        require(CAP_LOADER_CANDIDATE);
        require(CAP_LOADER_CREATE);
        Object loader = newPluginLoader();
        Object candidate;
        try {
            candidate = handles.loadCandidate.invoke(loader, jar);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.INVALID_DESCRIPTOR,
                    "velocity rejected " + jar.getFileName() + ": " + message(e.getCause()), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "loadCandidate failed for " + jar, e);
        }
        try {
            Object description = handles.createPluginFromCandidate.invoke(loader, candidate);
            return (PluginContainer) handles.containerConstructor.newInstance(description);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "createPluginFromCandidate failed for " + jar.getFileName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "cannot build a container for " + jar, e);
        }
    }

    public Object instantiate(PluginContainer container) throws HotloadException {
        Objects.requireNonNull(container, "container");
        require(CAP_LOADER_MODULE);
        Object loader = newPluginLoader();
        try {
            Module pluginModule = (Module) handles.createModule.invoke(loader, container);
            Module[] modules = {pluginModule, commonModule(container)};
            handles.createPlugin.invoke(loader, container, modules);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "guice could not instantiate " + id(container), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot instantiate " + id(container), e);
        }
        return container.getInstance().orElseThrow(() -> new HotloadException(HotloadException.Kind.LOAD_FAILED,
                "velocity produced no instance for " + id(container)));
    }

    public void registerContainer(PluginContainer container) throws HotloadException {
        Objects.requireNonNull(container, "container");
        require(CAP_PLUGIN_MAPS);
        try {
            handles.registerPlugin.invoke(handles.pluginManager, container);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "registerPlugin failed for " + id(container), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot register " + id(container), e);
        }
    }

    public void unregisterContainer(PluginContainer container) throws HotloadException {
        Objects.requireNonNull(container, "container");
        require(CAP_PLUGIN_MAPS);
        Object instance = container.getInstance().orElse(null);
        try {
            Collection<?> plugins = (Collection<?>) handles.plugins.get(handles.pluginManager);
            plugins.remove(container);
            Map<?, ?> byId = (Map<?, ?>) handles.pluginsById.get(handles.pluginManager);
            byId.values().removeIf(value -> value == container);
            Map<?, ?> instances = (Map<?, ?>) handles.pluginInstances.get(handles.pluginManager);
            instances.values().removeIf(value -> value == container);
            if (instance != null) {
                instances.remove(instance);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                    "cannot unregister " + id(container), e);
        }
    }

    public void registerListenersInternally(PluginContainer container, Object instance) throws HotloadException {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(instance, "instance");
        require(CAP_REGISTER_INTERNALLY);
        try {
            handles.registerInternally.invoke(handles.eventManager, container, instance);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "registerInternally failed for " + id(container), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot register listeners for " + id(container), e);
        }
    }

    public void fireScoped(PluginContainer target, Object event, Duration timeout) throws HotloadException {
        List<HandlerFailure> failures = fireScopedCollecting(target, event, timeout);
        if (failures.isEmpty()) {
            return;
        }
        HandlerFailure first = failures.get(0);
        throw new HotloadException(HotloadException.Kind.LOAD_FAILED, event.getClass().getSimpleName() + " handler "
                + first.handler() + " for " + first.pluginId() + " failed: " + message(first.error())
                + additionalFailures(failures), first.error());
    }

    List<HandlerFailure> fireScopedCollecting(PluginContainer target, Object event, Duration timeout)
            throws HotloadException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(timeout, "timeout");
        if (!report.supports(CAP_SCOPED_FIRE)) {
            return fireAnnotatedFallback(target, event);
        }
        List<ScopedHandler> handlers = scopedHandlers(target, event);
        if (handlers.isEmpty()) {
            return List.of();
        }
        return dispatchHandlers(target, event, handlers, timeout);
    }

    List<HandlerFailure> dispatchHandlers(PluginContainer target, Object event, List<ScopedHandler> handlers,
                                          Duration timeout) throws HotloadException {
        ScopedFire fire = new ScopedFire(target, event, timeout, System.nanoTime() + Math.max(1L, timeout.toNanos()));
        List<HandlerFailure> failures = new ArrayList<>();
        for (ScopedHandler scoped : handlers) {
            Throwable error = invokeHandler(fire, scoped);
            if (error == null) {
                continue;
            }
            logger.error("{} handler {} for {} failed", event.getClass().getSimpleName(), scoped.name(), id(target),
                    error);
            failures.add(new HandlerFailure(id(target), scoped.name(), error));
        }
        return List.copyOf(failures);
    }

    public void closeClassLoader(PluginContainer container) throws HotloadException {
        Objects.requireNonNull(container, "container");
        ClassLoader loader = classLoaderOf(container).orElse(null);
        if (loader == null) {
            throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                    "no class loader to close for " + id(container));
        }
        if (!(loader instanceof Closeable closeable)) {
            throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                    "class loader for " + id(container) + " is not closeable: " + loader.getClass().getName());
        }
        try {
            closeable.close();
        } catch (IOException e) {
            throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                    "closing the class loader for " + id(container) + " failed", e);
        }
        warnIfStillRegistered(loader, container);
    }

    public void shutdownContainerExecutor(PluginContainer container) {
        Objects.requireNonNull(container, "container");
        if (handles.hasExecutorService == null || !handles.containerType.isInstance(container)) {
            return;
        }
        try {
            if (!(boolean) handles.hasExecutorService.invoke(container)) {
                return;
            }
            ExecutorService service = container.getExecutorService();
            service.shutdownNow();
            if (!service.awaitTermination(EXECUTOR_DRAIN_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("executor for {} did not drain within {}s", id(container), EXECUTOR_DRAIN_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("interrupted while draining the executor for {}", id(container), e);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warn("cannot shut down the executor for {}", id(container), e);
        }
    }

    public Optional<ClassLoader> classLoaderOf(PluginContainer container) {
        Objects.requireNonNull(container, "container");
        Object instance = container.getInstance().orElse(null);
        if (instance != null) {
            return Optional.ofNullable(instance.getClass().getClassLoader());
        }
        return mainClassLoader(container.getDescription());
    }

    List<String> providedIds(PluginDescription description) {
        if (description == null || handles.providedIds == null) {
            return List.of();
        }
        try {
            Object value = handles.providedIds.invoke(description);
            if (!(value instanceof Collection<?> collection)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>(collection.size());
            for (Object id : collection) {
                ids.add(String.valueOf(id));
            }
            return ids;
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("cannot read the provided ids of {}", description.getId(), e);
            return List.of();
        }
    }

    void pluginsDirectory(Path directory) {
        if (directory != null) {
            this.pluginsDirectory = directory;
        }
    }

    private static void resolvePluginManager(Resolution resolution, Handles handles) {
        if (handles.pluginManager == null) {
            resolution.fail(CAP_PLUGIN_MAPS, "com.velocitypowered.api.proxy.ProxyServer#getPluginManager");
            return;
        }
        Class<?> type = handles.pluginManager.getClass();
        handles.plugins = resolution.field(CAP_PLUGIN_MAPS, type, List.of("plugins"));
        handles.pluginsById = resolution.field(CAP_PLUGIN_MAPS, type, List.of("pluginsById"));
        handles.pluginInstances = resolution.field(CAP_PLUGIN_MAPS, type, List.of("pluginInstances"));
        handles.registerPlugin = resolution.method(CAP_PLUGIN_MAPS, type, List.of("registerPlugin"), PluginContainer.class);
    }

    private static void resolvePluginLoader(Resolution resolution, Handles handles, ClassLoader proxyLoader) {
        Class<?> loaderType = resolution.type(CAP_LOADER_CANDIDATE, proxyLoader,
                List.of("com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader"));
        if (loaderType == null) {
            resolution.fail(CAP_LOADER_CREATE, "com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader");
            resolution.fail(CAP_LOADER_MODULE, "com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader");
            resolveContainerType(resolution, handles, proxyLoader);
            return;
        }
        handles.pluginLoaderConstructor = resolution.constructor(CAP_LOADER_CANDIDATE, loaderType,
                ProxyServer.class, Path.class);
        handles.loadCandidate = resolution.method(CAP_LOADER_CANDIDATE, loaderType, List.of("loadCandidate"), Path.class);
        handles.createPluginFromCandidate = resolution.method(CAP_LOADER_CREATE, loaderType,
                List.of("createPluginFromCandidate"), PluginDescription.class);
        handles.createModule = resolution.method(CAP_LOADER_MODULE, loaderType, List.of("createModule"), PluginContainer.class);
        handles.createPlugin = resolution.method(CAP_LOADER_MODULE, loaderType, List.of("createPlugin"),
                PluginContainer.class, Module[].class);
        resolveContainerType(resolution, handles, proxyLoader);
    }

    private static void resolveContainerType(Resolution resolution, Handles handles, ClassLoader proxyLoader) {
        handles.containerType = resolution.type(CAP_LOADER_CREATE, proxyLoader,
                List.of("com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer"));
        if (handles.containerType == null) {
            resolution.fail(CAP_CONTAINER_EXECUTOR, "com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer");
            return;
        }
        handles.containerConstructor = resolution.constructor(CAP_LOADER_CREATE, handles.containerType, PluginDescription.class);
        handles.hasExecutorService = resolution.method(CAP_CONTAINER_EXECUTOR, handles.containerType,
                List.of("hasExecutorService"));
    }

    private static void resolveClassLoaderRegistry(Resolution resolution, Handles handles, ClassLoader proxyLoader) {
        Class<?> type = resolution.type(CAP_CLASSLOADER_REGISTRY, proxyLoader,
                List.of("com.velocitypowered.proxy.plugin.PluginClassLoader"));
        if (type == null) {
            return;
        }
        handles.classLoaderRegistry = resolution.field(CAP_CLASSLOADER_REGISTRY, type, List.of("loaders"));
    }

    private static void resolveEventManager(Resolution resolution, Handles handles, ClassLoader proxyLoader) {
        if (handles.eventManager == null) {
            resolution.fail(CAP_REGISTER_INTERNALLY, "com.velocitypowered.api.proxy.ProxyServer#getEventManager");
            resolution.fail(CAP_SCOPED_FIRE, "com.velocitypowered.api.proxy.ProxyServer#getEventManager");
            return;
        }
        Class<?> type = handles.eventManager.getClass();
        handles.registerInternally = resolution.method(CAP_REGISTER_INTERNALLY, type, List.of("registerInternally"),
                PluginContainer.class, Object.class);
        handles.bakeHandlers = resolution.method(CAP_SCOPED_FIRE, type, List.of("bakeHandlers"), Class.class);
        Class<?> cacheType = resolution.type(CAP_SCOPED_FIRE, proxyLoader,
                List.of("com.velocitypowered.proxy.event.VelocityEventManager$HandlersCache"));
        Class<?> registrationType = resolution.type(CAP_SCOPED_FIRE, proxyLoader,
                List.of("com.velocitypowered.proxy.event.VelocityEventManager$HandlerRegistration"));
        if (cacheType == null || registrationType == null) {
            return;
        }
        handles.cacheHandlers = resolution.field(CAP_SCOPED_FIRE, cacheType, List.of("handlers"));
        handles.registrationPlugin = resolution.field(CAP_SCOPED_FIRE, registrationType, List.of("plugin"));
        handles.registrationHandler = resolution.field(CAP_SCOPED_FIRE, registrationType, List.of("handler"));
        handles.registrationInstance = quietField(registrationType, "instance");
    }

    private static Field quietField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method quietDeclaredMethod(ClassLoader loader, String className, String name) {
        try {
            Class<?> owner = Class.forName(className, false, loader);
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static Method quietMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static <T> T probe(Probe<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String message(Throwable cause) {
        if (cause == null) {
            return "unknown error";
        }
        return cause.getMessage() == null ? cause.getClass().getName() : cause.getMessage();
    }

    private static String id(PluginContainer container) {
        try {
            return container.getDescription().getId();
        } catch (RuntimeException e) {
            return "<unknown>";
        }
    }

    private void require(String capability) throws HotloadException {
        if (!report.supports(capability)) {
            throw new HotloadException(HotloadException.Kind.UNSUPPORTED_CAPABILITY,
                    "proxy internals are unavailable: " + capability);
        }
    }

    private Object newPluginLoader() throws HotloadException {
        try {
            return handles.pluginLoaderConstructor.newInstance(proxy, effectivePluginsDirectory());
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "cannot build a JavaPluginLoader", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "cannot build a JavaPluginLoader", e);
        }
    }

    private Path effectivePluginsDirectory() {
        Path configured = pluginsDirectory;
        if (configured != null) {
            return configured;
        }
        Path derived = derivePluginsDirectory();
        pluginsDirectory = derived;
        return derived;
    }

    private Path derivePluginsDirectory() {
        if (handles.pluginManager == null) {
            return DEFAULT_PLUGINS_DIRECTORY;
        }
        try {
            for (PluginContainer container : handles.pluginManager.getPlugins()) {
                Path source = container.getDescription().getSource().orElse(null);
                if (source != null && source.getParent() != null) {
                    return source.getParent();
                }
            }
        } catch (RuntimeException e) {
            logger.debug("cannot derive the plugins directory from loaded plugins", e);
        }
        return DEFAULT_PLUGINS_DIRECTORY;
    }

    private Module commonModule(PluginContainer container) {
        Set<PluginContainer> containers = new LinkedHashSet<>();
        try {
            containers.addAll(handles.pluginManager.getPlugins());
        } catch (RuntimeException e) {
            logger.debug("cannot enumerate loaded plugins for the injector module", e);
        }
        containers.add(container);
        return new CommonModule(proxy, List.copyOf(containers));
    }

    @SuppressWarnings("unchecked")
    private List<ScopedHandler> scopedHandlers(PluginContainer target, Object event) throws HotloadException {
        try {
            Object cache = handles.bakeHandlers.invoke(handles.eventManager, event.getClass());
            if (cache == null) {
                return List.of();
            }
            Object[] all = (Object[]) handles.cacheHandlers.get(cache);
            if (all == null) {
                return List.of();
            }
            List<ScopedHandler> scoped = new ArrayList<>(all.length);
            for (Object registration : all) {
                if (handles.registrationPlugin.get(registration) != target) {
                    continue;
                }
                Object handler = handles.registrationHandler.get(registration);
                if (handler instanceof EventHandler<?> typed) {
                    scoped.add(new ScopedHandler((EventHandler<Object>) typed, handlerName(registration, handler)));
                }
            }
            return List.copyOf(scoped);
        } catch (InvocationTargetException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot collect handlers for " + event.getClass().getSimpleName(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot collect handlers for " + event.getClass().getSimpleName(), e);
        }
    }

    private String handlerName(Object registration, Object handler) {
        if (handles.registrationInstance != null) {
            try {
                Object listener = handles.registrationInstance.get(registration);
                if (listener != null) {
                    return listener.getClass().getName();
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                logger.debug("cannot read the listener behind a handler registration", e);
            }
        }
        return handler.getClass().getName();
    }

    private Throwable invokeHandler(ScopedFire fire, ScopedHandler scoped) throws HotloadException {
        EventTask task;
        try {
            task = scoped.handler().executeAsync(fire.event());
        } catch (Throwable t) {
            return t;
        }
        if (task == null) {
            return null;
        }
        CompletableFuture<Object> completion = new CompletableFuture<>();
        try {
            task.execute(new FutureContinuation(completion));
        } catch (Throwable t) {
            return t;
        }
        return awaitContinuation(fire, completion);
    }

    private Throwable awaitContinuation(ScopedFire fire, CompletableFuture<Object> completion) throws HotloadException {
        try {
            completion.get(Math.max(1L, remainingMillis(fire.deadlineNanos())), TimeUnit.MILLISECONDS);
            return null;
        } catch (TimeoutException e) {
            throw new HotloadException(HotloadException.Kind.TIMEOUT, fire.event().getClass().getSimpleName()
                    + " for " + id(fire.target()) + " did not finish within " + fire.timeout(), e);
        } catch (ExecutionException e) {
            return e.getCause() == null ? e : e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HotloadException(HotloadException.Kind.TIMEOUT, "interrupted while firing "
                    + fire.event().getClass().getSimpleName() + " for " + id(fire.target()), e);
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    private static String additionalFailures(List<HandlerFailure> failures) {
        return failures.size() == 1 ? "" : " (and " + (failures.size() - 1) + " more handler failures)";
    }

    private List<HandlerFailure> fireAnnotatedFallback(PluginContainer target, Object event) throws HotloadException {
        Object instance = target.getInstance().orElse(null);
        if (instance == null) {
            return List.of();
        }
        if (fallbackFireAnnounced.compareAndSet(false, true)) {
            logger.warn("scoped event fire unavailable; using @Subscribe reflection, handlers registered without "
                    + "annotations will not see lifecycle events");
        }
        List<HandlerFailure> failures = new ArrayList<>();
        for (Method method : annotatedSubscribers(instance.getClass(), event.getClass())) {
            try {
                method.invoke(instance, event);
            } catch (InvocationTargetException e) {
                Throwable error = e.getCause() == null ? e : e.getCause();
                logger.error("{} handler {} for {} failed", event.getClass().getSimpleName(), method.getName(),
                        id(target), error);
                failures.add(new HandlerFailure(id(target), method.getName(), error));
            } catch (ReflectiveOperationException e) {
                throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                        "cannot invoke " + method.getName() + " on " + id(target), e);
            }
        }
        return List.copyOf(failures);
    }

    private List<Method> annotatedSubscribers(Class<?> listenerType, Class<?> eventType) {
        List<Method> methods = new ArrayList<>();
        for (Class<?> type = listenerType; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Subscribe.class) || method.getParameterCount() != 1) {
                    continue;
                }
                if (!method.getParameterTypes()[0].isAssignableFrom(eventType)) {
                    continue;
                }
                method.setAccessible(true);
                methods.add(method);
            }
        }
        return methods;
    }

    private Optional<ClassLoader> mainClassLoader(PluginDescription description) {
        if (handles.mainClass == null || !handles.mainClass.getDeclaringClass().isInstance(description)) {
            return Optional.empty();
        }
        try {
            Object value = handles.mainClass.invoke(description);
            return value instanceof Class<?> type ? Optional.ofNullable(type.getClassLoader()) : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("cannot read the main class of {}", description.getId(), e);
            return Optional.empty();
        }
    }

    private void warnIfStillRegistered(ClassLoader loader, PluginContainer container) {
        if (handles.classLoaderRegistry == null) {
            return;
        }
        try {
            Object registry = handles.classLoaderRegistry.get(null);
            if (registry instanceof Collection<?> loaders && loaders.contains(loader)) {
                logger.warn("class loader for {} is still registered after close", id(container));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.debug("cannot verify class loader deregistration for {}", id(container), e);
        }
    }

    private Object readField(Field field) {
        if (field == null || handles.pluginManager == null) {
            return null;
        }
        try {
            return field.get(handles.pluginManager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface Probe<T> {
        T get();
    }

    record ScopedHandler(EventHandler<Object> handler, String name) {
        ScopedHandler {
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(name, "name");
        }
    }

    record HandlerFailure(String pluginId, String handler, Throwable error) {
        HandlerFailure {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(handler, "handler");
            Objects.requireNonNull(error, "error");
        }
    }

    private record ScopedFire(PluginContainer target, Object event, Duration timeout, long deadlineNanos) {
    }

    private static final class FutureContinuation implements Continuation {
        private final CompletableFuture<Object> completion;

        private FutureContinuation(CompletableFuture<Object> completion) {
            this.completion = completion;
        }

        @Override
        public void resume() {
            completion.complete(null);
        }

        @Override
        public void resumeWithException(Throwable throwable) {
            completion.completeExceptionally(throwable == null
                    ? new IllegalStateException("a handler resumed with no exception") : throwable);
        }
    }

    private static final class Handles {
        private PluginManager pluginManager;
        private EventManager eventManager;
        private Field plugins;
        private Field pluginsById;
        private Field pluginInstances;
        private Method registerPlugin;
        private Constructor<?> pluginLoaderConstructor;
        private Method loadCandidate;
        private Method createPluginFromCandidate;
        private Method createModule;
        private Method createPlugin;
        private Class<?> containerType;
        private Constructor<?> containerConstructor;
        private Method hasExecutorService;
        private Field classLoaderRegistry;
        private Method registerInternally;
        private Method bakeHandlers;
        private Field cacheHandlers;
        private Field registrationPlugin;
        private Field registrationHandler;
        private Field registrationInstance;
        private Method providedIds;
        private Method mainClass;
    }

    private static final class CommonModule extends AbstractModule {
        private final ProxyServer proxy;
        private final List<PluginContainer> containers;

        private CommonModule(ProxyServer proxy, List<PluginContainer> containers) {
            this.proxy = proxy;
            this.containers = containers;
        }

        @Override
        protected void configure() {
            bind(ProxyServer.class).toInstance(proxy);
            bind(PluginManager.class).toInstance(proxy.getPluginManager());
            bind(EventManager.class).toInstance(proxy.getEventManager());
            bind(CommandManager.class).toInstance(proxy.getCommandManager());
            for (PluginContainer container : containers) {
                bind(PluginContainer.class)
                        .annotatedWith(Names.named(container.getDescription().getId()))
                        .toInstance(container);
            }
        }
    }

    static final class Resolution {
        private final Map<String, Boolean> capabilities = new LinkedHashMap<>();
        private final List<String> notes = new ArrayList<>();

        Resolution(List<String> capabilityKeys) {
            for (String key : Objects.requireNonNull(capabilityKeys, "capabilityKeys")) {
                capabilities.put(key, Boolean.TRUE);
            }
        }

        Field field(String capability, Class<?> owner, List<String> candidates) {
            for (String candidate : candidates) {
                for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                    try {
                        Field field = type.getDeclaredField(candidate);
                        field.setAccessible(true);
                        return field;
                    } catch (NoSuchFieldException | RuntimeException e) {
                        continue;
                    }
                }
            }
            fail(capability, member(owner, candidates));
            return null;
        }

        Method method(String capability, Class<?> owner, List<String> candidates, Class<?>... parameterTypes) {
            for (String candidate : candidates) {
                for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
                    try {
                        Method method = type.getDeclaredMethod(candidate, parameterTypes);
                        method.setAccessible(true);
                        return method;
                    } catch (NoSuchMethodException | RuntimeException e) {
                        continue;
                    }
                }
            }
            fail(capability, member(owner, candidates));
            return null;
        }

        Constructor<?> constructor(String capability, Class<?> owner, Class<?>... parameterTypes) {
            try {
                Constructor<?> constructor = owner.getDeclaredConstructor(parameterTypes);
                constructor.setAccessible(true);
                return constructor;
            } catch (NoSuchMethodException | RuntimeException e) {
                fail(capability, owner.getName() + "#<init>" + signature(parameterTypes));
                return null;
            }
        }

        Class<?> type(String capability, ClassLoader loader, List<String> candidates) {
            for (String candidate : candidates) {
                try {
                    return Class.forName(candidate, false, loader);
                } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
                    continue;
                }
            }
            fail(capability, "class " + String.join(", ", candidates));
            return null;
        }

        void fail(String capability, String member) {
            capabilities.put(capability, Boolean.FALSE);
            notes.add("unresolved " + member + " (capability " + capability + ")");
        }

        boolean supports(String capability) {
            return capabilities.getOrDefault(capability, Boolean.FALSE);
        }

        ProxyCapabilityReport report() {
            return new ProxyCapabilityReport(capabilities, notes);
        }

        private static String member(Class<?> owner, List<String> candidates) {
            return owner.getName() + "#" + candidates.get(0) + " (tried: " + String.join(", ", candidates) + ")";
        }

        private static String signature(Class<?>[] parameterTypes) {
            List<String> names = new ArrayList<>(parameterTypes.length);
            for (Class<?> parameterType : parameterTypes) {
                names.add(parameterType.getSimpleName());
            }
            return "(" + String.join(", ", names) + ")";
        }
    }
}
