package com.volmit.bile;

import org.bukkit.plugin.InvalidPluginException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PaperLoadFailureMonitorTest {
    private static final String LOAD_ERROR = "Error initializing plugin 'Example.jar' in folder 'plugins' (Is it up to date?)";

    @Test
    public void capturesOriginalLoadFailureWithoutSuppressingLogOutput() throws Exception {
        TestLogger logger = new TestLogger();
        Throwable cause = new IllegalStateException("broken onLoad");
        try (PaperLoadFailureMonitor monitor = observe(logger)) {
            logger.log(new TestEvent("Example", new TestMessage(LOAD_ERROR), cause));

            InvalidPluginException failure = assertThrows(InvalidPluginException.class,
                    () -> monitor.throwIfFailed("Example"));
            assertSame(cause, failure.getCause());
            assertEquals(1, logger.output.size());
            assertEquals(1, logger.appenders.size());
        }
        assertTrue(logger.appenders.isEmpty());
        assertTrue(logger.registry.isEmpty());
    }

    @Test
    public void ignoresOtherPluginsThreadsAndOrdinaryPluginErrors() throws Exception {
        TestLogger logger = new TestLogger();
        Throwable cause = new IllegalStateException("unrelated");
        try (PaperLoadFailureMonitor monitor = observe(logger)) {
            logger.log(new TestEvent("OtherPlugin", new TestMessage(LOAD_ERROR), cause));
            logger.log(new TestEvent("Example", new TestMessage("Database unavailable"), cause));
            logger.log(new TestEvent("Example", new TestMessage(LOAD_ERROR), null));
            Thread worker = new Thread(() -> logger.log(new TestEvent("Example", new TestMessage(LOAD_ERROR), cause)));
            worker.start();
            worker.join();

            monitor.throwIfFailed("Example");
            assertEquals(4, logger.output.size());
        }
    }

    @Test
    public void refusesBackendWithoutAppenderCapabilities() {
        PaperLoadFailureMonitor.BackendTypes types = new PaperLoadFailureMonitor.BackendTypes(
                Object.class, TestAppender.class, TestEvent.class, TestMessage.class);
        assertThrows(NoSuchMethodException.class, () -> PaperLoadFailureMonitor.observe(new Object(), types, new HashMap<>()));
    }

    @Test
    public void closePreservesUnrelatedAppenderAndRemovesCapturedCauses() throws Exception {
        TestLogger logger = new TestLogger();
        Object unrelated = new Object();
        logger.registry.put("existing", unrelated);
        PaperLoadFailureMonitor monitor = observe(logger);
        logger.log(new TestEvent("Example", new TestMessage(LOAD_ERROR), new IllegalStateException("failed")));

        monitor.close();

        assertEquals(Map.of("existing", unrelated), logger.registry);
        monitor.throwIfFailed("Example");
    }

    @Test
    public void closePreservesRegistryEntryReplacedByAnotherAppender() throws Exception {
        TestLogger logger = new TestLogger();
        PaperLoadFailureMonitor monitor = observe(logger);
        String name = logger.appenders.get(0).getName();
        Object replacement = new Object();
        logger.registry.put(name, replacement);

        monitor.close();

        assertSame(replacement, logger.registry.get(name));
    }

    private static PaperLoadFailureMonitor observe(TestLogger logger) throws Exception {
        return PaperLoadFailureMonitor.observe(logger, new PaperLoadFailureMonitor.BackendTypes(
                TestLogger.class, TestAppender.class, TestEvent.class, TestMessage.class), logger.registry);
    }

    public interface TestAppender {
        void append(TestEvent event);
        String getName();
    }

    public static final class TestLogger {
        private final List<TestAppender> appenders = new ArrayList<>();
        private final List<TestEvent> output = new ArrayList<>();
        private final Map<String, Object> registry = new HashMap<>();

        public void addAppender(TestAppender appender) {
            appenders.add(appender);
            registry.put(appender.getName(), appender);
        }

        public void removeAppender(TestAppender appender) {
            appenders.remove(appender);
        }

        public void log(TestEvent event) {
            output.add(event);
            for (TestAppender appender : appenders) {
                appender.append(event);
            }
        }
    }

    public record TestEvent(String getLoggerName, TestMessage getMessage, Throwable getThrown) {
    }

    public record TestMessage(String getFormattedMessage) {
    }
}
