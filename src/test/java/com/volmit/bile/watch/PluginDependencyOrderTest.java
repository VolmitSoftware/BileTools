package com.volmit.bile.watch;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PluginDependencyOrderTest {
    @Test
    public void ordersTransitiveDependenciesBeforeDependents() {
        Map<String, List<String>> dependencies = Map.of(
                "Alpha", List.of(),
                "Middle", List.of("Alpha"),
                "Zulu", List.of("Middle")
        );

        List<String> ordered = PluginDependencyOrder.order(
                List.of("Zulu", "Alpha", "Middle"), dependencies::get, ignored -> List.of());

        assertEquals(List.of("Alpha", "Middle", "Zulu"), ordered);
    }

    @Test
    public void ordersIndependentPluginsDeterministically() {
        List<String> ordered = PluginDependencyOrder.order(
                List.of("zeta", "Alpha", "beta"), ignored -> List.of(), ignored -> List.of());

        assertEquals(List.of("Alpha", "beta", "zeta"), ordered);
    }

    @Test
    public void retainsEveryPluginWhenDependenciesCycle() {
        Map<String, List<String>> dependencies = Map.of(
                "Alpha", List.of("Beta"),
                "Beta", List.of("Alpha")
        );

        List<String> ordered = PluginDependencyOrder.order(
                List.of("Beta", "Alpha"), dependencies::get, ignored -> List.of());

        assertEquals(List.of("Alpha", "Beta"), ordered);
    }

    @Test
    public void resolvesDependenciesDeclaredThroughProvidedNames() {
        Map<String, List<String>> dependencies = Map.of(
                "Consumer", List.of("SharedAPI"),
                "Provider", List.of()
        );
        Map<String, List<String>> providedNames = Map.of(
                "Consumer", List.of(),
                "Provider", List.of("SharedAPI")
        );

        List<String> ordered = PluginDependencyOrder.order(
                List.of("Consumer", "Provider"), dependencies::get, providedNames::get);

        assertEquals(List.of("Provider", "Consumer"), ordered);
    }
}
