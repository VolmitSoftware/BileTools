package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import org.bukkit.plugin.Plugin;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BukkitLifecycleTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void acceptsIsolatedAndRelocatedExplicitReloadContracts() throws Exception {
        for (String packageName : List.of("art.arcane.volmlib.integration", "example.libs.art.arcane.volmlib.integration")) {
            List<String> events = new ArrayList<>();
            try (ForeignParticipant foreign = foreignParticipant(packageName, true, events)) {
                assertFalse(foreign.plugin() instanceof ReloadAware);
                assertTrue(BukkitLifecycle.isParticipant(foreign.plugin()));

                String result = immediate(Duration.ofSeconds(5))
                        .start(() -> plan(List.of(foreign.plugin()), events)).toCompletableFuture().get(1, TimeUnit.SECONDS);

                assertEquals("done", result);
                assertEquals(List.of("prepare-HOT_RELOAD", "commit-HOT_RELOAD", "mutate", "close"), events);
            }
        }
    }

    @Test
    public void relocatedRefusalPreservesItsReasonAndRunsItsCancellation() throws Exception {
        List<String> events = new ArrayList<>();
        try (ForeignParticipant foreign = foreignParticipant("example.libs.art.arcane.volmlib.integration", false, events)) {
            CompletableFuture<String> result = immediate(Duration.ofSeconds(5))
                    .start(() -> plan(List.of(foreign.plugin()), events)).toCompletableFuture();

            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("still saving"));
            assertEquals(List.of("prepare-HOT_RELOAD", "cancel", "close"), events);
        }
    }

    @Test
    public void waitsForEveryPreparationBeforeCommittingOrMutating() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<ReloadPreparation> pending = new CompletableFuture<>();
        Plugin first = participant("first", () -> {
            events.add("prepare-first");
            return pending;
        }, () -> {
            events.add("commit-first");
            return CompletableFuture.completedFuture(null);
        }, () -> CompletableFuture.completedFuture(null));
        Plugin second = participant("second", () -> {
            events.add("prepare-second");
            return CompletableFuture.completedFuture(ReloadPreparation.readyToUnload());
        }, () -> {
            events.add("commit-second");
            return CompletableFuture.completedFuture(null);
        }, () -> CompletableFuture.completedFuture(null));
        BukkitLifecycle lifecycle = immediate(Duration.ofSeconds(5));
        CompletableFuture<String> result = lifecycle.start(() -> plan(List.of(first, second), events)).toCompletableFuture();

        assertEquals(List.of("prepare-first"), events);
        assertFalse(result.isDone());
        pending.complete(ReloadPreparation.readyToUnload());

        assertEquals("done", result.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("prepare-first", "prepare-second", "commit-first", "commit-second", "mutate", "close"), events);
    }

    @Test
    public void refusalCancelsTheWholePreparedGroupWithoutCommitting() {
        List<String> events = new ArrayList<>();
        Plugin first = participant("first", () -> CompletableFuture.completedFuture(ReloadPreparation.readyToUnload()),
                () -> {
                    events.add("commit");
                    return CompletableFuture.completedFuture(null);
                }, () -> {
                    events.add("cancel-first");
                    return CompletableFuture.completedFuture(null);
                });
        Plugin second = participant("second", () -> CompletableFuture.completedFuture(ReloadPreparation.refuse("saving")),
                () -> CompletableFuture.completedFuture(null), () -> {
                    events.add("cancel-second");
                    return CompletableFuture.completedFuture(null);
                });
        CompletableFuture<String> result = immediate(Duration.ofSeconds(5))
                .start(() -> plan(List.of(first, second), events)).toCompletableFuture();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("saving"));
        assertEquals(List.of("cancel-second", "cancel-first", "close"), events);
    }

    @Test
    public void commitFailureRestoresTheGroupBeforeClosing() {
        List<String> events = new ArrayList<>();
        Plugin plugin = participant("target", () -> CompletableFuture.completedFuture(ReloadPreparation.readyToUnload()),
                () -> CompletableFuture.failedFuture(new IllegalStateException("drain failed")),
                () -> CompletableFuture.completedFuture(null));
        CompletableFuture<String> result = immediate(Duration.ofSeconds(5))
                .start(() -> plan(List.of(plugin), events)).toCompletableFuture();

        assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("recover", "close"), events);
    }

    @Test
    public void expiredPreparationStaysQuarantinedUntilItSettlesAndCancels() throws Exception {
        List<String> events = new ArrayList<>();
        CompletableFuture<ReloadPreparation> pending = new CompletableFuture<>();
        Plugin plugin = participant("target", () -> pending, () -> {
            events.add("commit");
            return CompletableFuture.completedFuture(null);
        }, () -> {
            events.add("cancel");
            return CompletableFuture.completedFuture(null);
        });
        BukkitLifecycle lifecycle = immediate(Duration.ofMillis(30));
        CompletableFuture<String> result = lifecycle.start(() -> plan(List.of(plugin), events)).toCompletableFuture();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertThrows(ExecutionException.class,
                () -> lifecycle.start(() -> plan(List.of(), events)).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertTrue(events.isEmpty());

        pending.complete(ReloadPreparation.readyToUnload());

        assertEquals(List.of("cancel", "close"), events);
        assertEquals("done", lifecycle.start(() -> plan(List.of(), events)).toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    public void expiredCommitDoesNotRecoverUntilTheDrainActuallyFinishes() {
        List<String> events = new ArrayList<>();
        CompletableFuture<Void> drain = new CompletableFuture<>();
        Plugin plugin = participant("target", () -> CompletableFuture.completedFuture(ReloadPreparation.readyToUnload()),
                () -> drain, () -> CompletableFuture.completedFuture(null));
        BukkitLifecycle lifecycle = immediate(Duration.ofMillis(30));
        CompletableFuture<String> result = lifecycle.start(() -> plan(List.of(plugin), events)).toCompletableFuture();

        assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        assertTrue(events.isEmpty());
        drain.complete(null);
        assertEquals(List.of("recover", "close"), events);
    }

    @Test
    public void expiredQueuedOperationNeverBuildsItsPlan() {
        List<Runnable> queue = new ArrayList<>();
        List<String> events = new ArrayList<>();
        BukkitLifecycle lifecycle = new BukkitLifecycle(queue::add, Duration.ofMillis(30));
        CompletableFuture<String> result = lifecycle.start(() -> {
            events.add("factory");
            return plan(List.of(), events);
        }).toCompletableFuture();

        assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        queue.remove(0).run();
        assertTrue(events.isEmpty());
    }

    private static BukkitLifecycle immediate(Duration timeout) {
        return new BukkitLifecycle(task -> {
            task.run();
            return true;
        }, timeout);
    }

    private static BukkitLifecycle.Plan<String> plan(List<Plugin> plugins, List<String> events) {
        return new BukkitLifecycle.Plan<>(plugins, ReloadAware.PreUnloadReason.HOT_RELOAD, () -> {
            events.add("mutate");
            return "done";
        }, failure -> events.add("recover"), () -> events.add("close"));
    }

    private static Plugin participant(String name,
                                      Supplier<CompletionStage<ReloadPreparation>> prepare,
                                      Supplier<CompletionStage<Void>> commit,
                                      Supplier<CompletionStage<Void>> cancel) {
        return (Plugin) Proxy.newProxyInstance(BukkitLifecycleTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class, ReloadAware.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    case "prepareReload" -> prepare.get();
                    case "commitReload" -> commit.get();
                    case "cancelReload" -> cancel.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private ForeignParticipant foreignParticipant(String packageName, boolean ready, List<String> events) throws Exception {
        Path directory = temporaryFolder.newFolder().toPath();
        Path contractFile = directory.resolve("ReloadAware.java");
        Path preparationFile = directory.resolve("ReloadPreparation.java");
        Files.writeString(contractFile, "package " + packageName + ";\n" + """
                import java.util.concurrent.CompletionStage;
                public interface ReloadAware {
                    CompletionStage<ReloadPreparation> prepareReload(PreUnloadReason reason);
                    CompletionStage<Void> commitReload(PreUnloadReason reason);
                    CompletionStage<Void> cancelReload();
                    enum PreUnloadReason { HOT_RELOAD, HOT_UNLOAD }
                }
                """);
        Files.writeString(preparationFile, "package " + packageName + ";\n"
                + "public record ReloadPreparation(boolean ready, String reason) {}\n");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "--release", "17", "-d", directory.toString(),
                contractFile.toString(), preparationFile.toString()));
        URLClassLoader loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(packageName + ".")) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };
        try {
            Class<?> contract = loader.loadClass(packageName + ".ReloadAware");
            Object preparation = loader.loadClass(packageName + ".ReloadPreparation")
                    .getConstructor(boolean.class, String.class).newInstance(ready, ready ? "" : "still saving");
            Plugin plugin = (Plugin) Proxy.newProxyInstance(loader, new Class<?>[]{Plugin.class, contract},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "getName", "toString" -> "Foreign";
                        case "prepareReload" -> {
                            events.add("prepare-" + ((Enum<?>) arguments[0]).name());
                            yield CompletableFuture.completedFuture(preparation);
                        }
                        case "commitReload" -> {
                            events.add("commit-" + ((Enum<?>) arguments[0]).name());
                            yield CompletableFuture.completedFuture(null);
                        }
                        case "cancelReload" -> {
                            events.add("cancel");
                            yield CompletableFuture.completedFuture(null);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return new ForeignParticipant(plugin, loader);
        } catch (Exception | Error failure) {
            loader.close();
            throw failure;
        }
    }

    private record ForeignParticipant(Plugin plugin, URLClassLoader loader) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            loader.close();
        }
    }
}
