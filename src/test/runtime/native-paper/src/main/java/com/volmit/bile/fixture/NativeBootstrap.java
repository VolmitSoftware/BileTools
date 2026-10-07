package com.volmit.bile.fixture;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

public final class NativeBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        FixtureState.track(getClass().getClassLoader());
        FixtureState.bootstraps++;
        if ("startup-only".equals(FixtureState.mode())) {
            context.getLifecycleManager().registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY,
                    event -> context.getLogger().info("NATIVE_FIXTURE startup-only handler ran"));
        }
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            FixtureState.bootstrapRegistrations++;
            event.registrar().register(Commands.literal("nativefixture").then(Commands.literal("gc").executes(command -> {
                System.gc();
                command.getSource().getSender().sendMessage(FixtureState.status());
                return 1;
            })).executes(command -> {
                command.getSource().getSender().sendMessage(FixtureState.status());
                return 1;
            }).build());
        });
        context.getLogger().info(FixtureState.status());
    }
}
