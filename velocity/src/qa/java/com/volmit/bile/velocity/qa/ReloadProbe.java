package com.volmit.bile.velocity.qa;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.volmit.bile.velocity.BileVelocity;
import com.volmit.bile.velocity.UnloadReason;
import com.volmit.bile.velocity.api.ReloadParticipant;
import com.volmit.bile.velocity.api.ReloadPreparation;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ReloadProbe implements ReloadParticipant {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path data;
    private String revision;
    private volatile boolean prepared;

    @Inject
    public ReloadProbe(ProxyServer proxy, Logger logger, @DataDirectory Path data) {
        this.proxy = proxy;
        this.logger = logger;
        this.data = data;
    }

    @Subscribe
    public EventTask initialize(ProxyInitializeEvent event) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/revision.txt")) {
            revision = new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        return EventTask.withContinuation(continuation -> proxy.getScheduler().buildTask(this, () -> {
            try {
                proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qaprobe").plugin(this).build(),
                        (SimpleCommand) invocation -> invocation.source().sendMessage(Component.text("probe=" + revision + " prepared=" + prepared)));
                proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qacollision").plugin(this).build(),
                        (SimpleCommand) invocation -> invocation.source().sendMessage(Component.text("collision=core")));
                registerPacket();
                BileVelocity bile = (BileVelocity) proxy.getPluginManager().getPlugin("biletools").orElseThrow().getInstance().orElseThrow();
                bile.ownedResources().registerChannel(this, MinecraftChannelIdentifier.create("qa", "core"));
                bile.ownedResources().registerChannel(this, MinecraftChannelIdentifier.create("qa", "shared"));
                bile.ownedResources().registerChannel(this, MinecraftChannelIdentifier.create("qa", "external"));
                if (revision.equals("broken")) {
                    throw new IllegalStateException("Expected fixture initialization failure");
                }
                logger.info("PROBE_READY revision={}", revision);
                continuation.resume();
            } catch (Throwable failure) {
                continuation.resumeWithException(failure);
            }
        }).delay(Duration.ofMillis(250L)).schedule());
    }

    @SuppressWarnings("unchecked")
    private void registerPacket() throws Exception {
        Class<?> stateType = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry");
        Object play = stateType.getField("PLAY").get(null);
        Object registry = field(stateType, play, "clientbound");
        Map<?, ?> versions = (Map<?, ?>) field(registry.getClass(), registry, "versions");
        Object protocol = versions.values().iterator().next();
        Map<Class<?>, Integer> classes = (Map<Class<?>, Integer>) field(protocol.getClass(), protocol, "packetClassToId");
        Map<Integer, Object> suppliers = (Map<Integer, Object>) field(protocol.getClass(), protocol, "packetIdToSupplier");
        Class<?> packet = Class.forName("com.velocitypowered.proxy.protocol.MinecraftPacket");
        Supplier<Object> supplier = () -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{packet},
                (instance, method, arguments) -> null);
        if (suppliers.containsKey(28672)) {
            throw new IllegalStateException("Previous fixture packet mapping survived reload");
        }
        classes.put(supplier.get().getClass(), 28672);
        suppliers.put(28672, supplier);
    }

    private static Object field(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    @Subscribe
    public EventTask shutdown(ProxyShutdownEvent event) {
        return EventTask.withContinuation(continuation -> proxy.getScheduler().buildTask(this, () -> {
            logger.info("PROBE_STOPPED revision={}", revision);
            continuation.resume();
        }).delay(Duration.ofMillis(100L)).schedule());
    }

    @Override
    public CompletionStage<ReloadPreparation> prepareReload(UnloadReason reason) {
        prepared = true;
        if (Files.exists(data.resolve("slow"))) {
            CompletableFuture<ReloadPreparation> completion = new CompletableFuture<>();
            proxy.getScheduler().buildTask(this, () -> completion.complete(ReloadPreparation.readyToUnload()))
                    .delay(Duration.ofSeconds(8L)).schedule();
            return completion;
        }
        return CompletableFuture.completedFuture(Files.exists(data.resolve("veto"))
                ? ReloadPreparation.refuse("fixture veto") : ReloadPreparation.readyToUnload());
    }

    @Override
    public CompletionStage<Void> cancelReload() {
        prepared = false;
        logger.info("PROBE_CANCELLED revision={}", revision);
        return CompletableFuture.completedFuture(null);
    }
}
