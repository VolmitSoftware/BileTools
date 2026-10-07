package com.volmit.bile.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class VelocityOwnedResources {
    private final ProxyServer proxy;
    private final Map<String, ChannelClaim> channels = new LinkedHashMap<>();

    public VelocityOwnedResources(ProxyServer proxy) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
    }

    public synchronized void registerChannel(Object plugin, ChannelIdentifier channel) {
        Objects.requireNonNull(channel, "channel");
        PluginContainer owner = proxy.getPluginManager().fromInstance(plugin).orElseThrow(() ->
                new IllegalArgumentException("plugin is not registered"));
        ChannelClaim claim = channels.get(channel.getId());
        if (claim == null) {
            Map<?, ?> registered = channelMap();
            boolean exclusive = registered != null && !registered.containsKey(channel.getId());
            Object existing = registered == null ? null : registered.get(channel.getId());
            if (existing == null) {
                proxy.getChannelRegistrar().register(channel);
            }
            claim = new ChannelClaim(existing instanceof ChannelIdentifier identifier ? identifier : channel, exclusive);
            channels.put(channel.getId(), claim);
        }
        claim.owners.add(owner);
    }

    synchronized void release(PluginContainer owner) {
        List<String> finished = new ArrayList<>();
        for (Map.Entry<String, ChannelClaim> entry : channels.entrySet()) {
            ChannelClaim claim = entry.getValue();
            if (!claim.owners.remove(owner) || !claim.owners.isEmpty()) {
                continue;
            }
            Map<?, ?> registered = channelMap();
            if (claim.exclusive && registered != null && registered.get(entry.getKey()) == claim.identifier) {
                proxy.getChannelRegistrar().unregister(claim.identifier);
                if (registered.containsKey(entry.getKey())) {
                    throw new IllegalStateException("channel remains registered: " + entry.getKey());
                }
            }
            finished.add(entry.getKey());
        }
        for (String id : finished) {
            channels.remove(id);
        }
    }

    synchronized List<String> inspect(PluginContainer owner) {
        List<String> owned = new ArrayList<>();
        List<String> shared = new ArrayList<>();
        for (Map.Entry<String, ChannelClaim> entry : channels.entrySet()) {
            ChannelClaim claim = entry.getValue();
            if (claim.owners.contains(owner)) {
                (claim.exclusive && claim.owners.size() == 1 ? owned : shared).add(entry.getKey());
            }
        }
        return List.of("Exclusive tracked channels: " + owned, "Shared or ambiguous tracked channels: " + shared,
                "Untracked channels and external resources require plugin cleanup.");
    }

    private Map<?, ?> channelMap() {
        try {
            Object registrar = proxy.getChannelRegistrar();
            Field field = registrar.getClass().getDeclaredField("identifierMap");
            field.setAccessible(true);
            Object value = field.get(registrar);
            return value instanceof Map<?, ?> map ? map : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static final class ChannelClaim {
        private final ChannelIdentifier identifier;
        private final boolean exclusive;
        private final Set<PluginContainer> owners = Collections.newSetFromMap(new IdentityHashMap<>());

        private ChannelClaim(ChannelIdentifier identifier, boolean exclusive) {
            this.identifier = identifier;
            this.exclusive = exclusive;
        }
    }
}
