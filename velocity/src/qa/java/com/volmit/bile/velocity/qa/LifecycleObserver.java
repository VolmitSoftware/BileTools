package com.volmit.bile.velocity.qa;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.permission.PermissionsSetupEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class LifecycleObserver implements SimpleCommand {
    private final ProxyServer proxy;
    private final List<WeakReference<ClassLoader>> retired = new ArrayList<>();

    @Inject
    public LifecycleObserver(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(MinecraftChannelIdentifier.create("qa", "external"));
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qactl").plugin(this).build(), this);
    }

    @Subscribe
    public void permissions(PermissionsSetupEvent event) {
        event.setProvider(subject -> permission -> Tristate.TRUE);
    }

    @Override
    public void execute(Invocation invocation) {
        String operation = invocation.arguments().length == 0 ? "state" : invocation.arguments()[0];
        if (operation.equals("takeover")) {
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qacollision").plugin(this).build(),
                    (SimpleCommand) command -> command.source().sendMessage(Component.text("collision=observer")));
            invocation.source().sendMessage(Component.text("collision-taken"));
            return;
        }
        if (operation.equals("packets")) {
            try {
                Class<?> stateType = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry");
                Object play = stateType.getField("PLAY").get(null);
                Field direction = stateType.getDeclaredField("clientbound");
                direction.setAccessible(true);
                Object registry = direction.get(play);
                Field versionsField = registry.getClass().getDeclaredField("versions");
                versionsField.setAccessible(true);
                Map<?, ?> versions = (Map<?, ?>) versionsField.get(registry);
                Object protocol = versions.values().iterator().next();
                Field suppliersField = protocol.getClass().getDeclaredField("packetIdToSupplier");
                suppliersField.setAccessible(true);
                Map<?, ?> suppliers = (Map<?, ?>) suppliersField.get(protocol);
                invocation.source().sendMessage(Component.text("packet-mappings=" + (suppliers.containsKey(28672) ? 1 : 0)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot inspect fixture packet mapping", failure);
            }
            return;
        }
        if (operation.equals("track")) {
            for (String id : List.of("qacore", "qaaddon")) {
                proxy.getPluginManager().getPlugin(id).flatMap(container -> container.getInstance()).ifPresent(instance ->
                        retired.add(new WeakReference<>(instance.getClass().getClassLoader())));
            }
            invocation.source().sendMessage(Component.text("tracked=" + retired.size()));
            return;
        }
        if (operation.equals("gc")) {
            System.gc();
            int alive = 0;
            for (WeakReference<ClassLoader> reference : retired) {
                if (reference.get() != null) {
                    alive++;
                }
            }
            invocation.source().sendMessage(Component.text("retired-alive=" + alive + " tracked=" + retired.size()));
            return;
        }
        try {
            Object registrar = proxy.getChannelRegistrar();
            Field field = registrar.getClass().getDeclaredField("identifierMap");
            field.setAccessible(true);
            Map<?, ?> channels = (Map<?, ?>) field.get(registrar);
            invocation.source().sendMessage(Component.text("channels=" + channels.keySet()));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect fixture channels", failure);
        }
    }
}
