package com.volmit.bile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class LifecycleFailureMonitor extends Handler implements AutoCloseable {
    private static final String UPDATE_SUFFIX = " (Is it up to date?)";
    private static final List<String> DISABLE_OPERATIONS = List.of("disabling", "cancelling tasks for",
            "cancelling global tasks for", "cancelling async tasks for", "unregistering services for",
            "unregistering events for", "unregistering lifecycle event handlers for",
            "unregistering plugin channels for", "removing chunk tickets for");

    private final Options options;
    private final Thread owner;
    private final Formatter formatter = new SimpleFormatter();
    private final List<Logger> attached = new ArrayList<>(2);
    private final List<Throwable> failures = new ArrayList<>();
    private final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;

    private LifecycleFailureMonitor(Options options) {
        this.options = Objects.requireNonNull(options, "options");
        this.owner = Thread.currentThread();
        setLevel(Level.ALL);
        Set<Logger> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            for (Logger logger : options.loggers()) {
                if (unique.add(logger)) {
                    logger.addHandler(this);
                    attached.add(logger);
                }
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public static LifecycleFailureMonitor observe(Options options) {
        return new LifecycleFailureMonitor(options);
    }

    public void run(Runnable action) {
        try {
            Objects.requireNonNull(action, "action").run();
        } catch (RuntimeException | Error failure) {
            record(failure);
        }
        throwIfFailed();
    }

    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    public void throwIfFailed() {
        if (failures.isEmpty()) {
            return;
        }
        IllegalStateException failure = new IllegalStateException(options.phase().name().toLowerCase(Locale.ROOT)
                + " failed for " + options.displayName(), failures.get(0));
        for (int index = 1; index < failures.size(); index++) {
            failure.addSuppressed(failures.get(index));
        }
        throw failure;
    }

    @Override
    public void publish(LogRecord record) {
        if (closed || Thread.currentThread() != owner || record == null || record.getThrown() == null
                || record.getLevel().intValue() < Level.WARNING.intValue()) {
            return;
        }
        if (matches(formatter.formatMessage(record))) {
            record(record.getThrown());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        closed = true;
        for (Logger logger : attached) {
            logger.removeHandler(this);
        }
        attached.clear();
    }

    private boolean matches(String message) {
        if (message == null) {
            return false;
        }
        if (options.phase() == Phase.DISABLE
                && message.equals("Error closing the classloader for '" + options.displayName() + "'")) {
            return true;
        }
        String normalized = message.endsWith(UPDATE_SUFFIX)
                ? message.substring(0, message.length() - UPDATE_SUFFIX.length()) : message;
        List<String> operations = options.phase() == Phase.ENABLE ? List.of("enabling") : DISABLE_OPERATIONS;
        for (String operation : operations) {
            String target = operation + " " + options.displayName();
            if (normalized.equals("Error occurred while " + target)
                    || normalized.equals("Error occurred (in the plugin loader) while " + target)) {
                return true;
            }
        }
        return false;
    }

    private void record(Throwable failure) {
        if (seen.add(failure)) {
            failures.add(failure);
        }
    }

    public enum Phase {
        ENABLE,
        DISABLE
    }

    public record Options(String displayName, Phase phase, List<Logger> loggers) {
        public Options {
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(phase, "phase");
            loggers = List.copyOf(Objects.requireNonNull(loggers, "loggers"));
        }
    }
}
