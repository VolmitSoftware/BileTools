package com.volmit.bile.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityPluginDescriptorTest {
    @TempDir
    Path temp;

    @Test
    void readsEveryFieldFromAFullDescriptor() throws IOException {
        Path jar = JarFixtures.jar(temp, "full.jar", "{\"id\":\"demo\",\"name\":\"Demo Plugin\",\"version\":\"1.2.3\","
                + "\"main\":\"com.example.Demo\",\"dependencies\":[{\"id\":\"core\",\"optional\":false}]}");

        VelocityPluginDescriptor descriptor = VelocityPluginDescriptor.read(jar);

        assertEquals("demo", descriptor.id());
        assertEquals("Demo Plugin", descriptor.name());
        assertEquals("1.2.3", descriptor.version());
        assertEquals("com.example.Demo", descriptor.mainClass());
        assertEquals(List.of("core"), descriptor.requiredDependencies());
        assertEquals(List.of(), descriptor.optionalDependencies());
        assertEquals(jar, descriptor.source());
    }

    @Test
    void lowercasesTheId() throws IOException {
        Path jar = JarFixtures.jar(temp, "upper.jar", JarFixtures.descriptor("MiXeD", "com.example.Main"));

        assertEquals("mixed", VelocityPluginDescriptor.read(jar).id());
    }

    @Test
    void defaultsNameToIdAndVersionToUnknown() throws IOException {
        Path jar = JarFixtures.jar(temp, "bare.jar", JarFixtures.descriptor("bare", "com.example.Main"));

        VelocityPluginDescriptor descriptor = VelocityPluginDescriptor.read(jar);

        assertEquals("bare", descriptor.name());
        assertEquals("unknown", descriptor.version());
    }

    @Test
    void splitsDependenciesIntoRequiredAndOptional() throws IOException {
        Path jar = JarFixtures.jar(temp, "deps.jar", JarFixtures.descriptor("deps", "com.example.Main",
                "[{\"id\":\"core\"},{\"id\":\"extras\",\"optional\":true},{\"id\":\"api\",\"optional\":false}]"));

        VelocityPluginDescriptor descriptor = VelocityPluginDescriptor.read(jar);

        assertEquals(List.of("core", "api"), descriptor.requiredDependencies());
        assertEquals(List.of("extras"), descriptor.optionalDependencies());
    }

    @Test
    void throwsWhenTheDescriptorIsMissing() throws IOException {
        Path jar = JarFixtures.jar(temp, "bukkit.jar", null);

        IOException failure = assertThrows(IOException.class, () -> VelocityPluginDescriptor.read(jar));

        assertTrue(failure.getMessage().contains("missing velocity-plugin.json"), failure.getMessage());
    }

    @Test
    void throwsWhenTheMainClassIsMissing() throws IOException {
        Path jar = JarFixtures.jar(temp, "nomain.jar", "{\"id\":\"nomain\"}");

        IOException failure = assertThrows(IOException.class, () -> VelocityPluginDescriptor.read(jar));

        assertTrue(failure.getMessage().contains("main"), failure.getMessage());
    }

    @Test
    void throwsWhenTheDescriptorIsUnparseable() throws IOException {
        Path jar = JarFixtures.jar(temp, "broken.jar", "{ this is not json");

        assertThrows(IOException.class, () -> VelocityPluginDescriptor.read(jar));
    }

    @Test
    void tryReadReturnsEmptyForANonVelocityJar() throws IOException {
        Path jar = JarFixtures.jar(temp, "plain.jar", null);

        assertEquals(Optional.empty(), VelocityPluginDescriptor.tryRead(jar));
    }

    @Test
    void tryReadReturnsEmptyForAMissingFile() {
        assertEquals(Optional.empty(), VelocityPluginDescriptor.tryRead(temp.resolve("absent.jar")));
    }

    @Test
    void tryReadReturnsTheDescriptorForAVelocityJar() throws IOException {
        Path jar = JarFixtures.jar(temp, "ok.jar", JarFixtures.descriptor("ok", "com.example.Main"));

        Optional<VelocityPluginDescriptor> descriptor = VelocityPluginDescriptor.tryRead(jar);

        assertTrue(descriptor.isPresent());
        assertEquals("ok", descriptor.get().id());
    }

    @Test
    void readsADescriptorThatIsNotTheFirstEntry() throws IOException {
        Path jar = JarFixtures.jar(temp, "ordered.jar", JarFixtures.descriptor("ordered", "com.example.Main"));

        assertTrue(Files.size(jar) > 0);
        assertEquals("ordered", VelocityPluginDescriptor.read(jar).id());
    }
}
