package com.volmit.bile;

import org.bukkit.plugin.InvalidPluginException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class PluginDependencyPlan {
    private PluginDependencyPlan() {
    }

    static void validate(List<Identity> replacements, Set<String> survivingNames) throws InvalidPluginException {
        Map<String, Identity> owners = new HashMap<>();
        for (Identity identity : replacements) {
            String name = key(identity.name());
            Identity previous = owners.putIfAbsent(name, identity);
            if (previous != null && previous != identity) {
                throw new InvalidPluginException("Duplicate replacement identity " + identity.name());
            }
        }
        Set<String> available = new HashSet<>();
        for (String surviving : survivingNames) {
            available.add(key(surviving));
        }
        for (Identity identity : replacements) {
            for (String provided : identity.provides()) {
                if (!available.contains(key(provided))) {
                    owners.putIfAbsent(key(provided), identity);
                }
            }
        }
        Set<String> completed = new HashSet<>();
        for (Identity identity : replacements) {
            visit(identity, owners, available, new HashSet<>(), completed);
        }
    }

    private static void visit(Identity identity, Map<String, Identity> owners, Set<String> available,
            Set<String> visiting, Set<String> completed) throws InvalidPluginException {
        String name = key(identity.name());
        if (completed.contains(name)) {
            return;
        }
        if (!visiting.add(name)) {
            throw new InvalidPluginException("Required dependency cycle includes " + identity.name());
        }
        for (String dependency : identity.required()) {
            Identity owner = owners.get(key(dependency));
            if (owner != null) {
                visit(owner, owners, available, visiting, completed);
            } else if (!available.contains(key(dependency))) {
                throw new InvalidPluginException("Missing dependency " + dependency + " for " + identity.name());
            }
        }
        visiting.remove(name);
        completed.add(name);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    record Identity(String name, List<String> provides, List<String> required) {
        Identity {
            provides = List.copyOf(provides);
            required = List.copyOf(required);
        }
    }
}
