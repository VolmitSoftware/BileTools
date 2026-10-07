package com.volmit.bile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class RuntimeDataDirectoryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preservesExistingDataAndWritesAcrossRepeatedLoads() throws IOException {
        Path plugins = temporaryFolder.newFolder("plugins").toPath();
        Path runtime = plugins.resolve("BileTools/runtime-plugins");
        Files.createDirectories(plugins.resolve("Example"));
        Files.writeString(plugins.resolve("Example/config.yml"), "enabled: true");
        RuntimeDataDirectory.prepare(runtime, plugins, "Example");
        assertEquals("enabled: true", Files.readString(runtime.resolve("Example/config.yml")));
        Files.writeString(runtime.resolve("Example/state.txt"), "saved");
        RuntimeDataDirectory.prepare(runtime, plugins, "Example");
        assertEquals("saved", Files.readString(plugins.resolve("Example/state.txt")));
    }

    @Test
    public void refusesAnUnrelatedRuntimeDataDirectory() throws IOException {
        Path plugins = temporaryFolder.newFolder("plugins").toPath();
        Path runtime = plugins.resolve("BileTools/runtime-plugins");
        Files.createDirectories(runtime.resolve("Example"));
        assertThrows(IOException.class, () -> RuntimeDataDirectory.prepare(runtime, plugins, "Example"));
        assertThrows(IOException.class, () -> RuntimeDataDirectory.prepare(runtime, plugins, "../Example"));
    }
}
