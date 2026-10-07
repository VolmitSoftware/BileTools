package com.volmit.bile.fixture;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class NativeFixture extends JavaPlugin {
    @Override
    public void onLoad() {
        FixtureState.loads++;
        getLogger().info(FixtureState.status());
        if ("fail-load".equals(FixtureState.mode())) {
            throw new IllegalStateException("NATIVE_FIXTURE deliberate onLoad failure");
        }
    }

    @Override
    public void onEnable() {
        FixtureState.enables++;
        getLogger().info(FixtureState.status());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            FixtureState.pluginRegistrations++;
            event.registrar().register(Commands.literal("nativefixtureplugin").executes(command -> {
                command.getSource().getSender().sendMessage(FixtureState.status());
                return 1;
            }).build());
        });
        if ("fail-enable".equals(FixtureState.mode())) {
            throw new IllegalStateException("NATIVE_FIXTURE deliberate onEnable failure");
        }
    }

    @Override
    public void onDisable() {
        FixtureState.disables++;
        getLogger().info(FixtureState.status());
    }
}
