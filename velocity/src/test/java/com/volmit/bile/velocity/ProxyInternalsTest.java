package com.volmit.bile.velocity;

import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProxyInternalsTest {
    private static final String KEY = "plugin-manager.maps";

    @Test
    void resolvesAFieldUnderTheSecondCandidateName() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        Field field = resolution.field(KEY, Sample.class, List.of("executorService", "service"));

        assertNotNull(field);
        assertEquals("service", field.getName());
        assertTrue(resolution.supports(KEY));
        assertTrue(resolution.report().notes().isEmpty());
    }

    @Test
    void flipsTheCapabilityAndNotesTheMemberWhenNoFieldCandidateMatches() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        Field field = resolution.field(KEY, Sample.class, List.of("executorService", "pool"));

        assertNull(field);
        assertFalse(resolution.supports(KEY));
        List<String> notes = resolution.report().notes();
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains(Sample.class.getName()), notes.get(0));
        assertTrue(notes.get(0).contains("executorService"), notes.get(0));
        assertTrue(notes.get(0).contains("pool"), notes.get(0));
    }

    @Test
    void resolvesAMethodUnderTheSecondCandidateName() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        Method method = resolution.method(KEY, Sample.class, List.of("fireScoped", "fire"), String.class, int.class);

        assertNotNull(method);
        assertEquals("fire", method.getName());
        assertTrue(method.canAccess(new Sample()));
        assertTrue(resolution.supports(KEY));
    }

    @Test
    void flipsTheCapabilityAndNotesTheMemberWhenNoMethodCandidateMatches() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        Method method = resolution.method(KEY, Sample.class, List.of("bakeHandlers"), String.class);

        assertNull(method);
        assertFalse(resolution.supports(KEY));
        assertTrue(resolution.report().notes().get(0).contains("bakeHandlers"));
    }

    @Test
    void flipsTheCapabilityWhenTheParameterTypesDoNotMatch() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        assertNull(resolution.method(KEY, Sample.class, List.of("fire"), String.class));
        assertFalse(resolution.supports(KEY));
    }

    @Test
    void resolvesAConstructorAndNotesAMissingOne() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));

        Constructor<?> present = resolution.constructor(KEY, Sample.class);
        assertNotNull(present);
        assertTrue(resolution.supports(KEY));

        assertNull(resolution.constructor(KEY, Sample.class, Long.class));
        assertFalse(resolution.supports(KEY));
    }

    @Test
    void resolvesATypeByNameAndNotesAMissingOne() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of(KEY));
        ClassLoader loader = ProxyInternalsTest.class.getClassLoader();

        assertEquals(Sample.class, resolution.type(KEY, loader, List.of("com.volmit.bile.velocity.Absent", Sample.class.getName())));
        assertTrue(resolution.supports(KEY));

        assertNull(resolution.type(KEY, loader, List.of("com.volmit.bile.velocity.Absent")));
        assertFalse(resolution.supports(KEY));
        assertTrue(resolution.report().notes().get(0).contains("com.volmit.bile.velocity.Absent"));
    }

    @Test
    void everyDeclaredCapabilityStartsTrueAndIsPresentInTheReport() {
        ProxyInternals.Resolution resolution = new ProxyInternals.Resolution(List.of("a", "b"));

        ProxyCapabilityReport report = resolution.report();

        assertEquals(2, report.capabilities().size());
        assertTrue(report.supports("a"));
        assertTrue(report.supports("b"));
    }

    @Test
    void resolveNeverThrowsOffAVelocityProxyAndReportsNoSupport() {
        ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        Logger logger = mock(Logger.class);

        ProxyInternals internals = ProxyInternals.resolve(proxy, logger);

        assertNotNull(internals);
        ProxyCapabilityReport report = internals.report();
        assertFalse(report.supportsLoad());
        assertFalse(report.supportsUnload());
        assertFalse(report.notes().isEmpty());
        assertFalse(report.summary().isBlank());
    }

    @Test
    void liveViewsDegradeToEmptyWhenNothingResolved() {
        ProxyInternals internals = ProxyInternals.resolve(mock(ProxyServer.class, RETURNS_DEEP_STUBS), mock(Logger.class));

        assertTrue(internals.pluginsById().isEmpty());
        assertTrue(internals.pluginInstances().isEmpty());
        assertTrue(internals.pluginList().isEmpty());
    }

    @Test
    void dispatchRunsEveryHandlerInOrderAndCollectsTheFailures() throws Exception {
        ProxyInternals internals = internals();
        List<String> ran = new ArrayList<>();
        IllegalStateException boom = new IllegalStateException("registered a command and then exploded");
        List<ProxyInternals.ScopedHandler> handlers = List.of(
                handler("first", event -> ran.add("first")),
                handler("second", event -> {
                    throw boom;
                }),
                handler("third", event -> ran.add("third")));

        List<ProxyInternals.HandlerFailure> failures = internals.dispatchHandlers(container("demo"),
                new ProxyInitializeEvent(), handlers, Duration.ofSeconds(5L));

        assertEquals(List.of("first", "third"), ran);
        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0).error());
        assertEquals("second", failures.get(0).handler());
        assertEquals("demo", failures.get(0).pluginId());
    }

    @Test
    void dispatchJoinsAnEventTaskAndCollectsTheExceptionItResumesWith() throws Exception {
        ProxyInternals internals = internals();
        RuntimeException boom = new RuntimeException("continuation boom");

        List<ProxyInternals.HandlerFailure> failures = internals.dispatchHandlers(container("demo"),
                new ProxyShutdownEvent(),
                List.of(taskHandler("failing", continuation -> continuation.resumeWithException(boom)),
                        taskHandler("clean", Continuation::resume)),
                Duration.ofSeconds(5L));

        assertEquals(1, failures.size());
        assertSame(boom, failures.get(0).error());
        assertEquals("failing", failures.get(0).handler());
    }

    @Test
    void dispatchWaitsForAContinuationResumedOnAnotherThread() throws Exception {
        ProxyInternals internals = internals();
        AtomicBoolean resumed = new AtomicBoolean();

        List<ProxyInternals.HandlerFailure> failures = internals.dispatchHandlers(container("demo"),
                new ProxyInitializeEvent(),
                List.of(taskHandler("async", continuation -> {
                    Thread worker = new Thread(() -> {
                        resumed.set(true);
                        continuation.resume();
                    });
                    worker.setDaemon(true);
                    worker.start();
                })),
                Duration.ofSeconds(5L));

        assertTrue(failures.isEmpty());
        assertTrue(resumed.get());
    }

    @Test
    void dispatchTimesOutWhenAContinuationNeverResumes() {
        ProxyInternals internals = internals();

        HotloadException failure = assertThrows(HotloadException.class, () -> internals.dispatchHandlers(
                container("demo"), new ProxyInitializeEvent(),
                List.of(taskHandler("stuck", continuation -> {
                })), Duration.ofMillis(50L)));

        assertEquals(HotloadException.Kind.TIMEOUT, failure.kind());
    }

    private static ProxyInternals internals() {
        return ProxyInternals.resolve(mock(ProxyServer.class, RETURNS_DEEP_STUBS), mock(Logger.class));
    }

    private static PluginContainer container(String id) {
        PluginDescription description = mock(PluginDescription.class);
        when(description.getId()).thenReturn(id);
        PluginContainer container = mock(PluginContainer.class);
        when(container.getDescription()).thenReturn(description);
        return container;
    }

    private static ProxyInternals.ScopedHandler handler(String name, EventHandler<Object> handler) {
        return new ProxyInternals.ScopedHandler(handler, name);
    }

    private static ProxyInternals.ScopedHandler taskHandler(String name, Consumer<Continuation> body) {
        return new ProxyInternals.ScopedHandler(new EventHandler<>() {
            @Override
            public void execute(Object event) {
            }

            @Override
            public EventTask executeAsync(Object event) {
                return EventTask.withContinuation(body);
            }
        }, name);
    }

    static final class Sample {
        private String service;

        Sample() {
        }

        private void fire(String event, int offset) {
            this.service = event + offset;
        }
    }
}
