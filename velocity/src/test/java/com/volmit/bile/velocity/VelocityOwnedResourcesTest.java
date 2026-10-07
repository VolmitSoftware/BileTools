package com.volmit.bile.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocityOwnedResourcesTest {
    @Test
    void sharedClaimsSurviveUntilTheirLastOwnerUnloads() {
        ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        TestRegistrar registrar = new TestRegistrar();
        when(proxy.getChannelRegistrar()).thenReturn(registrar);
        Object first = new Object();
        Object second = new Object();
        PluginContainer firstContainer = mock(PluginContainer.class);
        PluginContainer secondContainer = mock(PluginContainer.class);
        when(proxy.getPluginManager().fromInstance(first)).thenReturn(Optional.of(firstContainer));
        when(proxy.getPluginManager().fromInstance(second)).thenReturn(Optional.of(secondContainer));
        VelocityOwnedResources resources = new VelocityOwnedResources(proxy);
        ChannelIdentifier channel = MinecraftChannelIdentifier.create("example", "shared");

        resources.registerChannel(first, channel);
        resources.registerChannel(second, channel);
        resources.release(firstContainer);
        assertTrue(registrar.identifierMap.containsKey(channel.getId()));
        resources.release(secondContainer);
        assertFalse(registrar.identifierMap.containsKey(channel.getId()));
    }

    @Test
    void preexistingChannelsAreNeverRemoved() {
        ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        TestRegistrar registrar = new TestRegistrar();
        when(proxy.getChannelRegistrar()).thenReturn(registrar);
        Object instance = new Object();
        PluginContainer owner = mock(PluginContainer.class);
        when(proxy.getPluginManager().fromInstance(instance)).thenReturn(Optional.of(owner));
        VelocityOwnedResources resources = new VelocityOwnedResources(proxy);
        ChannelIdentifier channel = MinecraftChannelIdentifier.create("example", "existing");
        registrar.register(channel);

        resources.registerChannel(instance, channel);
        resources.release(owner);

        assertTrue(registrar.identifierMap.containsKey(channel.getId()));
    }

    @Test
    void replacedChannelIdentifiersArePreserved() {
        ProxyServer proxy = mock(ProxyServer.class, RETURNS_DEEP_STUBS);
        TestRegistrar registrar = new TestRegistrar();
        when(proxy.getChannelRegistrar()).thenReturn(registrar);
        Object instance = new Object();
        PluginContainer owner = mock(PluginContainer.class);
        when(proxy.getPluginManager().fromInstance(instance)).thenReturn(Optional.of(owner));
        VelocityOwnedResources resources = new VelocityOwnedResources(proxy);
        ChannelIdentifier channel = MinecraftChannelIdentifier.create("example", "replaced");
        resources.registerChannel(instance, channel);
        registrar.register(MinecraftChannelIdentifier.create("example", "replaced"));

        resources.release(owner);

        assertTrue(registrar.identifierMap.containsKey(channel.getId()));
    }

    private static final class TestRegistrar implements ChannelRegistrar {
        private final Map<String, ChannelIdentifier> identifierMap = new LinkedHashMap<>();

        @Override
        public void register(ChannelIdentifier... identifiers) {
            for (ChannelIdentifier identifier : identifiers) {
                identifierMap.put(identifier.getId(), identifier);
            }
        }

        @Override
        public void unregister(ChannelIdentifier... identifiers) {
            for (ChannelIdentifier identifier : identifiers) {
                identifierMap.remove(identifier.getId());
            }
        }
    }
}
