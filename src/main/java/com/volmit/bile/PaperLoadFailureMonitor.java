package com.volmit.bile;

import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PaperLoadFailureMonitor implements AutoCloseable {
    private final Backend backend;
    private final Object appender;
    private final String name = "BileLoadMonitor-" + UUID.randomUUID();
    private final Thread owner = Thread.currentThread();
    private final List<Failure> failures = new ArrayList<>();
    private Throwable observationFailure;
    private boolean closed;

    private PaperLoadFailureMonitor(Backend backend) throws ReflectiveOperationException {
        this.backend = backend;
        appender = Proxy.newProxyInstance(backend.appenderType().getClassLoader(),
                new Class<?>[]{backend.appenderType()}, this::invokeAppender);
        backend.add().invoke(backend.logger(), appender);
    }

    static void validate() throws InvalidPluginException {
        try {
            resolve();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new InvalidPluginException("Paper load failure observation is unavailable", failure);
        }
    }

    static PaperLoadFailureMonitor observe() throws InvalidPluginException {
        try {
            return new PaperLoadFailureMonitor(resolve());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new InvalidPluginException("Cannot observe Paper plugin loading", failure);
        }
    }

    static PaperLoadFailureMonitor observe(Object logger, BackendTypes types, Map<?, ?> appenders) throws ReflectiveOperationException {
        return new PaperLoadFailureMonitor(inspect(logger, types, appenders));
    }

    void throwIfFailed(Plugin plugin) throws InvalidPluginException {
        if (plugin != null) {
            throwIfFailed(plugin.getLogger().getName());
        } else if (observationFailure != null) {
            throw new InvalidPluginException("Paper load failure observation failed", observationFailure);
        }
    }

    void throwIfFailed(String loggerName) throws InvalidPluginException {
        InvalidPluginException failure = observationFailure == null ? null
                : new InvalidPluginException("Paper load failure observation failed", observationFailure);
        for (Failure captured : failures) {
            if (!captured.logger().equals(loggerName)) {
                continue;
            }
            if (failure == null) {
                failure = new InvalidPluginException("onLoad failed for " + loggerName, captured.cause());
            } else if (failure.getCause() != captured.cause()) {
                failure.addSuppressed(captured.cause());
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            backend.remove().invoke(backend.logger(), appender);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot detach Paper load failure observer", failure);
        } finally {
            backend.appenders().remove(name, appender);
            failures.clear();
            observationFailure = null;
        }
    }

    private Object invokeAppender(Object proxy, Method method, Object[] arguments) throws ReflectiveOperationException {
        return switch (method.getName()) {
            case "append" -> {
                capture(arguments[0]);
                yield null;
            }
            case "getName", "toString" -> name;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "isStarted", "ignoreExceptions" -> true;
            case "isStopped" -> closed;
            case "getState" -> method.getReturnType().getField(closed ? "STOPPED" : "STARTED").get(null);
            case "initialize", "start", "stop", "setHandler", "getHandler", "getLayout" -> null;
            default -> throw new UnsupportedOperationException(method.getName());
        };
    }

    private void capture(Object event) {
        if (closed || Thread.currentThread() != owner) {
            return;
        }
        try {
            Object thrown = backend.thrown().invoke(event);
            if (!(thrown instanceof Throwable cause)) {
                return;
            }
            Object message = backend.message().invoke(event);
            String text = (String) backend.formatted().invoke(message);
            if (text != null && text.startsWith("Error initializing plugin ")
                    && text.endsWith(" (Is it up to date?)")) {
                failures.add(new Failure((String) backend.loggerName().invoke(event), cause));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            observationFailure = failure;
        }
    }

    private static Backend resolve() throws ReflectiveOperationException {
        ClassLoader loader = Plugin.class.getClassLoader();
        Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager", false, loader);
        Object logger = manager.getMethod("getRootLogger").invoke(null);
        BackendTypes types = new BackendTypes(
                Class.forName("org.apache.logging.log4j.core.Logger", false, loader),
                Class.forName("org.apache.logging.log4j.core.Appender", false, loader),
                Class.forName("org.apache.logging.log4j.core.LogEvent", false, loader),
                Class.forName("org.apache.logging.log4j.message.Message", false, loader));
        Object context = types.logger().getMethod("getContext").invoke(logger);
        Class<?> contextType = Class.forName("org.apache.logging.log4j.core.LoggerContext", false, loader);
        Object configuration = contextType.getMethod("getConfiguration").invoke(context);
        Class<?> configurationType = Class.forName("org.apache.logging.log4j.core.config.Configuration", false, loader);
        Object registry = configurationType.getMethod("getAppenders").invoke(configuration);
        if (!(registry instanceof Map<?, ?> appenders)) {
            throw new IllegalStateException("Paper logging appender registry is unavailable");
        }
        return inspect(logger, types, appenders);
    }

    private static Backend inspect(Object logger, BackendTypes types, Map<?, ?> appenders) throws ReflectiveOperationException {
        if (!types.logger().isInstance(logger) || !types.appender().isInterface()) {
            throw new IllegalStateException("Paper logging backend does not support scoped appenders");
        }
        return new Backend(logger, appenders, types.appender(), types.logger().getMethod("addAppender", types.appender()),
                types.logger().getMethod("removeAppender", types.appender()),
                types.event().getMethod("getThrown"), types.event().getMethod("getLoggerName"),
                types.event().getMethod("getMessage"), types.message().getMethod("getFormattedMessage"));
    }

    record BackendTypes(Class<?> logger, Class<?> appender, Class<?> event, Class<?> message) {
    }

    private record Backend(Object logger, Map<?, ?> appenders, Class<?> appenderType, Method add, Method remove,
                           Method thrown, Method loggerName, Method message, Method formatted) {
    }

    private record Failure(String logger, Throwable cause) {
    }
}
