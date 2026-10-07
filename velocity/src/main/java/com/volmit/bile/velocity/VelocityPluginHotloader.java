package com.volmit.bile.velocity;

import com.volmit.bile.velocity.api.ReloadParticipant;
import com.volmit.bile.velocity.api.ReloadPreparation;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.meta.PluginDependency;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

public final class VelocityPluginHotloader {
    private static final String VELOCITY_ID = "velocity";
    private static final String RUNTIME_DIRECTORY = "runtime-plugins";
    private static final String ARCHIVE_DIRECTORY = "archive";
    private static final DateTimeFormatter ARCHIVE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Duration ROLLBACK_EVENT_TIMEOUT = Duration.ofSeconds(10L);
    private static final List<String> LOAD_CAPABILITIES = ProxyCapabilityReport.LOAD_KEYS;
    private static final List<String> UNLOAD_CAPABILITIES = ProxyCapabilityReport.UNLOAD_KEYS;

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path runtimeDirectory;
    private final Path archiveDirectory;
    private final Path recoveryDirectory;
    private final Map<String, Path> recoveryCopies = new ConcurrentHashMap<>();
    private final VelocityOwnedResources ownedResources;
    private final Object selfInstance;
    private final ProxyInternals internals;
    private final HotloadOptions options;
    private final Map<String, Path> trackedSources = new LinkedHashMap<>();
    private final Map<String, Path> runtimeCopies = new LinkedHashMap<>();
    private volatile List<PluginContainer> loadedSnapshot = List.of();
    private volatile Map<String, PluginContainer> snapshotById = Map.of();
    private int operationDepth;

