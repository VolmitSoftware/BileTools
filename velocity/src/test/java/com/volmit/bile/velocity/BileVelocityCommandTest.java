package com.volmit.bile.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.proxy.ProxyServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class BileVelocityCommandTest {
    @TempDir
    Path pluginsDirectory;

    private VelocityWatchOrchestrator orchestrator;
    private VelocityPluginHotloader hotloader;
    private ProxyMessages messages;
    private CommandSource source;
    private BileVelocityCommand command;

    @BeforeEach
    public void setUp() {
        orchestrator = mock(VelocityWatchOrchestrator.class);
        hotloader = mock(VelocityPluginHotloader.class);
        messages = new ProxyMessages(mock(ProxyServer.class), mock(Logger.class), true);
        source = mock(CommandSource.class);
        when(source.hasPermission("bile.use")).thenReturn(true);
        command = new BileVelocityCommand(orchestrator, hotloader, messages, pluginsDirectory, "3.0.3-26.2");
    }

    @Test
    public void refusesSourcesWithoutThePermission() {
        when(source.hasPermission("bile.use")).thenReturn(false);

        assertFalse(command.hasPermission(invocation("list")));
        assertEquals(List.of(), command.suggest(invocation("re")));
        verifyNoInteractions(orchestrator);
    }

    @Test
    public void loadRoutesToTheOrchestratorAndSendsTheResult() {
        when(orchestrator.manualLoad("Gloss.jar"))
                .thenReturn(CompletableFuture.completedFuture("Loaded gloss from Gloss.jar."));

        command.execute(invocation("load", "Gloss.jar"));

        verify(orchestrator).manualLoad("Gloss.jar");
        verify(source).sendMessage(messages.success("Loaded gloss from Gloss.jar."));
    }

    @Test
    public void unloadRoutesToTheOrchestratorAndSendsTheResult() {
        when(orchestrator.manualUnload("gloss"))
                .thenReturn(CompletableFuture.completedFuture("Unloaded gloss."));

        command.execute(invocation("unload", "gloss"));

        verify(orchestrator).manualUnload("gloss");
        verify(source).sendMessage(messages.success("Unloaded gloss."));
    }

    @Test
    public void reloadRoutesToTheOrchestratorAndSendsTheResult() {
        when(orchestrator.manualReload("gloss"))
                .thenReturn(CompletableFuture.completedFuture("Reloaded gloss."));

        command.execute(invocation("reload", "gloss"));

        verify(orchestrator).manualReload("gloss");
        verify(source).sendMessage(messages.success("Reloaded gloss."));
    }

    @Test
    public void sendsFailedOperationsInRed() {
        when(orchestrator.manualReload("gloss"))
                .thenReturn(CompletableFuture.failedFuture(
                        new HotloadException(HotloadException.Kind.NOT_LOADED, "No loaded plugin with id gloss.")));

        command.execute(invocation("reload", "gloss"));

        verify(source).sendMessage(messages.failure("No loaded plugin with id gloss."));
    }

    @Test
    public void bareCommandAndHelpPrintTheUsage() {
        command.execute(invocation());
        command.execute(invocation("help"));

        verify(source, times(2))
                .sendMessage(messages.info("/bile load <jar-name-or-id>"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    public void versionPrintsThePluginVersion() {
        command.execute(invocation("version"));

        verify(source).sendMessage(messages.info("BileTools 3.0.3-26.2 on Velocity."));
        verifyNoInteractions(orchestrator);
    }

    @Test
    public void listPrintsLoadedPluginsAndWatcherState() {
        PluginContainer gloss = container("gloss", "1.2.3");
        when(hotloader.loadedPlugins()).thenReturn(List.of(gloss));
        when(hotloader.isSelf(gloss)).thenReturn(false);
        when(orchestrator.snapshot())
                .thenReturn(new VelocityWatchOrchestrator.OrchestratorSnapshot(4, Set.of(), 2L, 0L, true));

        command.execute(invocation("list"));

        verify(source).sendMessage(messages.info("Loaded proxy plugins (1):"));
        verify(source).sendMessage(messages.info("  gloss 1.2.3"));
        verify(source).sendMessage(messages.info("Watching 4 jars, 0 dirty, 2 reloads applied."));
    }

    @Test
    public void missingArgumentsPrintTheSubcommandUsage() {
        command.execute(invocation("reload"));

        verify(source).sendMessage(messages.failure("Usage: /bile reload <id>"));
        verify(orchestrator, never()).manualReload(anyString());
    }

    @Test
    public void suggestsSubcommands() {
        assertEquals(List.of("help", "list", "load", "reload", "unload", "version"),
                command.suggest(invocation()));
        assertEquals(List.of("reload"), command.suggest(invocation("rel")));
    }

    @Test
    public void suggestsLoadedIdsWithoutSelfForUnloadAndReload() {
        PluginContainer gloss = container("gloss", "1.2.3");
        PluginContainer self = container("biletools", "3.0.3-26.2");
        when(hotloader.loadedPlugins()).thenReturn(List.of(gloss, self));
        when(hotloader.isSelf(gloss)).thenReturn(false);
        when(hotloader.isSelf(self)).thenReturn(true);

        assertEquals(List.of("gloss"), command.suggest(invocation("unload", "")));
        assertEquals(List.of("gloss"), command.suggest(invocation("reload", "gl")));
    }

    @Test
    public void suggestsJarNamesForLoad() throws Exception {
        Files.writeString(pluginsDirectory.resolve("Gloss.jar"), "x", StandardCharsets.UTF_8);
        Files.writeString(pluginsDirectory.resolve("notes.txt"), "x", StandardCharsets.UTF_8);

        List<String> suggestions = command.suggest(invocation("load", ""));

        assertTrue(suggestions.contains("Gloss.jar"));
        assertFalse(suggestions.contains("notes.txt"));
    }

    private PluginContainer container(String id, String version) {
        PluginContainer container = mock(PluginContainer.class);
        PluginDescription description = mock(PluginDescription.class);
        when(container.getDescription()).thenReturn(description);
        when(description.getId()).thenReturn(id);
        when(description.getVersion()).thenReturn(Optional.of(version));
        return container;
    }

    private SimpleCommand.Invocation invocation(String... arguments) {
        SimpleCommand.Invocation invocation = mock(SimpleCommand.Invocation.class);
        when(invocation.source()).thenReturn(source);
        when(invocation.arguments()).thenReturn(arguments);
        return invocation;
    }
}
