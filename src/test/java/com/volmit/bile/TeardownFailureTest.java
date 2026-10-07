package com.volmit.bile;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandMap;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class TeardownFailureTest {
    @Test
    public void attemptsEverySchedulerAfterOneCancellationFails() {
        AtomicInteger cancelled = new AtomicInteger();
        GlobalRegionScheduler global = scheduler(GlobalRegionScheduler.class, cancelled, true);
        AsyncScheduler async = scheduler(AsyncScheduler.class, cancelled, false);
        BukkitScheduler bukkit = scheduler(BukkitScheduler.class, cancelled, false);
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getGlobalRegionScheduler" -> global;
                    case "getAsyncScheduler" -> async;
                    case "getScheduler" -> bukkit;
                    default -> null;
                });
        Plugin plugin = plugin(server);

        PlatformTasks.validatePluginTaskCancellation(plugin);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PlatformTasks.cancelPluginTasks(plugin));

        assertEquals(3, cancelled.get());
        assertEquals(1, failure.getSuppressed().length);
    }

    @Test
    public void refusesMissingSchedulerBeforeCancellation() {
        Server server = (Server) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, arguments) -> null);
        assertThrows(IllegalStateException.class, () -> PlatformTasks.validatePluginTaskCancellation(plugin(server)));
    }

    @Test
    public void reportsRemovalFailureAndStillCleansOtherCommands() {
        Plugin plugin = plugin(null);
        Map<String, Command> commands = new HashMap<>() {
            @Override
            public boolean remove(Object key, Object value) {
                if (key.equals("broken")) {
                    throw new IllegalStateException("map removal failed");
                }
                return super.remove(key, value);
            }
        };
        commands.put("broken", new OwnedCommand(plugin, false));
        commands.put("working", new OwnedCommand(plugin, false));

        assertThrows(IllegalStateException.class,
                () -> BileUtils.scrubPluginCommands(plugin, new SimpleCommandMap(null), commands));

        assertEquals(1, commands.size());
        assertFalse(commands.containsKey("working"));
    }

    @Test
    public void reportsCommandUnregistrationFailure() {
        Plugin plugin = plugin(null);
        Map<String, Command> commands = new HashMap<>();
        commands.put("broken", new OwnedCommand(plugin, true));

        assertThrows(IllegalStateException.class,
                () -> BileUtils.scrubPluginCommands(plugin, new SimpleCommandMap(null), commands));

        assertEquals(0, commands.size());
    }

    private static <T> T scheduler(Class<T> type, AtomicInteger cancelled, boolean fail) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("cancelTasks")) {
                        cancelled.incrementAndGet();
                        if (fail) {
                            throw new IllegalStateException("scheduler cancellation failed");
                        }
                    }
                    return null;
                }));
    }

    private static Plugin plugin(Server server) {
        PluginDescriptionFile description = new PluginDescriptionFile("CleanupFixture", "1", "example.Main");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> description.getName();
                    case "getDescription", "getPluginMeta" -> description;
                    case "getServer" -> server;
                    default -> null;
                });
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        private final boolean fail;

        private OwnedCommand(Plugin plugin, boolean fail) {
            super("owned");
            this.plugin = plugin;
            this.fail = fail;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return true;
        }

        @Override
        public boolean unregister(CommandMap commandMap) {
            if (fail) {
                throw new IllegalStateException("unregistration failed");
            }
            return super.unregister(commandMap);
        }
    }
}
