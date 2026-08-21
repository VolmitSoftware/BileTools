package com.volmit.bile.watch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;

public final class PluginDependencyOrder {
    private static final Comparator<String> NAME_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    private PluginDependencyOrder() {
    }

    public static List<String> order(Collection<String> pluginNames,
                                     Function<String, ? extends Collection<String>> dependencyProvider,
                                     Function<String, ? extends Collection<String>> providedNameProvider) {
        Map<String, String> canonicalNames = new HashMap<>();
        for (String pluginName : pluginNames) {
            if (pluginName == null || pluginName.trim().isEmpty()) {
                continue;
            }
            canonicalNames.putIfAbsent(normalize(pluginName), pluginName);
        }

        Map<String, String> providedNames = new HashMap<>();
        for (Map.Entry<String, String> entry : canonicalNames.entrySet()) {
            Collection<String> aliases = providedNameProvider.apply(entry.getValue());
            if (aliases == null) {
                continue;
            }
            for (String alias : aliases) {
                String aliasKey = normalize(alias);
                if (!aliasKey.isEmpty()) {
                    providedNames.putIfAbsent(aliasKey, entry.getKey());
                }
            }
        }

        Map<String, Integer> incoming = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String key : canonicalNames.keySet()) {
            incoming.put(key, 0);
            dependents.put(key, new HashSet<>());
        }

        for (Map.Entry<String, String> entry : canonicalNames.entrySet()) {
            Collection<String> dependencies = dependencyProvider.apply(entry.getValue());
            if (dependencies == null) {
                continue;
            }
            for (String dependency : dependencies) {
                String dependencyKey = normalize(dependency);
                if (!canonicalNames.containsKey(dependencyKey)) {
                    dependencyKey = providedNames.getOrDefault(dependencyKey, dependencyKey);
                }
                if (!canonicalNames.containsKey(dependencyKey) || dependencyKey.equals(entry.getKey())) {
                    continue;
                }
                if (dependents.get(dependencyKey).add(entry.getKey())) {
                    incoming.put(entry.getKey(), incoming.get(entry.getKey()) + 1);
                }
            }
        }

        PriorityQueue<String> ready = new PriorityQueue<>((left, right) ->
                NAME_ORDER.compare(canonicalNames.get(left), canonicalNames.get(right)));
        for (Map.Entry<String, Integer> entry : incoming.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        List<String> orderedKeys = new ArrayList<>(canonicalNames.size());
        while (!ready.isEmpty()) {
            String key = ready.remove();
            orderedKeys.add(key);
            for (String dependent : dependents.get(key)) {
                int remaining = incoming.get(dependent) - 1;
                incoming.put(dependent, remaining);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }

        if (orderedKeys.size() < canonicalNames.size()) {
            List<String> cyclic = new ArrayList<>();
            for (String key : canonicalNames.keySet()) {
                if (!orderedKeys.contains(key)) {
                    cyclic.add(key);
                }
            }
            cyclic.sort((left, right) -> NAME_ORDER.compare(canonicalNames.get(left), canonicalNames.get(right)));
            orderedKeys.addAll(cyclic);
        }

        List<String> ordered = new ArrayList<>(orderedKeys.size());
        for (String key : orderedKeys) {
            ordered.add(canonicalNames.get(key));
        }
        return ordered;
    }

    private static String normalize(String pluginName) {
        if (pluginName == null) {
            return "";
        }
        return pluginName.trim().toLowerCase(Locale.ROOT);
    }
}
