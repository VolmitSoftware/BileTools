package com.volmit.bile;

import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.help.GenericCommandHelpTopic;
import org.bukkit.help.HelpTopic;
import org.bukkit.help.IndexHelpTopic;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

final class PluginHelpCleanup {
    private PluginHelpCleanup() {
    }

    static void remove(Collection<HelpTopic> topics, Plugin plugin) throws ReflectiveOperationException {
        Set<HelpTopic> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IndexHelpTopic> indexes = new ArrayList<>();
        collect(topics, visited, indexes);
        Set<HelpTopic> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> names = new HashSet<>();
        for (HelpTopic topic : visited) {
            if (owned(topic, plugin)) {
                removed.add(topic);
                names.add(topic.getName());
            }
        }
        boolean changed;
        do {
            changed = false;
            for (HelpTopic topic : visited) {
                Field alias = field(topic.getClass(), "aliasFor");
                if (!removed.contains(topic) && alias != null && names.contains(alias.get(topic))) {
                    removed.add(topic);
                    names.add(topic.getName());
                    changed = true;
                }
            }
        } while (changed);
        for (IndexHelpTopic index : indexes) {
            Collection<HelpTopic> children = children(index);
            if (children.stream().anyMatch(removed::contains)) {
                try {
                    children.removeIf(removed::contains);
                } catch (UnsupportedOperationException exception) {
                    List<HelpTopic> retained = new ArrayList<>(children);
                    retained.removeIf(removed::contains);
                    field(IndexHelpTopic.class, "allTopics").set(index, retained);
                }
            }
        }
        topics.removeIf(removed::contains);
    }

    private static void collect(Collection<HelpTopic> topics, Set<HelpTopic> visited, List<IndexHelpTopic> indexes)
            throws ReflectiveOperationException {
        for (HelpTopic topic : topics) {
            if (visited.add(topic) && topic instanceof IndexHelpTopic index) {
                indexes.add(index);
                collect(children(index), visited, indexes);
            }
        }
    }

    private static boolean owned(HelpTopic topic, Plugin plugin) throws ReflectiveOperationException {
        if (topic.getClass().getClassLoader() != HelpTopic.class.getClassLoader()
                && topic.getClass().getClassLoader() == plugin.getClass().getClassLoader()) {
            return true;
        }
        if (topic instanceof GenericCommandHelpTopic) {
            Command command = (Command) field(GenericCommandHelpTopic.class, "command").get(topic);
            return command instanceof PluginIdentifiableCommand identifiable && identifiable.getPlugin() == plugin;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Collection<HelpTopic> children(IndexHelpTopic index) throws ReflectiveOperationException {
        return (Collection<HelpTopic>) field(IndexHelpTopic.class, "allTopics").get(index);
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException exception) {
                continue;
            }
        }
        return null;
    }
}
