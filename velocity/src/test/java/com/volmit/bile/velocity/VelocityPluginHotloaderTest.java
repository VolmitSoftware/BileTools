package com.volmit.bile.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityPluginHotloaderTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path temp;

    private Path pluginsDirectory;
    private Path dataDirectory;
    private Path runtimeDirectory;
    private Path archiveDirectory;
    private ProxyServer proxy;
    private EventManager eventManager;
    private Scheduler scheduler;
    private CommandManager commandManager;
    private Logger logger;
    private ProxyInternals internals;
    private Object selfInstance;
    private Map<String, PluginContainer> pluginsById;
    private Map<Object, PluginContainer> pluginInstances;
    private Map<String, PluginContainer> pending;

    @BeforeEach
    void setUp() throws Exception {
        pluginsDirectory = temp.resolve("plugins");
        dataDirectory = pluginsDirectory.resolve("biletools");
        runtimeDirectory = dataDirectory.resolve("runtime-plugins");
        archiveDirectory = dataDirectory.resolve("archive");
        Files.createDirectories(dataDirectory);

        proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        eventManager = proxy.getEventManager();
        scheduler = proxy.getScheduler();
        commandManager = proxy.getCommandManager();
        logger = mock(Logger.class);
        internals = mock(ProxyInternals.class);

        pluginsById = new LinkedHashMap<>();
        pluginInstances = new IdentityHashMap<>();
        pending = new LinkedHashMap<>();

        doReturn(List.of()).when(commandManager).getAliases();
        when(internals.report()).thenReturn(report());
        when(internals.pluginsById()).thenReturn(pluginsById);
        when(internals.pluginInstances()).thenReturn(pluginInstances);
        when(internals.pluginList()).thenReturn(Optional.empty());
        when(internals.providedIds(any())).thenReturn(List.of());
        when(internals.classLoaderOf(any())).thenReturn(Optional.of(getClass().getClassLoader()));
        when(internals.createContainer(any())).thenAnswer(invocation -> pendingFor(invocation.getArgument(0)));
        when(internals.instantiate(any())).thenAnswer(invocation -> instanceOf(invocation.getArgument(0)));
        doAnswer(invocation -> registerInMaps(invocation.getArgument(0))).when(internals).registerContainer(any());
        doAnswer(invocation -> removeFromMaps(invocation.getArgument(0))).when(internals).unregisterContainer(any());

        selfInstance = new Object();
        registerInMaps(container("biletools", selfInstance, List.of(), null));
    }

    @Test
    void loadDrivesTheInternalsInTheMandatedOrder() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader(new HotloadOptions(true, false, false, TIMEOUT));

        PluginContainer loaded = hotloader.load(jar);

        assertSame(demo, loaded);
        ArgumentCaptor<Path> staged = ArgumentCaptor.forClass(Path.class);
        InOrder order = inOrder(internals);
        order.verify(internals).createContainer(staged.capture());
        order.verify(internals).instantiate(demo);
        order.verify(internals).registerContainer(demo);
        order.verify(internals).registerListenersInternally(demo, instanceOf(demo));
        order.verify(internals).fireScoped(eq(demo), isA(ProxyInitializeEvent.class), eq(TIMEOUT));
        assertTrue(staged.getValue().startsWith(runtimeDirectory), staged.getValue().toString());
        assertTrue(staged.getValue().getFileName().toString().startsWith("demo-"), staged.getValue().toString());
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(staged.getValue()));
        assertEquals(Optional.of(jar), hotloader.sourceOf("demo"));
    }

    @Test
    void loadRefusesAPluginThatIsAlreadyLoaded() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        registerInMaps(container("demo", new Object(), List.of(), jar));

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.ALREADY_LOADED, failure.kind());
        verify(internals, never()).createContainer(any());
    }

    @Test
    void loadRefusesBileToolsItself() throws Exception {
        Path jar = sourceJar("BileTools.jar", JarFixtures.descriptor("biletools", "com.volmit.bile.velocity.BileVelocity"));

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.SELF, failure.kind());
        verify(internals, never()).createContainer(any());
    }

    @Test
    void loadListsEveryMissingRequiredDependency() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo",
                "[{\"id\":\"core\"},{\"id\":\"api\"},{\"id\":\"extras\",\"optional\":true}]"));

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.MISSING_DEPENDENCY, failure.kind());
        assertTrue(failure.getMessage().contains("core"), failure.getMessage());
        assertTrue(failure.getMessage().contains("api"), failure.getMessage());
        assertFalse(failure.getMessage().contains("extras"), failure.getMessage());
    }

    @Test
    void loadRefusesAnUnparseableJar() throws Exception {
        Path jar = sourceJar("Plain.jar", null);

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.INVALID_DESCRIPTOR, failure.kind());
    }

    @Test
    void loadRefusesBeforeMutatingAnythingWhenACapabilityIsMissing() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        when(internals.report()).thenReturn(reportWithout("plugin-manager.maps"));

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.UNSUPPORTED_CAPABILITY, failure.kind());
        assertTrue(failure.getMessage().contains("plugin-manager.maps"), failure.getMessage());
        verify(internals, never()).createContainer(any());
        verify(internals, never()).registerContainer(any());
        assertFalse(Files.exists(runtimeDirectory));
    }

    @Test
    void unloadRefusesBeforeMutatingAnythingWhenACapabilityIsMissing() throws Exception {
        registerInMaps(container("demo", new Object(), List.of(), temp.resolve("Demo.jar")));
        when(internals.report()).thenReturn(reportWithout("command-manager.unregister"));

        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().unload("demo", UnloadReason.HOT_UNLOAD));

        assertEquals(HotloadException.Kind.UNSUPPORTED_CAPABILITY, failure.kind());
        verify(internals, never()).unregisterContainer(any());
    }

    @Test
    void unloadRunsEveryTeardownStepInTheMandatedOrder() throws Exception {
        Object instance = new Object();
        PluginContainer demo = container("demo", instance, List.of(), temp.resolve("Demo.jar"));
        registerInMaps(demo);
        ScheduledTask task = mock(ScheduledTask.class);
        doReturn(List.of(task)).when(scheduler).tasksByPlugin(instance);
        CommandMeta meta = mock(CommandMeta.class);
        when(meta.getPlugin()).thenReturn(instance);
        doReturn(List.of("demo")).when(commandManager).getAliases();
        when(commandManager.getCommandMeta("demo")).thenReturn(meta);

        Set<Path> dependents = hotloader().unload("demo", UnloadReason.HOT_UNLOAD);

        assertTrue(dependents.isEmpty());
        InOrder order = inOrder(internals, eventManager, scheduler, commandManager);
        order.verify(internals).fireScopedCollecting(eq(demo), isA(ProxyShutdownEvent.class), eq(TIMEOUT));
        order.verify(eventManager).unregisterListeners(instance);
        order.verify(scheduler).tasksByPlugin(instance);
        order.verify(commandManager).getAliases();
        order.verify(commandManager).unregister(meta);
        order.verify(internals).unregisterContainer(demo);
        order.verify(internals).shutdownContainerExecutor(demo);
        order.verify(internals).closeClassLoader(demo);
        verify(task).cancel();
        assertTrue(hotloader().find("demo").isEmpty());
    }

    @Test
    void unloadLeavesCommandsOwnedByOtherPluginsAlone() throws Exception {
        Object instance = new Object();
        registerInMaps(container("demo", instance, List.of(), temp.resolve("Demo.jar")));
        CommandMeta foreign = mock(CommandMeta.class);
        when(foreign.getPlugin()).thenReturn(new Object());
        CommandMeta orphan = mock(CommandMeta.class);
        when(orphan.getPlugin()).thenReturn(null);
        doReturn(List.of("foreign", "orphan")).when(commandManager).getAliases();
        when(commandManager.getCommandMeta("foreign")).thenReturn(foreign);
        when(commandManager.getCommandMeta("orphan")).thenReturn(orphan);

        hotloader().unload("demo", UnloadReason.HOT_UNLOAD);

        verify(commandManager, never()).unregister(any(CommandMeta.class));
    }

    @Test
    void unloadTakesDependentsFirstAndReturnsTheirSourcePaths() throws Exception {
        Path coreJar = temp.resolve("Core.jar");
        Path addonJar = temp.resolve("Addon.jar");
        PluginContainer core = container("core", new Object(), List.of(), coreJar);
        PluginContainer addon = container("addon", new Object(), List.of(new PluginDependency("core", "1.0", false)), addonJar);
        registerInMaps(core);
        registerInMaps(addon);

        Set<Path> dependents = hotloader().unload("core", UnloadReason.HOT_UNLOAD);

        assertEquals(Set.of(addonJar), dependents);
        InOrder order = inOrder(internals);
        order.verify(internals).unregisterContainer(addon);
        order.verify(internals).unregisterContainer(core);
    }

    @Test
    void unloadFollowsOptionalDependenciesTransitively() throws Exception {
        PluginContainer core = container("core", new Object(), List.of(), temp.resolve("Core.jar"));
        PluginContainer middle = container("middle", new Object(), List.of(new PluginDependency("core", "1.0", true)), temp.resolve("Middle.jar"));
        PluginContainer leaf = container("leaf", new Object(), List.of(new PluginDependency("middle", "1.0", false)), temp.resolve("Leaf.jar"));
        registerInMaps(core);
        registerInMaps(middle);
        registerInMaps(leaf);
        VelocityPluginHotloader hotloader = hotloader();

        assertEquals(Set.of("middle", "leaf"), hotloader.dependentsOf("core"));

        Set<Path> dependents = hotloader.unload("core", UnloadReason.HOT_UNLOAD);

        assertEquals(Set.of(temp.resolve("Middle.jar"), temp.resolve("Leaf.jar")), dependents);
        InOrder order = inOrder(internals);
        order.verify(internals).unregisterContainer(leaf);
        order.verify(internals).unregisterContainer(middle);
        order.verify(internals).unregisterContainer(core);
    }

    @Test
    void unloadKeepsGoingAfterAFailingStepAndThenReportsUnloadFailed() throws Exception {
        Object instance = new Object();
        PluginContainer demo = container("demo", instance, List.of(), temp.resolve("Demo.jar"));
        registerInMaps(demo);
        doThrow(new HotloadException(HotloadException.Kind.UNLOAD_FAILED, "maps are gone"))
                .when(internals).unregisterContainer(demo);

        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().unload("demo", UnloadReason.HOT_UNLOAD));

        assertEquals(HotloadException.Kind.UNLOAD_FAILED, failure.kind());
        verify(internals).shutdownContainerExecutor(demo);
        verify(internals).closeClassLoader(demo);
        verify(logger).error(anyString(), eq("unregister container"), isA(HotloadException.class));
    }

    @Test
    void rollbackAfterAFailedInitializeRemovesEverythingThePluginRegistered() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        Object instance = instanceOf(demo);
        CommandMeta meta = mock(CommandMeta.class);
        when(meta.getPlugin()).thenReturn(instance);
        doAnswer(invocation -> {
            doReturn(List.of("demo")).when(commandManager).getAliases();
            when(commandManager.getCommandMeta("demo")).thenReturn(meta);
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "initialize exploded");
        }).when(internals).fireScoped(eq(demo), isA(ProxyInitializeEvent.class), any());

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.LOAD_FAILED, failure.kind());
        InOrder order = inOrder(internals, eventManager, commandManager);
        order.verify(internals).fireScopedCollecting(eq(demo), isA(ProxyShutdownEvent.class), any());
        order.verify(eventManager).unregisterListeners(instance);
        order.verify(commandManager).unregister(meta);
        order.verify(internals).unregisterContainer(demo);
        order.verify(internals).closeClassLoader(demo);
        verify(internals).shutdownContainerExecutor(demo);
        verify(scheduler).tasksByPlugin(instance);
        assertTrue(hotloader().find("demo").isEmpty());
        assertTrue(isEmpty(runtimeDirectory), "the staged copy survived the rollback");
    }

    @Test
    void aPluginWhoseInitializeHandlerFailsIsLoadableAgainImmediately() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        AtomicBoolean firstFire = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstFire.getAndSet(false)) {
                throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                        "ProxyInitializeEvent handler com.example.Demo for demo failed",
                        new IllegalStateException("boom"));
            }
            return null;
        }).when(internals).fireScoped(eq(demo), isA(ProxyInitializeEvent.class), any());
        VelocityPluginHotloader hotloader = hotloader();

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader.load(jar));

        assertEquals(HotloadException.Kind.LOAD_FAILED, failure.kind());
        assertTrue(hotloader.find("demo").isEmpty());
        assertSame(demo, hotloader.load(jar));
    }

    @Test
    void unloadSurvivesAShutdownHandlerThatFails() throws Exception {
        Object instance = new Object();
        PluginContainer demo = container("demo", instance, List.of(), temp.resolve("Demo.jar"));
        registerInMaps(demo);
        when(internals.fireScopedCollecting(eq(demo), isA(ProxyShutdownEvent.class), any()))
                .thenReturn(List.of(new ProxyInternals.HandlerFailure("demo", "com.example.DemoVelocity",
                        new IllegalStateException("shutdown boom"))));

        Set<Path> dependents = hotloader().unload("demo", UnloadReason.HOT_UNLOAD);

        assertTrue(dependents.isEmpty());
        verify(internals).unregisterContainer(demo);
        verify(internals).closeClassLoader(demo);
    }

    @Test
    void rollbackAfterAFailedInstantiateClosesTheClassLoaderAndDeletesTheStagedCopy() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        doThrow(new HotloadException(HotloadException.Kind.LOAD_FAILED, "guice refused the constructor"))
                .when(internals).instantiate(demo);

        HotloadException failure = assertThrows(HotloadException.class, () -> hotloader().load(jar));

        assertEquals(HotloadException.Kind.LOAD_FAILED, failure.kind());
        InOrder order = inOrder(internals);
        order.verify(internals).createContainer(any());
        order.verify(internals).instantiate(demo);
        order.verify(internals).closeClassLoader(demo);
        verify(internals, never()).registerContainer(any());
        assertTrue(isEmpty(runtimeDirectory), "the staged copy survived the rollback");
    }

    @Test
    void aFailedUnloadCarriesTheDependentSourcesOnTheException() throws Exception {
        Path coreJar = temp.resolve("Core.jar");
        Path addonJar = temp.resolve("Addon.jar");
        PluginContainer core = container("core", new Object(), List.of(), coreJar);
        PluginContainer addon = container("addon", new Object(),
                List.of(new PluginDependency("core", "1.0", false)), addonJar);
        registerInMaps(core);
        registerInMaps(addon);
        doThrow(new HotloadException(HotloadException.Kind.UNLOAD_FAILED, "maps are gone"))
                .when(internals).unregisterContainer(core);

        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().unload("core", UnloadReason.HOT_UNLOAD));

        assertEquals(HotloadException.Kind.UNLOAD_FAILED, failure.kind());
        assertEquals(Set.of(addonJar), failure.dependentSources());
        assertTrue(failure.getMessage().contains(addonJar.toString()), failure.getMessage());
    }

    @Test
    void reloadRestoresTheDependentsWhenTheUnloadHalfFails() throws Exception {
        Path coreJar = sourceJar("Core.jar", JarFixtures.descriptor("core", "com.example.Core"));
        Path addonJar = sourceJar("Addon.jar", JarFixtures.descriptor("addon", "com.example.Addon",
                "[{\"id\":\"core\"}]"));
        PluginContainer core = container("core", new Object(), List.of(), coreJar);
        PluginContainer addon = container("addon", new Object(),
                List.of(new PluginDependency("core", "1.0", false)), addonJar);
        pending.put("core", core);
        pending.put("addon", addon);
        registerInMaps(core);
        registerInMaps(addon);
        doThrow(new HotloadException(HotloadException.Kind.UNLOAD_FAILED, "maps are gone"))
                .when(internals).unregisterContainer(core);

        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().reload("core", coreJar));

        assertEquals(HotloadException.Kind.UNLOAD_FAILED, failure.kind());
        verify(internals).registerContainer(addon);
    }

    @Test
    void theLoadedPluginSnapshotUpdatesAfterLoadAndUnload() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader();
        List<PluginContainer> beforeLoad = hotloader.loadedPlugins();

        hotloader.load(jar);

        assertTrue(hotloader.loadedPlugins().contains(demo));
        assertEquals(Optional.of(demo), hotloader.find("demo"));
        assertFalse(beforeLoad.contains(demo));
        assertThrows(UnsupportedOperationException.class, () -> beforeLoad.add(demo));

        hotloader.unload("demo", UnloadReason.HOT_UNLOAD);

        assertFalse(hotloader.loadedPlugins().contains(demo));
        assertTrue(hotloader.find("demo").isEmpty());
    }

    @Test
    void unloadRefusesBileToolsItself() {
        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().unload("biletools", UnloadReason.HOT_UNLOAD));

        assertEquals(HotloadException.Kind.SELF, failure.kind());
    }

    @Test
    void unloadRefusesAPluginThatIsNotLoaded() {
        HotloadException failure = assertThrows(HotloadException.class,
                () -> hotloader().unload("ghost", UnloadReason.HOT_UNLOAD));

        assertEquals(HotloadException.Kind.NOT_LOADED, failure.kind());
    }

    @Test
    void loadRecordsTheOriginJarNotTheStagedCopyAsTheSource() throws Exception {
        Path origin = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        Path stagedCopy = Files.copy(origin, temp.resolve("Demo-stage.jar"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader();

        hotloader.load(stagedCopy, origin);

        assertEquals(Optional.of(origin), hotloader.sourceOf("demo"));
    }

    @Test
    void reloadWithoutASourceStagesFromTheOriginJar() throws Exception {
        Path origin = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        Path stagedCopy = Files.copy(origin, temp.resolve("Demo-stage.jar"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader();
        hotloader.load(stagedCopy, origin);
        Files.delete(stagedCopy);

        hotloader.reload("demo", null);

        ArgumentCaptor<Path> staged = ArgumentCaptor.forClass(Path.class);
        verify(internals, times(2)).createContainer(staged.capture());
        assertArrayEquals(Files.readAllBytes(origin), Files.readAllBytes(staged.getAllValues().get(1)));
        assertEquals(Optional.of(origin), hotloader.sourceOf("demo"));
    }

    @Test
    void reloadFromAStagedCopyKeepsTheOriginJarAsTheSource() throws Exception {
        Path origin = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        Path stagedCopy = Files.copy(origin, temp.resolve("Demo-stage.jar"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader();
        hotloader.load(origin);

        hotloader.reload("demo", stagedCopy, origin);

        assertEquals(Optional.of(origin), hotloader.sourceOf("demo"));
    }

    @Test
    void reloadWithoutASourceReusesTheRecordedSource() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        PluginContainer demo = declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader();
        hotloader.load(jar);

        PluginContainer reloaded = hotloader.reload("demo", null);

        assertSame(demo, reloaded);
        ArgumentCaptor<Path> staged = ArgumentCaptor.forClass(Path.class);
        verify(internals, times(2)).createContainer(staged.capture());
        List<Path> copies = staged.getAllValues();
        assertEquals(2, copies.size());
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(copies.get(1)));
        assertFalse(Files.exists(copies.get(0)));
        assertEquals(Optional.of(jar), hotloader.sourceOf("demo"));
    }

    @Test
    void reloadRestoresDependentsFromTheirSources() throws Exception {
        Path coreJar = sourceJar("Core.jar", JarFixtures.descriptor("core", "com.example.Core"));
        Path addonJar = sourceJar("Addon.jar", JarFixtures.descriptor("addon", "com.example.Addon",
                "[{\"id\":\"core\"}]"));
        PluginContainer core = container("core", new Object(), List.of(), coreJar);
        PluginContainer addon = container("addon", new Object(), List.of(new PluginDependency("core", "1.0", false)), addonJar);
        pending.put("core", core);
        pending.put("addon", addon);
        registerInMaps(core);
        registerInMaps(addon);

        hotloader().reload("core", coreJar);

        InOrder order = inOrder(internals);
        order.verify(internals).unregisterContainer(addon);
        order.verify(internals).unregisterContainer(core);
        order.verify(internals).registerContainer(core);
        order.verify(internals).registerContainer(addon);
    }

    @Test
    void unloadArchivesTheRuntimeCopyWhenArchivingIsEnabled() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader(new HotloadOptions(false, false, true, TIMEOUT));
        hotloader.load(jar);
        Path runtimeCopy = onlyFileIn(runtimeDirectory);

        hotloader.unload("demo", UnloadReason.HOT_UNLOAD);

        assertFalse(Files.exists(runtimeCopy));
        Path archived = onlyFileIn(archiveDirectory);
        assertTrue(archived.getFileName().toString().matches("demo-\\d{8}-\\d{6}\\.jar"), archived.toString());
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(archived));
    }

    @Test
    void unloadDeletesTheRuntimeCopyWhenArchivingIsDisabled() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader(new HotloadOptions(false, false, false, TIMEOUT));
        hotloader.load(jar);
        Path runtimeCopy = onlyFileIn(runtimeDirectory);

        hotloader.unload("demo", UnloadReason.HOT_UNLOAD);

        assertFalse(Files.exists(runtimeCopy));
        assertFalse(Files.exists(archiveDirectory));
    }

    @Test
    void emitsOneTimingLinePerOperationWhenTimingsAreEnabled() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        declare("demo", List.of());
        VelocityPluginHotloader hotloader = hotloader(new HotloadOptions(false, true, false, TIMEOUT));

        hotloader.load(jar);
        hotloader.reload("demo", null);

        List<String> lines = timingLines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).matches("load demo took \\d+ms"), lines.get(0));
        assertTrue(lines.get(1).matches("reload demo took \\d+ms \\(unload=\\d+, load=\\d+, dependents=0\\)"), lines.get(1));
    }

    @Test
    void emitsNoTimingLineWhenTimingsAreDisabled() throws Exception {
        Path jar = sourceJar("Demo.jar", JarFixtures.descriptor("demo", "com.example.Demo"));
        declare("demo", List.of());

        hotloader().load(jar);

        assertTrue(timingLines().isEmpty(), timingLines().toString());
    }

    @Test
    void loadedPluginsHidesTheVelocityVirtualContainerAndFindIsCaseInsensitive() throws Exception {
        registerInMaps(container("velocity", new Object(), List.of(), null));
        PluginContainer demo = container("demo", new Object(), List.of(), temp.resolve("Demo.jar"));
        registerInMaps(demo);
        VelocityPluginHotloader hotloader = hotloader();

        List<String> ids = new ArrayList<>();
        for (PluginContainer container : hotloader.loadedPlugins()) {
            ids.add(container.getDescription().getId());
        }

        assertEquals(List.of("biletools", "demo"), ids);
        assertEquals(Optional.of(demo), hotloader.find("DEMO"));
        assertTrue(hotloader.isSelf(hotloader.find("biletools").orElseThrow()));
        assertFalse(hotloader.isSelf(demo));
        assertSame(internals, hotloader.internals());
    }

    private VelocityPluginHotloader hotloader() {
        return hotloader(new HotloadOptions(false, false, false, TIMEOUT));
    }

    private VelocityPluginHotloader hotloader(HotloadOptions options) {
        return new VelocityPluginHotloader(proxy, logger, dataDirectory, selfInstance, internals, options);
    }

    private List<String> timingLines() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(logger, atLeast(0)).info(captor.capture());
        List<String> lines = new ArrayList<>();
        for (String line : captor.getAllValues()) {
            if (line.contains(" took ")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private Path sourceJar(String fileName, String descriptorJson) throws IOException {
        return JarFixtures.jar(pluginsDirectory, fileName, descriptorJson);
    }

    private PluginContainer declare(String id, List<PluginDependency> dependencies) {
        PluginContainer container = container(id, new Object(), dependencies, null);
        pending.put(id, container);
        return container;
    }

    private PluginContainer container(String id, Object instance, List<PluginDependency> dependencies, Path source) {
        PluginDescription description = mock(PluginDescription.class);
        when(description.getId()).thenReturn(id);
        when(description.getDependencies()).thenReturn(dependencies);
        when(description.getSource()).thenReturn(Optional.ofNullable(source));
        PluginContainer container = mock(PluginContainer.class);
        when(container.getDescription()).thenReturn(description);
        doReturn(Optional.ofNullable(instance)).when(container).getInstance();
        return container;
    }

    private PluginContainer pendingFor(Path staged) {
        String name = staged.getFileName().toString();
        for (Map.Entry<String, PluginContainer> entry : pending.entrySet()) {
            if (name.startsWith(entry.getKey() + "-")) {
                return entry.getValue();
            }
        }
        throw new IllegalStateException("no container declared for " + name);
    }

    private Object instanceOf(PluginContainer container) {
        return container.getInstance().orElseThrow();
    }

    private Object registerInMaps(PluginContainer container) {
        pluginsById.put(container.getDescription().getId(), container);
        Object instance = container.getInstance().orElse(null);
        if (instance != null) {
            pluginInstances.put(instance, container);
        }
        return null;
    }

    private Object removeFromMaps(PluginContainer container) {
        pluginsById.values().removeIf(value -> value == container);
        pluginInstances.values().removeIf(value -> value == container);
        return null;
    }

    private static boolean isEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return true;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.findAny().isEmpty();
        }
    }

    private static Path onlyFileIn(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(directory)) {
            stream.forEach(files::add);
        }
        assertEquals(1, files.size(), files.toString());
        return files.get(0);
    }

    private static ProxyCapabilityReport report() {
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (String key : List.of("plugin-manager.maps", "plugin-loader.candidate", "plugin-loader.create",
                "plugin-loader.module", "classloader.close", "classloader.registry",
                "event-manager.register-internally", "event-manager.scoped-fire", "container.executor",
                "command-manager.unregister")) {
            capabilities.put(key, Boolean.TRUE);
        }
        return new ProxyCapabilityReport(capabilities, List.of());
    }

    private static ProxyCapabilityReport reportWithout(String key) {
        Map<String, Boolean> capabilities = new LinkedHashMap<>(report().capabilities());
        capabilities.put(key, Boolean.FALSE);
        return new ProxyCapabilityReport(capabilities, List.of());
    }
}
