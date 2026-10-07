package com.volmit.bile;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LifecycleFailureMonitorTest {
    @Test
    public void capturesSwallowedSpigotEnableFailureWithoutSuppressingLogging() {
        Logger logger = logger();
        AtomicInteger published = new AtomicInteger();
        logger.addHandler(new CountingHandler(published));
        RuntimeException cause = new RuntimeException("enable broke");
        try (LifecycleFailureMonitor monitor = observe(logger, LifecycleFailureMonitor.Phase.ENABLE)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> monitor.run(() ->
                    logger.log(Level.SEVERE, "Error occurred while enabling Probe v1 (Is it up to date?)", cause)));

            assertSame(cause, failure.getCause());
            assertEquals(1, published.get());
        }
        assertEquals(1, logger.getHandlers().length);
    }

    @Test
    public void capturesPaperLoaderAndDisableCleanupFailures() {
        Logger logger = logger();
        RuntimeException first = new RuntimeException("disable broke");
        RuntimeException second = new RuntimeException("tasks remained");
        try (LifecycleFailureMonitor monitor = observe(logger, LifecycleFailureMonitor.Phase.DISABLE)) {
            logger.log(Level.SEVERE, "Error occurred while disabling Probe v1", first);
            logger.log(Level.SEVERE, "Error occurred (in the plugin loader) while cancelling async tasks for Probe v1 (Is it up to date?)", second);

            IllegalStateException failure = assertThrows(IllegalStateException.class, monitor::throwIfFailed);
            assertSame(first, failure.getCause());
            assertSame(second, failure.getSuppressed()[0]);
        }
    }

    @Test
    public void ignoresOtherThreadsPluginsPhasesAndOrdinaryPluginErrors() throws Exception {
        Logger logger = logger();
        RuntimeException cause = new RuntimeException("unrelated");
        try (LifecycleFailureMonitor monitor = observe(logger, LifecycleFailureMonitor.Phase.ENABLE)) {
            logger.log(Level.SEVERE, "Error occurred while enabling Probe v12 (Is it up to date?)", cause);
            logger.log(Level.SEVERE, "Error occurred while enabling Other v1 (Is it up to date?)", cause);
            logger.log(Level.SEVERE, "Error occurred while disabling Probe v1", cause);
            logger.log(Level.SEVERE, "Plugin initialization warning", cause);
            logger.severe("Error occurred while enabling Probe v1 (Is it up to date?)");
            Thread worker = new Thread(() -> logger.log(Level.SEVERE,
                    "Error occurred while enabling Probe v1 (Is it up to date?)", cause));
            worker.start();
            worker.join();

            assertTrue(monitor.failures().isEmpty());
        }
    }

    @Test
    public void deduplicatesPropagatedLogRecordsAndPreservesThrownCauses() {
        Logger parent = logger();
        Logger child = logger();
        child.setParent(parent);
        child.setUseParentHandlers(true);
        RuntimeException cause = new RuntimeException("loader failure");
        try (LifecycleFailureMonitor monitor = LifecycleFailureMonitor.observe(new LifecycleFailureMonitor.Options(
                "Probe v1", LifecycleFailureMonitor.Phase.ENABLE, List.of(parent, child, parent)))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> monitor.run(() -> {
                child.log(Level.SEVERE, "Error occurred while enabling Probe v1 (Is it up to date?)", cause);
                throw cause;
            }));

            assertSame(cause, failure.getCause());
            assertEquals(1, monitor.failures().size());
            assertEquals(0, failure.getSuppressed().length);
        }
        assertEquals(0, parent.getHandlers().length);
        assertEquals(0, child.getHandlers().length);
    }

    @Test
    public void observesPaperClassloaderCloseFailures() {
        Logger logger = logger();
        RuntimeException cause = new RuntimeException("classloader close failed");
        LogRecord record = new LogRecord(Level.WARNING, "Error closing the classloader for 'Probe v1'");
        record.setThrown(cause);
        try (LifecycleFailureMonitor monitor = observe(logger, LifecycleFailureMonitor.Phase.DISABLE)) {
            logger.log(record);
            assertEquals(List.of(cause), monitor.failures());
        }
    }

    private static LifecycleFailureMonitor observe(Logger logger, LifecycleFailureMonitor.Phase phase) {
        return LifecycleFailureMonitor.observe(new LifecycleFailureMonitor.Options("Probe v1", phase, List.of(logger)));
    }

    private static Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }

    private static final class CountingHandler extends Handler {
        private final AtomicInteger published;

        private CountingHandler(AtomicInteger published) {
            this.published = published;
        }

        @Override
        public void publish(LogRecord record) {
            published.incrementAndGet();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
