package com.volmit.bile.velocity.qa;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.volmit.bile.velocity.BileVelocity;
import net.kyori.adventure.text.Component;

public final class DependentProbe {
    private final ProxyServer proxy;

    @Inject
    public DependentProbe(ProxyServer proxy) {
        this.proxy = proxy;
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        BileVelocity bile = (BileVelocity) proxy.getPluginManager().getPlugin("biletools").orElseThrow().getInstance().orElseThrow();
        bile.ownedResources().registerChannel(this, MinecraftChannelIdentifier.create("qa", "shared"));
        bile.ownedResources().registerChannel(this, MinecraftChannelIdentifier.create("qa", "addon"));
        proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("qaaddon").plugin(this).build(),
                (SimpleCommand) invocation -> invocation.source().sendMessage(Component.text("addon-ready="
                        + proxy.getPluginManager().getPlugin("qacore").isPresent())));
    }
}
