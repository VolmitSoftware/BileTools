package com.volmit.bile;

import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
                api-version: 26.2
                folia-supported: true
                depend: [RuntimeDependency]
                """;
        String paperDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 26.2
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
                api-version: 26.2
                """;
        String paperDescriptor = """
                name: PaperName
                version: 1.0.0
                main: example.Plugin
                api-version: 26.2
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
    }

    @Test
    public void getPluginDescription_readsPaperDescriptorAndDependencies() throws Exception {
        File pluginJar = temporaryFolder.newFile("Example.jar");
        String descriptor = """
                name: Example
                version: 1.2.3
                main: example.Plugin
                api-version: 26.2
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
                api-version: 26.2
                depend: [LegacyRequired]
                softdepend: [SharedDependency, LegacyOptional]
                """;
        String paperDescriptor = """
                name: DualExample
                version: 2.0.0
                main: example.Plugin
                api-version: 26.2
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
    public void getPluginDescription_rejectsInvalidPaperDependencyMetadata() throws Exception {
        File pluginJar = temporaryFolder.newFile("InvalidExample.jar");
        String descriptor = """
                name: InvalidExample
                version: 1.0.0
                main: example.Plugin
                api-version: 26.2
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
                api-version: 26.2
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
}
