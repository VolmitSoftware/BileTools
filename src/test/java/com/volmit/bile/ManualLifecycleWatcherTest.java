package com.volmit.bile;

import com.volmit.bile.watch.JarSnapshotStager;
import org.bukkit.plugin.Plugin;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ManualLifecycleWatcherTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void manualReplacementAllowsEarlierContentForTheWholeChangedGroup() throws Exception {
        Path root = temporary.newFile("Root.jar").toPath();
        Files.writeString(root, "version-one");
        String startupFingerprint = JarSnapshotStager.fingerprint(root);
        Path dependent = temporary.newFile("Dependent.jar").toPath();
        Path addedDependency = temporary.newFile("NewDependency.jar").toPath();
        Path unrelated = temporary.newFile("Unrelated.jar").toPath();
        Plugin unchanged = plugin("Unrelated");
        Map<Plugin, Path> before = new IdentityHashMap<>();
        before.put(plugin("Root"), root);
        before.put(plugin("Dependent"), dependent);
        before.put(unchanged, unrelated);
        Map<Plugin, Path> after = new IdentityHashMap<>();
        after.put(plugin("Root"), root);
        after.put(plugin("Dependent"), dependent);
        after.put(plugin("NewDependency"), addedDependency);
        after.put(unchanged, unrelated);
        Map<Path, String> applied = new HashMap<>();
        applied.put(root, startupFingerprint);
        applied.put(dependent, "dependent-startup");
        applied.put(unrelated, "unchanged");
        Files.writeString(root, "version-two");

        Set<Path> changed = BileTools.changedPluginSources(before, after);
        changed.forEach(applied::remove);
        Files.writeString(root, "version-one");

        assertEquals(Set.of(root, dependent, addedDependency), changed);
        assertFalse(JarSnapshotStager.fingerprint(root).equals(applied.get(root)));
        assertEquals(Map.of(unrelated, "unchanged"), applied);
    }

    @Test
    public void manualUnloadInvalidatesEveryRemovedPluginSource() {
        Path root = Path.of("Root.jar");
        Path dependent = Path.of("Dependent.jar");
        Plugin untouched = plugin("Unrelated");
        Map<Plugin, Path> before = new IdentityHashMap<>();
        before.put(plugin("Root"), root);
        before.put(plugin("Dependent"), dependent);
        before.put(untouched, Path.of("Unrelated.jar"));
        Map<Plugin, Path> after = new IdentityHashMap<>();
        after.put(untouched, Path.of("Unrelated.jar"));

        assertEquals(Set.of(root, dependent), BileTools.changedPluginSources(before, after));
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "equals" -> arguments[0] instanceof Plugin other && name.equals(other.getName());
                    case "hashCode" -> name.hashCode();
                    default -> null;
                });
    }
}
