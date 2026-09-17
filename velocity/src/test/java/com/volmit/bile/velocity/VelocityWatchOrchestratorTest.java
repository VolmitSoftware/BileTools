package com.volmit.bile.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class VelocityWatchOrchestratorTest {
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(3L);
    private static final long AWAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final long POLL_MILLIS = 10L;

    @TempDir
    Path root;

    private Path pluginsDirectory;
    private Path dataDirectory;
    private ProxyServer proxy;
    private Logger logger;
    private VelocityPluginHotloader hotloader;
    private VelocityWatchOrchestrator orchestrator;
    private VelocityConfig config;
    private AtomicLong clock;
    private final List<String> loadedIds = new CopyOnWriteArrayList<>();
    private final List<Path> loadedPaths = new CopyOnWriteArrayList<>();
    private final List<Path> loadedOrigins = new CopyOnWriteArrayList<>();
    private final List<String> unloadedIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        pluginsDirectory = Files.createDirectories(root.resolve("plugins"));
        dataDirectory = Files.createDirectories(pluginsDirectory.resolve("biletools"));
        proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        logger = mock(Logger.class);
        hotloader = mock(VelocityPluginHotloader.class);
        clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1000L));

        config = loadConfig("{\"watcher\": {\"fingerprint-debounce-polls\": 2}}");

        when(hotloader.find(anyString())).thenReturn(Optional.empty());
        when(hotloader.load(any(), any())).thenAnswer(invocation -> {
            Path staged = invocation.getArgument(0);
            loadedIds.add(readDescriptor(staged).map(VelocityPluginDescriptor::id).orElse("?"));
            loadedPaths.add(staged);
            loadedOrigins.add(invocation.getArgument(1));
            return mock(PluginContainer.class);
        });
        when(hotloader.unload(anyString(), any())).thenAnswer(invocation -> {
            unloadedIds.add(invocation.getArgument(0));
            return Set.of();
        });

        orchestrator = newOrchestrator(config, new ProxyMessages(proxy, logger, false));
        orchestrator.start();
    }

    @AfterEach
    public void tearDown() {
        orchestrator.close();
    }

    @Test
    public void loadsANewJarFromTheStagingCopyAfterTheDebounce() throws Exception {
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");

        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1 && loadedPaths.size() == 1);

        assertEquals(List.of("gloss"), loadedIds);
        Path staged = loadedPaths.get(0);
        assertFalse(staged.equals(jar));
        assertTrue(staged.startsWith(dataDirectory.resolve("watcher-stage")));
        assertEquals(List.of(jar), loadedOrigins);
    }

    @Test
    public void manualReloadStagesFromThePluginsDirectoryJarAfterAnAutomaticReload() throws Exception {
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1);
        awaitCondition(() -> !orchestrator.isBatchInFlight());
        assertEquals(List.of(jar), loadedOrigins);

        PluginContainer container = loadedContainer("gloss");
        when(hotloader.find("gloss")).thenReturn(Optional.of(container));
        when(hotloader.sourceOf("gloss")).thenReturn(Optional.of(jar));

        assertEquals("Reloaded gloss.", orchestrator.manualReload("gloss").get(10L, TimeUnit.SECONDS));

        verify(hotloader).reload("gloss", jar);
    }

    @Test
    public void doesNotLoadAgainWhenTheFingerprintIsUnchanged() throws Exception {
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1 && loadedPaths.size() == 1);
        awaitCondition(() -> !orchestrator.isBatchInFlight());

        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 10_000L));
        stageOnePendingJar();
        tick();

        assertEquals(1, loadedPaths.size());
        verify(hotloader, times(1)).load(any(), any());
    }

    @Test
    public void unloadsAfterTheDeletionGrace() throws Exception {
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1 && loadedPaths.size() == 1);
        awaitCondition(() -> !orchestrator.isBatchInFlight());

        Files.delete(jar);
        tick();
        assertEquals(List.of(), unloadedIds);

        tick();
        tick();
        awaitCondition(() -> unloadedIds.size() == 1);

        assertEquals(List.of("gloss"), unloadedIds);
        verify(hotloader).unload("gloss", UnloadReason.HOT_UNLOAD);
    }

    @Test
    public void skipsPluginsMarkedDirtyByAFailedLifecycle() throws Exception {
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "boom"))
                .when(hotloader).load(any(), any());
        writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> orchestrator.snapshot().dirtyPlugins().contains("gloss"));

        writeJar("gloss.jar", "gloss", List.of(), "two");
        stageOnePendingJar();
        tick();

        verify(logger, atLeastOnce()).warn(contains("dirty"), anyString());
        verify(hotloader, times(1)).load(any(), any());
    }

    @Test
    public void refusesToReloadItsOwnJarAndLogsOncePerFingerprint() throws Exception {
        Path jar = writeJar("BileTools.jar", "biletools", List.of(), "one");
        stageOnePendingJar();
        tick();

        verify(logger, times(1)).warn(contains("restart the proxy"));
        verify(hotloader, never()).load(any(), any());

        Files.setLastModifiedTime(jar, FileTime.fromMillis(System.currentTimeMillis() + 10_000L));
        stageOnePendingJar();
        tick();

        verify(logger, times(1)).warn(contains("restart the proxy"));
        verify(hotloader, never()).load(any(), any());
    }

    @Test
    public void ignoresJarsWithoutAProxyDescriptor() throws Exception {
        writeJarWithoutDescriptor("spigot-only.jar");

        stageOnePendingJar();
        tick();
        tick();

        verify(hotloader, never()).load(any(), any());
        assertEquals(List.of(), loadedIds);
    }

    @Test
    public void ordersABatchSoDependenciesLoadFirst() throws Exception {
        writeJar("beta.jar", "beta", List.of("alpha"), "b");
        writeJar("alpha.jar", "alpha", List.of(), "a");

        tickUntil(() -> orchestrator.activeStagingTaskCount() == 2);
        awaitCondition(() -> orchestrator.completedStageCount() == 2);
        tick();
        awaitCondition(() -> loadedIds.size() == 2);

        assertEquals(List.of("alpha", "beta"), loadedIds);
    }

    @Test
    public void unloadsAJarThatWasAlreadyPresentWhenTheProxyStarted() throws Exception {
        orchestrator.close();
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        orchestrator = newOrchestrator(config, new ProxyMessages(proxy, logger, false));
        orchestrator.start();

        Files.delete(jar);
        tick();
        assertEquals(List.of(), unloadedIds);

        tick();
        tick();
        awaitCondition(() -> unloadedIds.size() == 1);

        assertEquals(List.of("gloss"), unloadedIds);
        verify(hotloader).unload("gloss", UnloadReason.HOT_UNLOAD);
        verify(hotloader, never()).load(any(), any());
    }

    @Test
    public void completesTheBatchWhenTheOperatorNotificationThrows() throws Exception {
        orchestrator.close();
        ProxyMessages messages = mock(ProxyMessages.class);
        doThrow(new IllegalStateException("no player list")).when(messages).notifyOperators(any());
        orchestrator = newOrchestrator(config, messages);
        orchestrator.start();

        writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1);

        awaitCondition(() -> !orchestrator.isBatchInFlight());
        tick();
        assertTrue(stagedSnapshots().isEmpty(), stagedSnapshots().toString());
    }

    @Test
    public void skipsAutomaticWorkForIgnoredPlugins() throws Exception {
        restartWith("{\"watcher\": {\"fingerprint-debounce-polls\": 2, \"ignore\": [\"Gloss\"]}}");
        writeJar("gloss.jar", "gloss", List.of(), "one");

        stageOnePendingJar();
        tick();

        verify(hotloader, never()).load(any(), any());
    }

    @Test
    public void managesOnlyThePluginsListedInWatcherOnly() throws Exception {
        restartWith("{\"watcher\": {\"fingerprint-debounce-polls\": 2, \"only\": [\"alpha\"]}}");
        writeJar("gloss.jar", "gloss", List.of(), "g");
        writeJar("alpha.jar", "alpha", List.of(), "a");

        tickUntil(() -> orchestrator.activeStagingTaskCount() == 2);
        awaitCondition(() -> orchestrator.completedStageCount() == 2);
        tick();
        awaitCondition(() -> loadedIds.size() == 1);

        assertEquals(List.of("alpha"), loadedIds);
    }

    @Test
    public void manualReloadBypassesTheDirtySetAndClearsIt() throws Exception {
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "boom"))
                .when(hotloader).load(any(), any());
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> orchestrator.snapshot().dirtyPlugins().contains("gloss"));

        PluginContainer container = loadedContainer("gloss");
        when(hotloader.find("gloss")).thenReturn(Optional.of(container));
        when(hotloader.sourceOf("gloss")).thenReturn(Optional.of(jar));

        assertEquals("Reloaded gloss.", orchestrator.manualReload("gloss").get(10L, TimeUnit.SECONDS));

        verify(hotloader).reload("gloss", jar);
        assertFalse(orchestrator.snapshot().dirtyPlugins().contains("gloss"));
    }

    @Test
    public void manualFailureMarksTheDescriptorIdAndAManualSuccessUnblocksTheWatcher() throws Exception {
        writeJar("bilepig-c.jar", "bilepig-c", List.of(), "one");
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "boom")).when(hotloader).load(any());

        assertThrows(ExecutionException.class,
                () -> orchestrator.manualLoad("bilepig-c.jar").get(10L, TimeUnit.SECONDS));
        assertEquals(Set.of("bilepig-c"), orchestrator.snapshot().dirtyPlugins());

        PluginContainer container = loadedContainer("bilepig-c");
        doReturn(container).when(hotloader).load(any());
        assertTrue(orchestrator.manualLoad("bilepig-c").get(10L, TimeUnit.SECONDS).startsWith("Loaded bilepig-c"));
        assertEquals(Set.of(), orchestrator.snapshot().dirtyPlugins());

        writeJar("bilepig-c.jar", "bilepig-c", List.of(), "two");
        stageOnePendingJar();
        awaitCondition(() -> loadedIds.size() == 1);

        verify(hotloader).load(any(), any());
    }

    @Test
    public void anAutomaticFailureIsClearedByAManualReloadOnTheJarName() throws Exception {
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "boom"))
                .when(hotloader).load(any(), any());
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> orchestrator.snapshot().dirtyPlugins().contains("gloss"));

        PluginContainer container = loadedContainer("gloss");
        when(hotloader.find("gloss")).thenReturn(Optional.of(container));
        when(hotloader.sourceOf("gloss")).thenReturn(Optional.of(jar));

        assertEquals("Reloaded gloss.", orchestrator.manualReload("gloss.jar").get(10L, TimeUnit.SECONDS));

        assertEquals(Set.of(), orchestrator.snapshot().dirtyPlugins());
    }

    @Test
    public void deletingAJarClearsItsDirtyEntry() throws Exception {
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "boom"))
                .when(hotloader).load(any(), any());
        Path jar = writeJar("gloss.jar", "gloss", List.of(), "one");
        stageOnePendingJar();
        awaitCondition(() -> orchestrator.snapshot().dirtyPlugins().contains("gloss"));

        Files.delete(jar);
        tick();
        tick();
        tick();

        awaitCondition(() -> orchestrator.snapshot().dirtyPlugins().isEmpty());
    }

    private VelocityWatchOrchestrator newOrchestrator(VelocityConfig configuration, ProxyMessages messages) {
        return new VelocityWatchOrchestrator(proxy, new Object(), logger, pluginsDirectory, dataDirectory,
                configuration, hotloader, messages, clock::get, this::readDescriptor);
    }

    private void restartWith(String document) throws IOException {
        orchestrator.close();
        config = loadConfig(document);
        orchestrator = newOrchestrator(config, new ProxyMessages(proxy, logger, false));
        orchestrator.start();
    }

    private VelocityConfig loadConfig(String document) throws IOException {
        Path file = dataDirectory.resolve("biletools.json");
        Files.writeString(file, document, StandardCharsets.UTF_8);
        return VelocityConfig.load(file, logger);
    }

    private List<Path> stagedSnapshots() throws IOException {
        Path staging = dataDirectory.resolve("watcher-stage");
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(staging)) {
            return stream.toList();
        }
    }

    private PluginContainer loadedContainer(String id) {
        PluginDescription description = mock(PluginDescription.class);
        when(description.getId()).thenReturn(id);
        PluginContainer container = mock(PluginContainer.class);
        when(container.getDescription()).thenReturn(description);
        return container;
    }

    private void stageOnePendingJar() {
        tickUntil(() -> orchestrator.activeStagingTaskCount() == 1);
        awaitCondition(() -> orchestrator.completedStageCount() == 1);
        tick();
    }

    private void tick() {
        clock.addAndGet(TICK_NANOS);
        orchestrator.tick();
    }

    private void tickUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            tick();
            if (condition.getAsBoolean()) {
                return;
            }
            sleepPollInterval();
        }
        throw new AssertionError("Condition was not reached while ticking the orchestrator");
    }

    private void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepPollInterval();
        }
        throw new AssertionError("Condition was not reached before the timeout");
    }

    private void sleepPollInterval() {
        try {
            TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for a condition", exception);
        }
    }

    private Path writeJar(String fileName, String id, List<String> dependencies, String payload) throws IOException {
        Path jar = pluginsDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("velocity-plugin.json"));
            output.write(descriptorDocument(id, dependencies).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new JarEntry("payload.txt"));
            output.write(payload.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private Path writeJarWithoutDescriptor(String fileName) throws IOException {
        Path jar = pluginsDirectory.resolve(fileName);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("plugin.yml"));
            output.write("name: Spigot\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jar;
    }

    private String descriptorDocument(String id, List<String> dependencies) {
        StringBuilder document = new StringBuilder("{\"id\":\"").append(id)
                .append("\",\"name\":\"").append(id)
                .append("\",\"version\":\"1.0\",\"main\":\"test.").append(id)
                .append("\",\"dependencies\":[");
        for (int index = 0; index < dependencies.size(); index++) {
            if (index > 0) {
                document.append(',');
            }
            document.append("{\"id\":\"").append(dependencies.get(index)).append("\",\"optional\":false}");
        }
        return document.append("]}").toString();
    }

    private Optional<VelocityPluginDescriptor> readDescriptor(Path jar) {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            ZipEntry entry = archive.getEntry("velocity-plugin.json");
            if (entry == null) {
                return Optional.empty();
            }
            JsonObject document;
            try (InputStreamReader reader =
                         new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
                document = JsonParser.parseReader(reader).getAsJsonObject();
            }
            List<String> required = new ArrayList<>();
            List<String> optional = new ArrayList<>();
            JsonElement dependencies = document.get("dependencies");
            if (dependencies != null && dependencies.isJsonArray()) {
                JsonArray array = dependencies.getAsJsonArray();
                for (JsonElement element : array) {
                    JsonObject dependency = element.getAsJsonObject();
                    String dependencyId = dependency.get("id").getAsString();
                    if (dependency.has("optional") && dependency.get("optional").getAsBoolean()) {
                        optional.add(dependencyId);
                    } else {
                        required.add(dependencyId);
                    }
                }
            }
            return Optional.of(new VelocityPluginDescriptor(
                    document.get("id").getAsString().toLowerCase(Locale.ROOT),
                    document.get("name").getAsString(),
                    document.get("version").getAsString(),
                    document.get("main").getAsString(),
                    List.copyOf(required),
                    List.copyOf(optional),
                    jar));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }
}
