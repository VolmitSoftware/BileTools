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
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class BileUtilsTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void validateRuntimeCompatibility_rejectsPaperOnlyJarOutsidePaper() throws InvalidPluginException {
        BileUtils.validateRuntimeCompatibility(true, true, "Example.jar");
        BileUtils.validateRuntimeCompatibility(false, false, "Example.jar");
        BileUtils.validateRuntimeCompatibility(false, true, "Example.jar");

        InvalidPluginException exception = assertThrows(InvalidPluginException.class,
                () -> BileUtils.validateRuntimeCompatibility(true, false, "Example.jar"));
        assertEquals(
                "Cannot load Example.jar: paper-plugin.yml-only jars require a Paper-compatible runtime",
                exception.getMessage());
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
