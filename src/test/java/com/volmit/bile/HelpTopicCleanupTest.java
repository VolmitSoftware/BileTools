package com.volmit.bile;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.plugin.Plugin;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class HelpTopicCleanupTest {
    @Test
    public void removesOnlyOwnedCommandsAndTheirAliasChainsFromEveryIndex() throws Exception {
        Plugin target = plugin("target");
        Plugin other = plugin("other");
        HelpTopic owned = new GenericCommandHelpTopic(new OwnedCommand("native", target));
        HelpTopic retained = new GenericCommandHelpTopic(new OwnedCommand("other", other));
        HelpTopic alias = new AliasTopic("/alias", "/native");
        HelpTopic aliasChain = new AliasTopic("/chain", "/alias");
        HelpTopic unrelated = new AliasTopic("/kept", "/other");
        InspectableIndex nested = new InspectableIndex("nested", List.of(owned, alias, retained));
        InspectableIndex index = new InspectableIndex("index", new ArrayList<>(List.of(owned, aliasChain, nested, retained)));
        List<HelpTopic> topics = new ArrayList<>(List.of(owned, retained, aliasChain, alias, unrelated, index));

        PluginHelpCleanup.remove(topics, target);

        assertEquals(List.of(retained, unrelated, index), topics);
        assertEquals(List.of(nested, retained), index.topics());
        assertEquals(List.of(retained), nested.topics());
    }

    @Test
    public void preservesLiveIndexCollectionAndPluginIdentity() throws Exception {
        Plugin target = plugin("same-name");
        Plugin other = plugin("same-name");
        HelpTopic owned = new GenericCommandHelpTopic(new OwnedCommand("native", target));
        HelpTopic retained = new GenericCommandHelpTopic(new OwnedCommand("other", other));
        List<HelpTopic> commands = new ArrayList<>(List.of(owned, retained));
        InspectableIndex index = new InspectableIndex("index", commands);
        List<HelpTopic> topics = new ArrayList<>(List.of(owned, retained, index));

        PluginHelpCleanup.remove(topics, target);
        HelpTopic added = new AliasTopic("/new", "/other");
        commands.add(added);

        assertEquals(List.of(retained, index), topics);
        assertEquals(List.of(retained, added), index.topics());
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> method.getName().equals("getName") ? name : null);
    }

    private static final class OwnedCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;

        private OwnedCommand(String name, Plugin plugin) {
            super(name);
            this.plugin = plugin;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] arguments) {
            return true;
        }
    }

    private static final class AliasTopic extends HelpTopic {
        private final String aliasFor;

        private AliasTopic(String name, String aliasFor) {
            this.name = name;
            this.aliasFor = aliasFor;
        }

        @Override
        public boolean canSee(CommandSender sender) {
            return true;
        }
    }

    private static final class InspectableIndex extends IndexHelpTopic {
        private InspectableIndex(String name, Collection<HelpTopic> topics) {
            super(name, "", null, topics);
        }

        private Collection<HelpTopic> topics() {
            return allTopics;
        }
    }
}
