package com.volmit.bile;

import org.bukkit.plugin.InvalidPluginException;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertThrows;

public class PluginDependencyPlanTest {
    @Test
    public void rejectsRequiredCyclesAndSelfDependencies() {
        assertThrows(InvalidPluginException.class, () -> PluginDependencyPlan.validate(List.of(
                new PluginDependencyPlan.Identity("Root", List.of(), List.of("Dependent")),
                new PluginDependencyPlan.Identity("Dependent", List.of(), List.of("Root"))), Set.of()));
        assertThrows(InvalidPluginException.class, () -> PluginDependencyPlan.validate(List.of(
                new PluginDependencyPlan.Identity("Root", List.of("Alias"), List.of("Alias"))), Set.of()));
    }

    @Test
    public void usesReplacementAliasesAndSurvivingDependencies() throws InvalidPluginException {
        PluginDependencyPlan.validate(List.of(
                new PluginDependencyPlan.Identity("Replacement", List.of("OldName"), List.of("Library")),
                new PluginDependencyPlan.Identity("Dependent", List.of(), List.of("oldname"))), Set.of("LIBRARY"));
        assertThrows(InvalidPluginException.class, () -> PluginDependencyPlan.validate(List.of(
                new PluginDependencyPlan.Identity("Replacement", List.of(), List.of()),
                new PluginDependencyPlan.Identity("Dependent", List.of(), List.of("OldName"))), Set.of()));
    }
}
