package com.volmit.bile.command;

import art.arcane.volmlib.util.director.DirectorTextResolver;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu.DirectorHelpPage;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import com.volmit.bile.BileTools;
import com.volmit.bile.PlatformTasks;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public final class BileFancyMenu {
    private BileFancyMenu() {
    }

    public static boolean sendIfHelpRequested(
            CommandSender sender,
            DirectorRuntimeEngine engine,
            String[] args,
            DirectorTextResolver resolver
    ) {
        Optional<DirectorHelpPage> request = DirectorMiniMenu.resolveHelp(engine, Arrays.asList(args));
        if (request.isEmpty()) {
            return false;
        }

        DirectorMiniMenu.deliver(sender, request.get(), DirectorMiniMenu.Theme.bileGreen(), resolver);
        return true;
    }

    public static void playTabSound(CommandSender sender) {
        playOnPlayer(sender, player ->
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.25f, randomPitch(0.125f, 1.95f)));
    }

    public static void playSuccessSound(CommandSender sender) {
        playOnPlayer(sender, player -> {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.77f, 1.65f);
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.125f, 2.99f);
        });
    }

    public static void playFailureSound(CommandSender sender) {
        playOnPlayer(sender, player -> {
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.77f, 0.25f);
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.2f, 0.45f);
        });
    }

    private static void playOnPlayer(CommandSender sender, java.util.function.Consumer<Player> action) {
        if (!(sender instanceof Player player) || action == null) {
            return;
        }

        Runnable task = () -> {
            try {
                action.accept(player);
            } catch (Throwable ignored) {
            }
        };

        if (BileTools.bile != null && BileTools.bile.isEnabled()) {
            PlatformTasks.runForPlayer(BileTools.bile, player, task);
        } else {
            task.run();
        }
    }

    private static float randomPitch(float min, float max) {
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }

}
