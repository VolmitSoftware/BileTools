package com.volmit.bile;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BileUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validateRuntimeCompatibility_limitsPaperRuntimeLoadsToDualDescriptorReloads() throws InvalidPluginException {
        BileUtils.validateRuntimeCompatibility(false, true, false, false, "Example.jar");
        BileUtils.validateRuntimeCompatibility(false, true, true, false, "Example.jar");
        BileUtils.validateRuntimeCompatibility(true, true, false, false, "Example.jar");
        BileUtils.validateRuntimeCompatibility(true, true, true, true, "Example.jar");

        BileUtils.RestartRequiredException firstLoad = assertThrows(BileUtils.RestartRequiredException.class,
                () -> BileUtils.validateRuntimeCompatibility(true, true, true, false, "Example.jar"));
        assertEquals(
                "Cannot hot-load Example.jar: Paper plugin entrypoints require startup; install the jar and perform a full server restart",
                firstLoad.getMessage());

        BileUtils.RestartRequiredException paperOnly = assertThrows(BileUtils.RestartRequiredException.class,
                () -> BileUtils.validateRuntimeCompatibility(true, false, true, true, "Example.jar"));
        assertEquals(
                "Cannot reload Example.jar: Paper-only plugins cannot register during runtime; a full server restart is required",
                paperOnly.getMessage());

        InvalidPluginException unsupportedServer = assertThrows(InvalidPluginException.class,
                () -> BileUtils.validateRuntimeCompatibility(true, false, false, true, "Example.jar"));
        assertEquals(
                "Cannot load Example.jar: paper-plugin.yml-only jars require a Paper-compatible server startup",
                unsupportedServer.getMessage());
    }

    @Test
    public void validateFoliaRuntimeCompatibility_requiresAuthoredPluginSupport() throws InvalidPluginException {
        BileUtils.validateFoliaRuntimeCompatibility(false, false, "Example.jar");
        BileUtils.validateFoliaRuntimeCompatibility(true, true, "Example.jar");

        BileUtils.RestartRequiredException unsupported = assertThrows(BileUtils.RestartRequiredException.class,
                () -> BileUtils.validateFoliaRuntimeCompatibility(true, false, "Example.jar"));
        assertEquals(
                "Cannot reload Example.jar through plugin.yml on Folia: folia-supported is not true; a full server restart is required",
                unsupported.getMessage());
    }

    @Test
    public void createRuntimePluginView_removesOnlyPaperDescriptor() throws Exception {
        File pluginJar = temporaryFolder.newFile("Dual+Example.jar");
        File runtimeDirectory = temporaryFolder.newFolder("runtime");
        String pluginDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 1.20
                folia-supported: true
                depend: [RuntimeDependency]
                """;
        String paperDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 1.20
                bootstrapper: example.Bootstrap
                dependencies:
                  bootstrap:
                    StartupDependency:
                      required: true
                """;
        byte[] binaryResource = new byte[]{0, 1, 2, 3, 127, -1};

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(pluginDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(paperDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("example/resource.bin"));
            output.write(binaryResource);
            output.closeEntry();
        }

        File runtimeJar = BileUtils.createRuntimePluginView(pluginJar, runtimeDirectory);

        assertFalse(pluginJar.getCanonicalFile().equals(runtimeJar.getCanonicalFile()));
        try (ZipFile source = new ZipFile(pluginJar); ZipFile runtime = new ZipFile(runtimeJar)) {
            assertTrue(source.getEntry("paper-plugin.yml") != null);
            assertTrue(source.getEntry("plugin.yml") != null);
            assertFalse(runtime.getEntry("paper-plugin.yml") != null);
            assertTrue(runtime.getEntry("plugin.yml") != null);
            byte[] sourcePluginDescriptor = source.getInputStream(source.getEntry("plugin.yml")).readAllBytes();
            byte[] runtimePluginDescriptor = runtime.getInputStream(runtime.getEntry("plugin.yml")).readAllBytes();
            byte[] runtimeResource = runtime.getInputStream(runtime.getEntry("example/resource.bin")).readAllBytes();
            assertArrayEquals(sourcePluginDescriptor, runtimePluginDescriptor);
            assertArrayEquals(binaryResource, runtimeResource);
        }
        assertEquals(List.of("RuntimeDependency"), BileUtils.getDependencies(runtimeJar));
        assertTrue(BileUtils.readPluginDescriptorFlag(runtimeJar, "folia-supported"));
        assertEquals("Dual+Example.jar", BileUtils.runtimeSourceBaseName(runtimeJar));
    }

    @Test
    public void readPaperPreferredPluginName_acceptsNonJarUpdatesAndPrefersPaperDescriptor() throws Exception {
        File updateArchive = temporaryFolder.newFile("Example.update");
        String pluginDescriptor = """
                name: LegacyName
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                """;
        String paperDescriptor = """
                name: PaperName
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(updateArchive))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(pluginDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(paperDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        assertEquals("PaperName", BileUtils.readPaperPreferredPluginName(updateArchive));
        assertTrue(BileUtils.pluginArchiveMatchesName(updateArchive, "LegacyName", false));
        assertFalse(BileUtils.pluginArchiveMatchesName(updateArchive, "PaperName", false));
        assertTrue(BileUtils.pluginArchiveMatchesName(updateArchive, "PaperName", true));
    }

    @Test
    public void getPluginDescription_readsPaperDescriptorAndDependencies() throws Exception {
        File pluginJar = temporaryFolder.newFile("Example.jar");
        String descriptor = """
                name: Example
                version: 1.2.3
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  server:
                    RequiredPlugin:
                      load: AFTER
                      required: true
                    PlaceholderAPI:
                      load: BEFORE
                      required: false
                    DefaultRequiredPlugin:
                      load: OMIT
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PluginDescriptionFile description = BileUtils.getPluginDescription(pluginJar);
        assertEquals("Example", description.getName());
        assertEquals("1.2.3", description.getVersion());
        assertEquals(List.of("RequiredPlugin", "DefaultRequiredPlugin"), BileUtils.getDependencies(pluginJar));
        assertEquals(List.of("PlaceholderAPI"), BileUtils.getSoftDependencies(pluginJar));
    }

    @Test
    public void getPluginDescription_mergesDualDescriptorsAndBootstrapDependencies() throws Exception {
        File pluginJar = temporaryFolder.newFile("DualExample.jar");
        String pluginDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 1.20
                depend: [LegacyRequired]
                softdepend: [SharedDependency, LegacyOptional]
                """;
        String paperDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  bootstrap:
                    BootstrapRequired:
                      required: true
                    SharedDependency:
                      required: true
                    BootstrapOptional:
                      required: false
                  server:
                    ServerRequired:
                      required: true
                    LegacyOptional:
                      required: true
                    BootstrapOptional:
                      required: false
                    ServerOptional:
                      required: false
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(pluginDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(paperDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PluginDescriptionFile description = BileUtils.getPluginDescription(pluginJar);
        assertEquals("DualExample", description.getName());
        assertEquals(
                List.of("LegacyRequired", "BootstrapRequired", "SharedDependency", "ServerRequired", "LegacyOptional"),
                BileUtils.getDependencies(pluginJar));
        assertEquals(
                List.of("BootstrapOptional", "ServerOptional"),
                BileUtils.getSoftDependencies(pluginJar));
    }

    @Test
    public void declaresDependency_readsPaperDependenciesFromTheSourceArtifact() throws Exception {
        File pluginJar = temporaryFolder.newFile("PaperDependent.jar");
        String pluginDescriptor = """
                name: PaperDependent
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                depend: [LegacyBase]
                """;
        String paperDescriptor = """
                name: PaperDependent
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  server:
                    LifecycleBase:
                      required: true
                    OptionalBase:
                      required: false
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("plugin.yml"));
            output.write(pluginDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(paperDescriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PluginDescriptionFile runtimeDescription = new PluginDescriptionFile(new StringReader(pluginDescriptor));
        assertTrue(BileUtils.declaresDependency(runtimeDescription, pluginJar, "LifecycleBase"));
        assertTrue(BileUtils.declaresDependency(runtimeDescription, pluginJar, "optionalbase"));
        assertFalse(BileUtils.declaresDependency(runtimeDescription, pluginJar, "UnrelatedPlugin"));
        assertFalse(BileUtils.declaresDependency(runtimeDescription, pluginJar, "LifecycleBase", false));
        assertTrue(BileUtils.declaresDependency(runtimeDescription, pluginJar, "LegacyBase", false));
    }

    @Test
    public void getPluginDescription_rejectsInvalidPaperDependencyMetadata() throws Exception {
        File pluginJar = temporaryFolder.newFile("InvalidExample.jar");
        String descriptor = """
                name: InvalidExample
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  server:
                    PlaceholderAPI:
                      required: sometimes
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        InvalidDescriptionException exception = assertThrows(InvalidDescriptionException.class,
                () -> BileUtils.getDependencies(pluginJar));
        assertEquals(
                "dependencies.server.PlaceholderAPI.required must be true or false in InvalidExample.jar",
                exception.getMessage());
    }

    @Test
    public void getPluginDescription_rejectsInvalidBootstrapDependencyMetadata() throws Exception {
        File pluginJar = temporaryFolder.newFile("InvalidBootstrapExample.jar");
        String descriptor = """
                name: InvalidBootstrapExample
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  bootstrap:
                    RegistryPlugin:
                      required: sometimes
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        InvalidDescriptionException exception = assertThrows(InvalidDescriptionException.class,
                () -> BileUtils.getDependencies(pluginJar));
        assertEquals(
                "dependencies.bootstrap.RegistryPlugin.required must be true or false in InvalidBootstrapExample.jar",
                exception.getMessage());
    }

    @Test
    public void getPluginDescription_acceptsEmptyPaperDependencySections() throws Exception {
        File pluginJar = temporaryFolder.newFile("EmptyDependencies.jar");
        String descriptor = """
                name: EmptyDependencies
                version: 1.0.0
                main: example.Plugin
                api-version: 1.20
                dependencies:
                  bootstrap: {}
                  server: {}
                """;

        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("paper-plugin.yml"));
            output.write(descriptor.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        PluginDescriptionFile description = BileUtils.getPluginDescription(pluginJar);
        assertEquals("EmptyDependencies", description.getName());
        assertTrue(BileUtils.getDependencies(pluginJar).isEmpty());
        assertTrue(BileUtils.getSoftDependencies(pluginJar).isEmpty());
    }

    @Test
    public void getPluginDescription_rejectsMissingDescriptors() throws Exception {
        File pluginJar = temporaryFolder.newFile("NoDescriptor.jar");
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(pluginJar))) {
            output.putNextEntry(new ZipEntry("example/resource.txt"));
            output.write("resource".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        InvalidDescriptionException exception = assertThrows(InvalidDescriptionException.class,
                () -> BileUtils.getPluginDescription(pluginJar));
        assertEquals("No plugin.yml or paper-plugin.yml found in NoDescriptor.jar", exception.getMessage());
    }

    @Test
    public void derivePluginsFolder_usesUpdateFolderParent() throws Exception {
        File pluginsFolder = temporaryFolder.newFolder("plugins");
        assertEquals(pluginsFolder, BileUtils.derivePluginsFolder(new File(pluginsFolder, "update")));
    }

    @Test
    public void derivePluginsFolder_usesDefaultWhenUpdateFolderHasNoParent() {
        assertEquals(new File("plugins"), BileUtils.derivePluginsFolder(new File("update")));
    }

    @Test
    public void derivePluginsFolder_usesDefaultWhenUpdateFolderIsUnavailable() {
        assertEquals(new File("plugins"), BileUtils.derivePluginsFolder(null));
    }

    @Test
    public void invokePluginsFolderApi_returnsFolderFromAvailableApi() throws Exception {
        Method method = PluginsFolderApiFixture.class.getMethod("pluginsFolder");
        assertEquals(new File("paper-plugins"), BileUtils.invokePluginsFolderApi(method));
    }

    @Test
    public void invokePluginsFolderApi_returnsNullWhenCapabilityIsUnavailable() {
        assertNull(BileUtils.invokePluginsFolderApi(null));
    }

    @Test
    public void invokePluginsFolderApi_returnsNullForUnexpectedReturnType() throws Exception {
        Method method = PluginsFolderApiFixture.class.getMethod("notAPluginsFolder");
        assertNull(BileUtils.invokePluginsFolderApi(method));
    }

    @Test
    public void invokePluginsFolderApi_returnsNullWhenInvocationFails() throws Exception {
        Method method = PluginsFolderApiFixture.class.getMethod("failingPluginsFolder");
        assertNull(BileUtils.invokePluginsFolderApi(method));
    }

    @Test
    public void scrubPluginCommands_survivesPoisonedCommandMapIteration_andRemovesDeclaredCommands() throws Exception {
        Plugin plugin = fakePlugin("Iris", """
                name: Iris
                version: 1.0.0
                main: example.Plugin
                commands:
                  iris: {}
                """);

        Map<String, Command> commands = poisonedCommandMap();
        commands.put("iris", namedCommand("iris"));
        commands.put("iris:iris", namedCommand("iris:iris"));
        commands.put("other", namedCommand("other"));

        BileUtils.scrubPluginCommands(plugin, new SimpleCommandMap(null), commands);

        assertFalse(commands.containsKey("iris"));
        assertFalse(commands.containsKey("iris:iris"));
        assertTrue(commands.containsKey("other"));
    }

    @Test
    public void removeApiNodesFromRoot_removesOwnedAndOrphanedNodes_keepsResolvableAndVanilla() {
        FakeRoot root = new FakeRoot();
        root.add(new FakeNode("iris", new FakeMeta(new FakePluginMeta("Iris"))));
        root.add(new FakeNode("iris:iris", new FakeMeta(new FakePluginMeta("Iris"))));
        root.add(new FakeNode("adapt", new FakeMeta(new FakePluginMeta("Adapt"))));
        root.add(new FakeNode("ghost", new FakeMeta(new FakePluginMeta("Ghost"))));
        root.add(new FakeNode("version", null));

        List<String> removed = BileUtils.removeApiNodesFromRoot(root, "Iris", owner -> owner.equals("Adapt"));

        assertEquals(List.of("iris", "iris:iris", "ghost"), removed);
        assertEquals(List.of("adapt", "version"), root.childNames());
    }

    private static Plugin fakePlugin(String name, String descriptorYaml) throws Exception {
        PluginDescriptionFile description = new PluginDescriptionFile(new StringReader(descriptorYaml));
        return (Plugin) Proxy.newProxyInstance(BileUtilsTest.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (Object proxy, Method method, Object[] args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "getDescription" -> description;
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Command namedCommand(String name) {
        return new Command(name) {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }

    /**
     * Mimics Paper's BukkitBrigForwardingMap when the dispatcher holds a node whose owning
     * plugin is no longer resolvable: any entry iteration explodes with an NPE from wrapNode.
     */
    private static Map<String, Command> poisonedCommandMap() {
        return new HashMap<>() {
            @Override
            public Set<Map.Entry<String, Command>> entrySet() {
                return new AbstractSet<>() {
                    @Override
                    public Iterator<Map.Entry<String, Command>> iterator() {
                        return new Iterator<>() {
                            @Override
                            public boolean hasNext() {
                                throw new NullPointerException("wrapNode: owning plugin not resolvable");
                            }

                            @Override
                            public Map.Entry<String, Command> next() {
                                throw new NullPointerException("wrapNode: owning plugin not resolvable");
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return 0;
                    }
                };
            }
        };
    }

    public static final class FakeRoot {
        private final Map<String, FakeNode> children = new LinkedHashMap<>();

        void add(FakeNode node) {
            children.put(node.getName(), node);
        }

        public Collection<FakeNode> getChildren() {
            return children.values();
        }

        public void removeCommand(String name) {
            children.remove(name);
        }

        List<String> childNames() {
            return List.copyOf(children.keySet());
        }
    }

    public static final class FakeNode {
        private final String name;
        private final FakeMeta apiCommandMeta;

        FakeNode(String name, FakeMeta apiCommandMeta) {
            this.name = name;
            this.apiCommandMeta = apiCommandMeta;
        }

        public String getName() {
            return name;
        }
    }

    public static final class FakeMeta {
        private final FakePluginMeta pluginMeta;

        FakeMeta(FakePluginMeta pluginMeta) {
            this.pluginMeta = pluginMeta;
        }

        public FakePluginMeta pluginMeta() {
            return pluginMeta;
        }
    }

    public static final class FakePluginMeta {
        private final String name;

        FakePluginMeta(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    public static final class PluginsFolderApiFixture {
        public static File pluginsFolder() {
            return new File("paper-plugins");
        }

        public static String notAPluginsFolder() {
            return "paper-plugins";
        }

        public static File failingPluginsFolder() {
            throw new IllegalStateException("unavailable");
        }
    }
}
