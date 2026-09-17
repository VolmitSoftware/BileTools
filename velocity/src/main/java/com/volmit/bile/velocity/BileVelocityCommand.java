package com.volmit.bile.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.PluginContainer;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

public final class BileVelocityCommand implements SimpleCommand {
    private static final List<String> SUBCOMMANDS = List.of("help", "list", "load", "reload", "unload", "version");
    private static final List<String> USAGE = List.of(
            "/bile load <jar-name-or-id>",
            "/bile unload <id>",
            "/bile reload <id>",
            "/bile list",
            "/bile version");

    private final VelocityWatchOrchestrator orchestrator;
    private final VelocityPluginHotloader hotloader;
    private final ProxyMessages messages;
    private final Path pluginsDirectory;
    private final String version;

    public BileVelocityCommand(VelocityWatchOrchestrator orchestrator,
                               VelocityPluginHotloader hotloader,
                               ProxyMessages messages,
                               Path pluginsDirectory,
                               String version) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.hotloader = Objects.requireNonNull(hotloader, "hotloader");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.pluginsDirectory = Objects.requireNonNull(pluginsDirectory, "pluginsDirectory");
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(ProxyMessages.PERMISSION);
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            sendUsage(source);
            return;
        }

        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "load" -> dispatch(source, arguments, "load <jar-name-or-id>", orchestrator::manualLoad);
            case "unload" -> dispatch(source, arguments, "unload <id>", orchestrator::manualUnload);
            case "reload" -> dispatch(source, arguments, "reload <id>", orchestrator::manualReload);
            case "list" -> sendList(source);
            case "version" -> messages.send(source, messages.info("BileTools " + version + " on Velocity."));
            default -> sendUsage(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (!invocation.source().hasPermission(ProxyMessages.PERMISSION)) {
            return List.of();
        }

        String[] arguments = invocation.arguments();
        if (arguments.length <= 1) {
            return filter(SUBCOMMANDS, arguments.length == 0 ? "" : arguments[0]);
        }
        if (arguments.length > 2) {
            return List.of();
        }
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "load" -> filter(jarNames(), arguments[1]);
            case "unload", "reload" -> filter(loadedIds(), arguments[1]);
            default -> List.of();
        };
    }

    private void dispatch(CommandSource source,
                          String[] arguments,
                          String usage,
                          Function<String, CompletableFuture<String>> operation) {
        if (arguments.length < 2 || arguments[1].isBlank()) {
            messages.send(source, messages.failure("Usage: /bile " + usage));
            return;
        }

        operation.apply(arguments[1]).whenComplete((message, failure) -> {
            if (failure != null) {
                messages.send(source, messages.failure(describe(failure)));
                return;
            }
            messages.send(source, messages.success(message));
        });
    }

    private void sendList(CommandSource source) {
        List<PluginContainer> loaded = hotloader.loadedPlugins();
        messages.send(source, messages.info("Loaded proxy plugins (" + loaded.size() + "):"));
        VelocityWatchOrchestrator.OrchestratorSnapshot snapshot = orchestrator.snapshot();
        Set<String> dirty = snapshot.dirtyPlugins();
        for (PluginContainer container : loaded) {
            String id = container.getDescription().getId();
            StringBuilder line = new StringBuilder("  ").append(id).append(' ')
                    .append(container.getDescription().getVersion().orElse("unknown"));
            if (hotloader.isSelf(container)) {
                line.append(" (this plugin)");
            }
            if (dirty.contains(id.toLowerCase(Locale.ROOT))) {
                line.append(" (dirty)");
            }
            messages.send(source, messages.info(line.toString()));
        }
        messages.send(source, messages.info("Watching " + snapshot.watchedJars() + " jars, "
                + dirty.size() + " dirty, " + snapshot.reloadsTotal() + " reloads applied."));
    }

    private void sendUsage(CommandSource source) {
        for (String line : USAGE) {
            messages.send(source, messages.info(line));
        }
    }

    private List<String> loadedIds() {
        List<PluginContainer> loaded = hotloader.loadedPlugins();
        List<String> ids = new ArrayList<>(loaded.size());
        for (PluginContainer container : loaded) {
            if (!hotloader.isSelf(container)) {
                ids.add(container.getDescription().getId());
            }
        }
        return ids;
    }

    private List<String> jarNames() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            for (Path path : stream) {
                names.add(path.getFileName().toString());
            }
        } catch (IOException exception) {
            return List.of();
        }
        return names;
    }

    private List<String> filter(List<String> candidates, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>(candidates.size());
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(normalized)) {
                matches.add(candidate);
            }
        }
        return List.copyOf(matches);
    }

    private String describe(Throwable failure) {
        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
