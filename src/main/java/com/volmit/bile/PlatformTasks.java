package com.volmit.bile;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;

/**
 * Cross-platform task scheduling.
 *
 * <ul>
 *   <li>Folia/Canvas: GlobalRegionScheduler / AsyncScheduler / EntityScheduler only</li>
 *   <li>Paper/Purpur/Leaf: prefer Folia API stubs when present, else Bukkit scheduler</li>
 *   <li>Spigot: Bukkit scheduler only</li>
 * </ul>
 */
public final class PlatformTasks {
    private static final ExecutorService FALLBACK_ASYNC = Executors.newCachedThreadPool(daemonFactory("BileTools-FallbackAsync"));

    private PlatformTasks() {
    }

    public static boolean runGlobal(Plugin plugin, Runnable runnable) {
        return runGlobal(plugin, runnable, 0L);
    }

    public static boolean runGlobal(Plugin plugin, Runnable runnable, long delayTicks) {
        if (plugin == null || runnable == null || !plugin.isEnabled()) {
            return false;
        }

        Runnable guarded = wrap(plugin, runnable);

        if (FoliaScheduler.runGlobal(plugin, guarded, delayTicks)) {
            return true;
        }

        // Folia/Canvas: never touch Bukkit.getScheduler() — it throws.
        if (ServerPlatform.isFoliaFamily()) {
            return false;
        }

        try {
            if (delayTicks <= 0L) {
                if (FoliaScheduler.isPrimaryThread()) {
                    guarded.run();
                    return true;
                }
                Bukkit.getScheduler().runTask(plugin, guarded);
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, guarded, Math.max(1L, delayTicks));
            }
            return true;
        } catch (IllegalPluginAccessException | UnsupportedOperationException e) {
            return false;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to schedule global task", t);
            return false;
        }
    }

    public static boolean runAsync(Plugin plugin, Runnable runnable) {
        if (plugin == null || runnable == null || !plugin.isEnabled()) {
            return false;
        }

        Runnable guarded = wrap(plugin, runnable);

        if (FoliaScheduler.runAsync(plugin, guarded)) {
            return true;
        }

        if (!ServerPlatform.isFoliaFamily()) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, guarded);
                return true;
            } catch (IllegalPluginAccessException | UnsupportedOperationException ignored) {
                // fall through
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to schedule async task via Bukkit", t);
            }
        }

        try {
            FALLBACK_ASYNC.execute(guarded);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to schedule fallback async task", t);
            return false;
        }
    }

    /**
     * Run work on the thread that owns a player/entity (Folia), or global/main otherwise.
     */
    public static boolean runForEntity(Plugin plugin, Entity entity, Runnable runnable) {
        if (plugin == null || runnable == null || !plugin.isEnabled()) {
            return false;
        }

        if (entity == null) {
            return runGlobal(plugin, runnable);
        }

        Runnable guarded = wrap(plugin, runnable);

        if (ServerPlatform.isFoliaFamily()) {
            if (FoliaScheduler.runEntity(plugin, entity, guarded)) {
                return true;
            }
            // Entity retired or scheduler unavailable — degrade to global for message-like work
            return FoliaScheduler.runGlobal(plugin, guarded);
        }

        return runGlobal(plugin, guarded);
    }

    public static boolean runForPlayer(Plugin plugin, Player player, Runnable runnable) {
        return runForEntity(plugin, player, runnable);
    }

    public static void validatePluginTaskCancellation(Plugin plugin) {
        if (ServerPlatform.isPaperRuntime()) {
            for (String accessor : List.of("getGlobalRegionScheduler", "getAsyncScheduler")) {
                Object scheduler = pluginScheduler(plugin, accessor);
                cancellationMethod(scheduler);
            }
        }
        if (!ServerPlatform.isRegionizedThreading() && plugin.getServer().getScheduler() == null) {
            throw new IllegalStateException("The Bukkit task scheduler is unavailable");
        }
    }

    public static void cancelPluginTasks(Plugin plugin) {
        List<Throwable> failures = new ArrayList<>();
        if (ServerPlatform.isPaperRuntime()) {
            for (String accessor : List.of("getGlobalRegionScheduler", "getAsyncScheduler")) {
                try {
                    Object scheduler = pluginScheduler(plugin, accessor);
                    cancellationMethod(scheduler).invoke(scheduler, plugin);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    failures.add(failure);
                }
            }
        }
        if (!ServerPlatform.isRegionizedThreading()) {
            try {
                plugin.getServer().getScheduler().cancelTasks(plugin);
            } catch (RuntimeException failure) {
                failures.add(failure);
            }
        }
        if (!failures.isEmpty()) {
            IllegalStateException failure = new IllegalStateException("Task cancellation failed for " + plugin.getName());
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private static Object pluginScheduler(Plugin plugin, String accessor) {
        try {
            Method method = plugin.getServer().getClass().getMethod(accessor);
            method.setAccessible(true);
            Object scheduler = method.invoke(plugin.getServer());
            if (scheduler == null) {
                throw new IllegalStateException(accessor + " returned no scheduler");
            }
            return scheduler;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Task scheduler unavailable: " + accessor, failure);
        }
    }

    private static Method cancellationMethod(Object scheduler) {
        try {
            Method method = scheduler.getClass().getMethod("cancelTasks", Plugin.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Task cancellation unavailable for " + scheduler.getClass().getName(), failure);
        }
    }

    private static Runnable wrap(Plugin plugin, Runnable runnable) {
        return () -> {
            if (plugin.isEnabled()) {
                runnable.run();
            }
        };
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