    public VelocityPluginHotloader(ProxyServer proxy, Logger logger, Path dataDirectory, Object selfInstance,
                                   ProxyInternals internals, HotloadOptions options) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.selfInstance = Objects.requireNonNull(selfInstance, "selfInstance");
        this.internals = Objects.requireNonNull(internals, "internals");
        this.options = Objects.requireNonNull(options, "options");
        Path data = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.runtimeDirectory = data.resolve(RUNTIME_DIRECTORY);
        this.archiveDirectory = data.resolve(ARCHIVE_DIRECTORY);
        this.recoveryDirectory = data.resolve("recovery-plugins");
        this.ownedResources = new VelocityOwnedResources(proxy);
        internals.pluginsDirectory(data.getParent());
        refreshSnapshot();
        captureStartupCopies();
    }

    public VelocityOwnedResources ownedResources() {
        return ownedResources;
    }

    public ProxyInternals internals() {
        return internals;
    }

    public Optional<PluginContainer> find(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(snapshotById.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<PluginContainer> loadedPlugins() {
        return loadedSnapshot;
    }

    public boolean isSelf(PluginContainer container) {
        Objects.requireNonNull(container, "container");
        return container.getInstance().orElse(null) == selfInstance;
    }

    public Set<String> dependentsOf(String id) {
        Objects.requireNonNull(id, "id");
        Set<String> collected = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(id);
        while (!pending.isEmpty()) {
            for (String dependent : directDependentsOf(pending.poll())) {
                if (dependent.equalsIgnoreCase(id) || !collected.add(dependent)) {
                    continue;
                }
                pending.add(dependent);
            }
        }
        return collected;
    }

    public PluginContainer load(Path sourceJar) throws HotloadException {
        return load(sourceJar, sourceJar);
    }

    public PluginContainer load(Path sourceJar, Path originJar) throws HotloadException {
        Objects.requireNonNull(sourceJar, "sourceJar");
        Objects.requireNonNull(originJar, "originJar");
        requireCapabilities(LOAD_CAPABILITIES);
        VelocityPluginDescriptor descriptor = readDescriptor(sourceJar);
        String id = descriptor.id();
        if (id.equals(selfId())) {
            throw new HotloadException(HotloadException.Kind.SELF,
                    "BileTools cannot load itself on the proxy; restart the proxy instead");
        }
        if (find(id).isPresent()) {
            throw new HotloadException(HotloadException.Kind.ALREADY_LOADED, id + " is already loaded");
        }
        List<String> missing = missingDependencies(descriptor);
        if (!missing.isEmpty()) {
            throw new HotloadException(HotloadException.Kind.MISSING_DEPENDENCY,
                    id + " needs plugins that are not loaded: " + String.join(", ", missing));
        }
        long started = System.nanoTime();
        operationDepth++;
        try {
            Path runtimeCopy = stage(sourceJar, id);
            PluginContainer container = createRegisterAndStart(id, runtimeCopy);
            trackedSources.put(id, originJar);
            runtimeCopies.put(id, runtimeCopy);
            try {
                retainRecoveryCopy(id, runtimeCopy);
            } catch (HotloadException failure) {
                unloadSingle(container, UnloadReason.HOT_RELOAD);
                throw failure;
            }
            logTiming("load " + id + " took " + elapsedMillis(started) + "ms");
            return container;
        } finally {
            operationDepth--;
        }
    }

    public Set<Path> unload(String id, UnloadReason reason) throws HotloadException {
        Objects.requireNonNull(reason, "reason");
        requireCapabilities(UNLOAD_CAPABILITIES);
        List<PluginContainer> group = unloadOrder(id);
        String resolvedId = group.get(group.size() - 1).getDescription().getId();
        prepareGroup(group, reason);
        Set<Path> dependents = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        long started = System.nanoTime();
        operationDepth++;
        try {
            for (PluginContainer container : group) {
                String memberId = container.getDescription().getId();
                if (!memberId.equalsIgnoreCase(resolvedId)) {
                    sourceOf(memberId).ifPresent(dependents::add);
                }
                failures.addAll(unloadSingle(container, reason));
            }
            logTiming("unload " + id + " took " + elapsedMillis(started) + "ms (dependents=" + dependents.size() + ")");
            if (!failures.isEmpty()) {
                throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                        id + " did not unload cleanly: " + String.join(", ", failures)
                                + describeDependents(dependents), null, dependents);
            }
            return dependents;
        } finally {
            operationDepth--;
        }
    }

    public PluginContainer reload(String id, Path sourceJar) throws HotloadException {
        return reload(id, sourceJar, sourceJar);
    }

    public PluginContainer reload(String id, Path sourceJar, Path originJar) throws HotloadException {
        requireCapabilities(LOAD_CAPABILITIES);
        requireCapabilities(UNLOAD_CAPABILITIES);
        List<PluginContainer> group = unloadOrder(id);
        String resolvedId = group.get(group.size() - 1).getDescription().getId();
        Path origin = originJar != null ? originJar : sourceOf(resolvedId).orElseThrow(() ->
                new HotloadException(HotloadException.Kind.LOAD_FAILED, "no source jar is recorded for " + resolvedId));
        Path candidate = stage(sourceJar == null ? origin : sourceJar, resolvedId);
        List<RecoveryEntry> recovery = new ArrayList<>(group.size());
        long started = System.nanoTime();
        operationDepth++;
        try {
            preflightReplacement(resolvedId, candidate);
            for (PluginContainer container : group) {
                String memberId = container.getDescription().getId();
                Path previous = recoveryCopies.get(memberId);
                if (previous == null || !Files.isRegularFile(previous)) {
                    throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                            "no verified running-version copy for " + memberId + "; restart before reloading");
                }
                Path source = sourceOf(memberId).orElseThrow(() -> new HotloadException(
                        HotloadException.Kind.LOAD_FAILED, "no source jar for " + memberId));
                recovery.add(new RecoveryEntry(memberId, stage(previous, memberId), source, container));
            }
            prepareGroup(group, UnloadReason.HOT_RELOAD);
            try {
                for (PluginContainer container : group) {
                    List<String> failures = unloadSingle(container, UnloadReason.HOT_RELOAD);
                    if (!failures.isEmpty()) {
                        throw new HotloadException(HotloadException.Kind.UNLOAD_FAILED,
                                container.getDescription().getId() + " did not unload cleanly: " + String.join(", ", failures));
                    }
                }
                PluginContainer replacement = load(candidate, origin);
                for (int index = recovery.size() - 2; index >= 0; index--) {
                    RecoveryEntry entry = recovery.get(index);
                    load(entry.copy(), entry.origin());
                }
                logTiming("reload " + resolvedId + " took " + elapsedMillis(started) + "ms (group=" + group.size() + ")");
                return replacement;
            } catch (HotloadException | RuntimeException failure) {
                List<String> recoveryFailures = recoverGroup(recovery);
                for (RecoveryEntry entry : recovery) {
                    try {
                        retainRecoveryCopy(entry.id(), entry.copy());
                    } catch (HotloadException retentionFailure) {
                        logger.error("Cannot preserve recovery jar for {}", entry.id(), retentionFailure);
                        recoveryFailures.add(entry.id() + " recovery jar could not be retained");
                    }
                }
                String outcome = recoveryFailures.isEmpty() ? "; previous dependency group restored"
                        : "; recovery incomplete: " + String.join(", ", recoveryFailures);
                throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                        "reload of " + resolvedId + " failed" + outcome, failure);
            }
        } finally {
            deleteQuietly(candidate);
            for (RecoveryEntry entry : recovery) {
                deleteQuietly(entry.copy());
            }
            operationDepth--;
        }
    }

    public List<String> inspect(String id) throws HotloadException {
        PluginContainer container = find(id).orElseThrow(() -> new HotloadException(
                HotloadException.Kind.NOT_LOADED, id + " is not loaded"));
        Object instance = container.getInstance().orElse(null);
        List<String> lines = new ArrayList<>();
        lines.add(container.getDescription().getId() + " " + container.getDescription().getVersion().orElse("unknown"));
        lines.add("Dependencies: " + container.getDescription().getDependencies());
        lines.add("Affected dependents: " + dependentsOf(id));
        lines.add("Recovery copy: " + (recoveryCopies.containsKey(container.getDescription().getId()) ? "available" : "unavailable"));
        lines.add("Cooperative preparation: " + (instance instanceof ReloadParticipant));
        lines.add("Owned commands: " + ownedCommandAliases(container, instance));
        lines.add("Scheduled tasks: " + (instance == null ? 0 : proxy.getScheduler().tasksByPlugin(instance).size()));
        lines.addAll(ownedResources.inspect(container));
        lines.add(internals.report().summary());
        return List.copyOf(lines);
    }

    public Optional<Path> sourceOf(String id) {
        Objects.requireNonNull(id, "id");
        Path tracked = trackedSources.get(id.toLowerCase(Locale.ROOT));
        if (tracked != null) {
            return Optional.of(tracked);
        }
        PluginContainer container = find(id).orElse(null);
        return container == null ? Optional.empty() : container.getDescription().getSource();
    }

    private void captureStartupCopies() {
        for (PluginContainer container : loadedPlugins()) {
            if (isSelf(container)) {
                continue;
            }
            container.getDescription().getSource().ifPresent(source -> {
                try {
                    retainRecoveryCopy(container.getDescription().getId(), source);
                } catch (HotloadException failure) {
                    logger.warn("Cannot preserve startup jar for {}", container.getDescription().getId(), failure);
                }
            });
        }
    }

    private void retainRecoveryCopy(String id, Path source) throws HotloadException {
        Path copy = recoveryDirectory.resolve(id + "-" + UUID.randomUUID() + ".jar");
        try {
            Files.createDirectories(recoveryDirectory);
            Files.copy(source, copy);
            Path previous = recoveryCopies.put(id, copy);
            if (previous != null) {
                deleteQuietly(previous);
            }
        } catch (IOException failure) {
            deleteQuietly(copy);
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "cannot preserve running jar for " + id, failure);
        }
    }

    private void preflightReplacement(String id, Path candidate) throws HotloadException {
        VelocityPluginDescriptor descriptor = readDescriptor(candidate);
        if (!descriptor.id().equals(id)) {
            throw new HotloadException(HotloadException.Kind.INVALID_DESCRIPTOR,
                    "replacement id " + descriptor.id() + " does not match " + id);
        }
        List<String> missing = missingDependencies(descriptor);
        if (!missing.isEmpty()) {
            throw new HotloadException(HotloadException.Kind.MISSING_DEPENDENCY,
                    id + " needs plugins that are not loaded: " + String.join(", ", missing));
        }
        Set<String> dependents = dependentsOf(id);
        for (String dependency : descriptor.requiredDependencies()) {
            PluginContainer target = find(dependency).orElse(null);
            if (target != null && dependents.contains(target.getDescription().getId())) {
                throw new HotloadException(HotloadException.Kind.MISSING_DEPENDENCY,
                        "replacement creates a dependency cycle through " + dependency);
            }
        }
    }

    private List<PluginContainer> unloadOrder(String id) throws HotloadException {
        PluginContainer target = find(id).orElseThrow(() -> new HotloadException(
                HotloadException.Kind.NOT_LOADED, id + " is not loaded"));
        List<PluginContainer> ordered = new ArrayList<>();
        visitUnload(target, new LinkedHashSet<>(), new LinkedHashSet<>(), ordered);
        return ordered;
    }

    private void visitUnload(PluginContainer container, Set<String> visiting, Set<String> visited,
                             List<PluginContainer> ordered) throws HotloadException {
        String id = container.getDescription().getId();
        if (visited.contains(id)) {
            return;
        }
        if (isSelf(container)) {
            throw new HotloadException(HotloadException.Kind.SELF, "the operation would unload BileTools; restart instead");
        }
        if (!visiting.add(id)) {
            throw new HotloadException(HotloadException.Kind.MISSING_DEPENDENCY, "dependency cycle involving " + id);
        }
        for (String dependent : directDependentsOf(id)) {
            visitUnload(find(dependent).orElseThrow(), visiting, visited, ordered);
        }
        visiting.remove(id);
        visited.add(id);
        ordered.add(container);
    }

    private void prepareGroup(List<PluginContainer> group, UnloadReason reason) throws HotloadException {
        List<ReloadParticipant> prepared = new ArrayList<>();
        try {
            for (PluginContainer container : group) {
                if (!(container.getInstance().orElse(null) instanceof ReloadParticipant participant)) {
                    continue;
                }
                prepared.add(participant);
                CompletionStage<ReloadPreparation> stage = Objects.requireNonNull(participant.prepareReload(reason));
                ReloadPreparation result = awaitPreparation(stage);
                if (!result.ready()) {
                    throw new HotloadException(HotloadException.Kind.PREPARATION_REFUSED,
                            container.getDescription().getId() + " refused unloading: " + result.reason());
                }
            }
        } catch (Exception failure) {
            for (int index = prepared.size() - 1; index >= 0; index--) {
                try {
                    awaitPreparation(prepared.get(index).cancelReload());
                } catch (Exception cancellation) {
                    logger.error("Cannot resume a plugin after cancelled reload preparation", cancellation);
                    failure.addSuppressed(cancellation);
                }
            }
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (failure instanceof HotloadException hotload) {
                throw hotload;
            }
            throw new HotloadException(HotloadException.Kind.PREPARATION_REFUSED,
                    "reload preparation failed; dependency group was not unloaded", failure);
        }
    }

    private <T> T awaitPreparation(CompletionStage<T> stage) throws Exception {
        try {
            return stage.toCompletableFuture().get(options.lifecycleTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            logger.error("Reload preparation exceeded {}; waiting for completion before changing plugin state", options.lifecycleTimeout());
            stage.handle((result, failure) -> null).toCompletableFuture().join();
            throw timeout;
        }
    }

    private List<String> unloadSingle(PluginContainer container, UnloadReason reason) {
        String id = container.getDescription().getId();
        List<String> failures = new ArrayList<>();
        if (container.getInstance().orElse(null) instanceof ReloadParticipant participant) {
            runStep(failures, "commit reload preparation", () -> awaitPreparation(participant.commitReload(reason)));
        }
        failures.addAll(teardown(new TeardownPlan(id, container, runtimeCopies.get(id),
                LoadStage.INITIALIZED, options.lifecycleTimeout(), options.archivePlugins())));
        trackedSources.remove(id);
        runtimeCopies.remove(id);
        return failures;
    }

    private List<String> recoverGroup(List<RecoveryEntry> recovery) {
        List<String> failures = new ArrayList<>();
        List<PluginContainer> replacements = new ArrayList<>();
        for (RecoveryEntry entry : recovery) {
            PluginContainer current = find(entry.id()).orElse(null);
            if (current != null && current != entry.original()) {
                replacements.add(current);
            }
        }
        try {
            prepareGroup(replacements, UnloadReason.HOT_RELOAD);
        } catch (HotloadException failure) {
            logger.error("Cannot prepare replacement plugins for recovery", failure);
            failures.add(failure.getMessage());
            return failures;
        }
        for (RecoveryEntry entry : recovery) {
            PluginContainer current = find(entry.id()).orElse(null);
            if (current != null) {
                failures.addAll(unloadSingle(current, UnloadReason.HOT_RELOAD));
            }
        }
        for (int index = recovery.size() - 1; index >= 0; index--) {
            RecoveryEntry entry = recovery.get(index);
            try {
                load(entry.copy(), entry.origin());
            } catch (HotloadException failure) {
                logger.error("Could not restore previous version of {}", entry.id(), failure);
                failures.add(entry.id() + ": " + failure.getMessage());
            }
        }
        return failures;
    }

    private List<String> ownedCommandAliases(PluginContainer container, Object instance) {
        List<String> aliases = new ArrayList<>();
        for (String alias : proxy.getCommandManager().getAliases()) {
            CommandMeta meta = proxy.getCommandManager().getCommandMeta(alias);
            if (meta != null && (meta.getPlugin() == instance || meta.getPlugin() == container)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    private PluginContainer createRegisterAndStart(String id, Path runtimeCopy) throws HotloadException {
        PluginContainer container = null;
        Object instance;
        try {
            container = internals.createContainer(runtimeCopy);
            instance = internals.instantiate(container);
        } catch (HotloadException e) {
            rollback(id, container, runtimeCopy, LoadStage.CREATED);
            throw e;
        }
        LoadStage stage = LoadStage.CREATED;
        try {
            internals.registerContainer(container);
            stage = LoadStage.REGISTERED;
            refreshSnapshot();
            internals.registerListenersInternally(container, instance);
            stage = LoadStage.INITIALIZED;
            internals.fireScoped(container, new ProxyInitializeEvent(), options.lifecycleTimeout());
            if (options.healthCheck()) {
                verifyHealth(id, container, instance);
            }
            return container;
        } catch (HotloadException e) {
            rollback(id, container, runtimeCopy, stage);
            throw e;
        } catch (RuntimeException e) {
            rollback(id, container, runtimeCopy, stage);
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED, "loading " + id + " failed", e);
        }
    }

    private void verifyHealth(String id, PluginContainer container, Object instance) throws HotloadException {
        if (find(id).isEmpty()) {
            throw new HotloadException(HotloadException.Kind.HEALTH_FAILED, id + " is not registered after loading");
        }
        Map<Object, PluginContainer> instances = internals.pluginInstances();
        if (instances == null || instances.get(instance) != container) {
            throw new HotloadException(HotloadException.Kind.HEALTH_FAILED,
                    id + " instance is not registered after loading");
        }
        if (internals.classLoaderOf(container).isEmpty()) {
            throw new HotloadException(HotloadException.Kind.HEALTH_FAILED, id + " has no class loader after loading");
        }
    }

    private void rollback(String id, PluginContainer container, Path runtimeCopy, LoadStage stage) {
        if (container == null) {
            deleteQuietly(runtimeCopy);
            return;
        }
        teardown(new TeardownPlan(id, container, runtimeCopy, stage, ROLLBACK_EVENT_TIMEOUT, false));
    }

    List<String> teardown(TeardownPlan plan) {
        PluginContainer container = plan.container();
        Object instance = container.getInstance().orElse(null);
        List<String> failures = new ArrayList<>();
        if (plan.stage() == LoadStage.INITIALIZED) {
            runStep(failures, "scoped ProxyShutdownEvent", () -> {
                List<ProxyInternals.HandlerFailure> handlers = internals.fireScopedCollecting(
                        container, new ProxyShutdownEvent(), plan.eventTimeout());
                for (ProxyInternals.HandlerFailure handler : handlers) {
                    failures.add("shutdown handler " + handler.handler());
                }
            });
        }
        if (plan.stage() != LoadStage.CREATED) {
            if (instance != null) {
                runStep(failures, "unregister listeners", () -> proxy.getEventManager().unregisterListeners(instance));
                runStep(failures, "cancel scheduled tasks", () -> cancelTasks(instance));
                runStep(failures, "unregister commands", () -> unregisterCommands(container, instance));
            }
            runStep(failures, "unregister container", () -> internals.unregisterContainer(container));
        }
        runStep(failures, "event caches", () -> internals.removeOwnedEventCaches(container));
        runStep(failures, "owned registrations", () -> ownedResources.release(container));
        runStep(failures, "packet registrations", () -> internals.removeOwnedPackets(container));
        runStep(failures, "shut down executor", () -> internals.shutdownContainerExecutor(container));
        runStep(failures, "close class loader", () -> internals.closeClassLoader(container));
        runStep(failures, "remove runtime copy", () -> removeRuntimeCopy(plan));
        refreshSnapshot();
        return failures;
    }

    private void cancelTasks(Object instance) {
        Collection<ScheduledTask> tasks = proxy.getScheduler().tasksByPlugin(instance);
        if (tasks == null) {
            return;
        }
        for (ScheduledTask task : tasks) {
            task.cancel();
        }
    }

    private void unregisterCommands(PluginContainer container, Object instance) {
        CommandManager commands = proxy.getCommandManager();
        Collection<String> aliases = commands.getAliases();
        if (aliases == null) {
            return;
        }
        Set<CommandMeta> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String alias : new ArrayList<>(aliases)) {
            CommandMeta meta = commands.getCommandMeta(alias);
            if (meta == null) {
                continue;
            }
            Object owner = meta.getPlugin();
            if (owner == instance || owner == container) {
                owned.add(meta);
            }
        }
        for (CommandMeta meta : owned) {
            commands.unregister(meta);
        }
    }

    private void removeRuntimeCopy(TeardownPlan plan) throws IOException {
        Path copy = plan.runtimeCopy();
        if (copy == null || !Files.exists(copy)) {
            return;
        }
        if (!plan.archiveRuntimeCopy()) {
            Files.deleteIfExists(copy);
            return;
        }
        Files.createDirectories(archiveDirectory);
        Path archived = archiveDirectory.resolve(plan.id() + "-" + ARCHIVE_STAMP.format(LocalDateTime.now()) + ".jar");
        Files.move(copy, archived, StandardCopyOption.REPLACE_EXISTING);
    }

    private String describeDependents(Set<Path> dependentSources) {
        if (dependentSources.isEmpty()) {
            return "";
        }
        List<String> paths = new ArrayList<>(dependentSources.size());
        for (Path source : dependentSources) {
            paths.add(source.toString());
        }
        return "; dependents left unloaded: " + String.join(", ", paths);
    }

    private void refreshSnapshot() {
        Collection<PluginContainer> live = allContainers();
        List<PluginContainer> loaded = new ArrayList<>(live.size());
        Map<String, PluginContainer> byId = new LinkedHashMap<>();
        for (PluginContainer container : live) {
            PluginDescription description = container.getDescription();
            String id = description.getId();
            byId.putIfAbsent(id.toLowerCase(Locale.ROOT), container);
            for (String provided : internals.providedIds(description)) {
                byId.putIfAbsent(provided.toLowerCase(Locale.ROOT), container);
            }
            if (!VELOCITY_ID.equals(id)) {
                loaded.add(container);
            }
        }
        loadedSnapshot = List.copyOf(loaded);
        snapshotById = Map.copyOf(byId);
    }

    private Path stage(Path sourceJar, String id) throws HotloadException {
        try {
            Files.createDirectories(runtimeDirectory);
            Path target = runtimeDirectory.resolve(id + "-" + UUID.randomUUID() + ".jar");
            Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            throw new HotloadException(HotloadException.Kind.LOAD_FAILED,
                    "cannot stage a runtime copy of " + sourceJar.getFileName(), e);
        }
    }

    private VelocityPluginDescriptor readDescriptor(Path sourceJar) throws HotloadException {
        try {
            return VelocityPluginDescriptor.read(sourceJar);
        } catch (IOException e) {
            throw new HotloadException(HotloadException.Kind.INVALID_DESCRIPTOR,
                    "cannot read velocity-plugin.json from " + sourceJar.getFileName(), e);
        }
    }

    private List<String> missingDependencies(VelocityPluginDescriptor descriptor) {
        List<String> missing = new ArrayList<>();
        for (String dependency : descriptor.requiredDependencies()) {
            if (find(dependency).isEmpty()) {
                missing.add(dependency);
            }
        }
        return missing;
    }

    private void requireCapabilities(List<String> keys) throws HotloadException {
        ProxyCapabilityReport capabilities = internals.report();
        if (capabilities == null) {
            throw new HotloadException(HotloadException.Kind.UNSUPPORTED_CAPABILITY,
                    "proxy internals were never resolved");
        }
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!capabilities.supports(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new HotloadException(HotloadException.Kind.UNSUPPORTED_CAPABILITY,
                    "this proxy build does not expose: " + String.join(", ", missing));
        }
    }

    private String selfId() {
        for (PluginContainer container : loadedSnapshot) {
            if (container.getInstance().orElse(null) == selfInstance) {
                return container.getDescription().getId();
            }
        }
        return null;
    }

    private Collection<PluginContainer> allContainers() {
        Optional<Collection<PluginContainer>> live = internals.pluginList();
        if (live != null && live.isPresent()) {
            return new ArrayList<>(live.get());
        }
        Map<String, PluginContainer> byId = internals.pluginsById();
        if (byId == null) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(byId.values()));
    }

    private List<String> directDependentsOf(String id) {
        Set<String> aliases = aliasesOf(id);
        List<String> dependents = new ArrayList<>();
        for (PluginContainer container : loadedPlugins()) {
            PluginDescription description = container.getDescription();
            if (aliases.contains(description.getId().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (dependsOnAny(description, aliases)) {
                dependents.add(description.getId());
            }
        }
        return dependents;
    }

    private boolean dependsOnAny(PluginDescription description, Set<String> aliases) {
        Collection<PluginDependency> dependencies = description.getDependencies();
        if (dependencies == null) {
            return false;
        }
        for (PluginDependency dependency : dependencies) {
            if (aliases.contains(dependency.getId().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> aliasesOf(String id) {
        Set<String> aliases = new LinkedHashSet<>();
        aliases.add(id.toLowerCase(Locale.ROOT));
        PluginContainer container = find(id).orElse(null);
        if (container == null) {
            return aliases;
        }
        PluginDescription description = container.getDescription();
        aliases.add(description.getId().toLowerCase(Locale.ROOT));
        for (String provided : internals.providedIds(description)) {
            aliases.add(provided.toLowerCase(Locale.ROOT));
        }
        return aliases;
    }

    private void runStep(List<String> failures, String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            logger.error("teardown step '{}' failed", name, t);
            failures.add(name);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.error("cannot delete the runtime copy {}", path, e);
        }
    }

    private void logTiming(String line) {
        if (options.logTimings() && operationDepth == 1) {
            logger.info(line);
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private record RecoveryEntry(String id, Path copy, Path origin, PluginContainer original) {
    }

    enum LoadStage {
        CREATED,
        REGISTERED,
        INITIALIZED
    }

    record TeardownPlan(String id,
                        PluginContainer container,
                        Path runtimeCopy,
                        LoadStage stage,
                        Duration eventTimeout,
                        boolean archiveRuntimeCopy) {
        TeardownPlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(eventTimeout, "eventTimeout");
        }
    }
}
