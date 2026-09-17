package com.volmit.bile.velocity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

final class JarFixtures {
    private JarFixtures() {
    }

    static Path jar(Path directory, String fileName, String descriptorJson) throws IOException {
        Files.createDirectories(directory);
        Path jar = directory.resolve(fileName);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), new Manifest())) {
            out.putNextEntry(new JarEntry("com/example/Marker.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
            if (descriptorJson != null) {
                out.putNextEntry(new JarEntry("velocity-plugin.json"));
                out.write(descriptorJson.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    static String descriptor(String id, String main) {
        return "{\"id\":\"" + id + "\",\"main\":\"" + main + "\"}";
    }

    static String descriptor(String id, String main, String dependenciesJson) {
        return "{\"id\":\"" + id + "\",\"main\":\"" + main + "\",\"dependencies\":" + dependenciesJson + "}";
    }
}
