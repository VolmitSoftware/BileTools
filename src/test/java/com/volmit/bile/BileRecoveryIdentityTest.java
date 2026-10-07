package com.volmit.bile;

import java.io.File;

import com.volmit.bile.watch.JarSnapshotStager;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sun.misc.Unsafe;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BileRecoveryIdentityTest {
    private static final String NAME = "RecoveryCaptureCase";
    private static final String KEY = "recoverycapturecase";

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private Field recoveryField;
    private Object previousStore;
    private PluginRecoveryStore store;

    @Before
    public void configureRecoveryStore() throws Exception {
        recoveryField = BileUtils.class.getDeclaredField("recoveryStore");
        recoveryField.setAccessible(true);
        previousStore = recoveryField.get(null);
        store = new PluginRecoveryStore(temporary.newFolder("recovery").toPath());
        recoveryField.set(null, store);
    }

    @After
    public void restoreRecoveryStore() throws Exception {
        recoveryField.set(null, previousStore);
        stateMap("SOURCE_FILE_OVERRIDES").remove(KEY);
        stateMap("RECOVERY_CAPTURED_INSTANCES").remove(KEY);
        stateMap("RUNNING_PLUGIN_METADATA").remove(KEY);
    }

    @Test
    public void keepsTheAuthoritativeSourceAfterItsJarIsDeleted() throws Exception {
        Path source = writeJar(temporary.getRoot().toPath().resolve("Example.jar"), "one");
        Plugin plugin = plugin(source, "one");
        BileUtils.rememberRunningPlugin(plugin);
        Files.delete(source);

        assertEquals(source.toFile(), BileUtils.getPluginFile(plugin));
        assertTrue(store.contains(NAME));
    }

    @Test
    public void capturesANewInstanceWithTheSameIdWithoutRecapturingTheOldInstance() throws Exception {
        Path source = writeJar(temporary.getRoot().toPath().resolve("Example.jar"), "one");
        byte[] original = Files.readAllBytes(source);
        Plugin first = plugin(source, "one");
        BileUtils.rememberRunningPlugin(first);
        writeJar(source, "two");
        BileUtils.rememberRunningPlugin(first);
        assertArrayEquals(original, recoveryBytes());

        Plugin second = plugin(source, "two");
        BileUtils.rememberRunningPlugin(second);

        assertArrayEquals(Files.readAllBytes(source), recoveryBytes());
        assertTrue(stateMap("RECOVERY_CAPTURED_INSTANCES").get(KEY) instanceof WeakReference<?>);
    }

    @Test
    public void failedNewInstanceCaptureDoesNotAdvertiseAnotherInstancesSnapshot() throws Exception {
        Path firstSource = writeJar(temporary.getRoot().toPath().resolve("First.jar"), "one");
        BileUtils.rememberRunningPlugin(plugin(firstSource, "one"));
        Path secondSource = temporary.getRoot().toPath().resolve("Second.jar");
        Files.writeString(secondSource, "incomplete jar");
        Plugin second = plugin(secondSource, "two");

        BileUtils.rememberRunningPlugin(second);

        assertFalse(store.contains(NAME));
        assertEquals(secondSource.toFile(), BileUtils.getPluginFile(second));
    }

    @Test
    public void mapsARemappedLoadedArchiveToItsAuthoritativePluginPath() throws Exception {
        Path plugins = temporary.newFolder("plugins").toPath();
        Path remapped = Files.createDirectory(plugins.resolve(".paper-remapped"));
        Path runtime = writeJar(remapped.resolve("Example.jar"), "one");
        Plugin plugin = plugin(runtime, "one");

        BileUtils.rememberRunningPlugin(plugin);

        assertEquals(plugins.resolve("Example.jar").toFile(), BileUtils.getPluginFile(plugin));
        assertTrue(store.contains(NAME));
    }

    @Test
    public void dependencyChecksRetainTheRunningVersionsMetadata() throws Exception {
        Path source = writeJar(temporary.getRoot().toPath().resolve("Example.jar"), "one", "OldLibrary");
        Plugin first = plugin(source, "one");
        BileUtils.rememberRunningPlugin(first);
        Plugin oldDependency = plugin(source, "one", "OldLibrary");
        Plugin newDependency = plugin(source, "two", "NewLibrary");
        writeJar(source, "two", "NewLibrary");

        assertTrue(dependsOn(first, oldDependency));
        assertFalse(dependsOn(first, newDependency));

        Plugin replacement = plugin(source, "two");
        BileUtils.rememberRunningPlugin(replacement);

        assertFalse(dependsOn(replacement, oldDependency));
        assertTrue(dependsOn(replacement, newDependency));
    }

    @Test
    public void preservesManagedSourceWhenPaperAddsARemappingSuffix() throws Exception {
        Path plugins = temporary.newFolder("managed-plugins").toPath();
        Path source = writeJar(plugins.resolve("Example.jar"), "one");
        Plugin first = plugin(source, "one");
        BileUtils.rememberRunningPlugin(first);
        Method metadataGetter = BileUtils.class.getDeclaredMethod("runningMetadata", Plugin.class);
        metadataGetter.setAccessible(true);
        Object metadata = metadataGetter.invoke(null, first);
        Path remapped = Files.createDirectories(plugins.resolve("runtime-plugins/.paper-remapped"));
        Path loaded = writeJar(remapped.resolve("RXhhbXBsZS5qYXI-12345678-1234-1234-1234-123456789012-1780000000.jar"), "one");
        Plugin replacement = plugin(loaded, "one");
        Method seed = BileUtils.class.getDeclaredMethod("rememberRunningMetadata", Plugin.class, metadata.getClass());
        seed.setAccessible(true);
        seed.invoke(null, replacement, metadata);

        BileUtils.rememberRunningPlugin(replacement);

        assertEquals(source.toFile(), BileUtils.getPluginFile(replacement));
    }

    @Test
    public void recoversCanonicalSourceFromRemappingCacheWithoutAnExistingOverride() throws Exception {
        Path remapped = temporary.newFolder("cache").toPath();
        Path loaded = writeJar(remapped.resolve("RXhhbXBsZS5qYXI-12345678-1234-1234-1234-123456789012-1780000000.jar"), "one");
        Plugin plugin = plugin(loaded, "one");

        BileUtils.rememberRunningPlugin(plugin);

        assertEquals(new File(BileUtils.getPluginsFolder(), "Example.jar"), BileUtils.getPluginFile(plugin));
    }

    private static boolean dependsOn(Plugin candidate, Plugin dependency) throws Exception {
        Method method = BileUtils.class.getDeclaredMethod("dependsOn", Plugin.class, Plugin.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, candidate, dependency);
    }

    private byte[] recoveryBytes() throws Exception {
        JarSnapshotStager.StagedJar retained = store.retain(NAME);
        try {
            return Files.readAllBytes(retained.staged());
        } finally {
            retained.delete();
        }
    }

    private static Map<?, ?> stateMap(String name) throws Exception {
        Field field = BileUtils.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static Plugin plugin(Path source, String version) throws Exception {
        return plugin(source, version, NAME);
    }

    private static Plugin plugin(Path source, String version, String name) throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        JavaPlugin plugin = (JavaPlugin) unsafe.allocateInstance(TestPlugin.class);
        Field description = JavaPlugin.class.getDeclaredField("description");
        description.setAccessible(true);
        PluginDescriptionFile metadata = new PluginDescriptionFile(name, version, "example.Main");
        description.set(plugin, metadata);
        Field pluginMeta = JavaPlugin.class.getDeclaredField("pluginMeta");
        pluginMeta.setAccessible(true);
        pluginMeta.set(plugin, metadata);
        Field file = JavaPlugin.class.getDeclaredField("file");
        file.setAccessible(true);
        file.set(plugin, source.toFile());
        return plugin;
    }

    private static final class TestPlugin extends JavaPlugin {
    }

    private static Path writeJar(Path source, String version) throws Exception {
        return writeJar(source, version, null);
    }

    private static Path writeJar(Path source, String version, String dependency) throws Exception {
        String dependencyDescriptor = dependency == null ? "" : "depend: [" + dependency + "]\n";
        try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(source))) {
            archive.putNextEntry(new ZipEntry("plugin.yml"));
            archive.write(("name: " + NAME + "\nmain: example.Main\nversion: " + version + "\n" + dependencyDescriptor)
                    .getBytes(StandardCharsets.UTF_8));
            archive.closeEntry();
        }
        return source;
    }
}
