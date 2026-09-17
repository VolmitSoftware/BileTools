package com.volmit.bile.velocity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public record VelocityPluginDescriptor(String id, String name, String version, String mainClass,
                                       List<String> requiredDependencies, List<String> optionalDependencies, Path source) {
    private static final String ENTRY = "velocity-plugin.json";
    private static final String UNKNOWN_VERSION = "unknown";

    public VelocityPluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(mainClass, "mainClass");
        Objects.requireNonNull(source, "source");
        requiredDependencies = List.copyOf(Objects.requireNonNull(requiredDependencies, "requiredDependencies"));
        optionalDependencies = List.copyOf(Objects.requireNonNull(optionalDependencies, "optionalDependencies"));
    }

    public static VelocityPluginDescriptor read(Path jar) throws IOException {
        Objects.requireNonNull(jar, "jar");
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(ENTRY);
            if (entry == null) {
                throw new IOException("missing velocity-plugin.json in " + jar);
            }
            try (InputStream in = file.getInputStream(entry);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(root(reader, jar), jar);
            }
        }
    }

    public static Optional<VelocityPluginDescriptor> tryRead(Path jar) {
        try {
            return Optional.of(read(jar));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static JsonObject root(Reader reader, Path jar) throws IOException {
        try {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IOException("velocity-plugin.json in " + jar + " is not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("unparseable velocity-plugin.json in " + jar, e);
        }
    }

    private static VelocityPluginDescriptor parse(JsonObject root, Path jar) throws IOException {
        String id = text(root, "id");
        if (id == null) {
            throw new IOException("velocity-plugin.json in " + jar + " has no id");
        }
        String mainClass = text(root, "main");
        if (mainClass == null) {
            throw new IOException("velocity-plugin.json in " + jar + " has no main class");
        }
        String normalizedId = id.toLowerCase(Locale.ROOT);
        String name = text(root, "name");
        String version = text(root, "version");
        List<String> required = new ArrayList<>();
        List<String> optional = new ArrayList<>();
        collectDependencies(root, required, optional);
        return new VelocityPluginDescriptor(normalizedId, name == null ? normalizedId : name,
                version == null ? UNKNOWN_VERSION : version, mainClass, required, optional, jar);
    }

    private static void collectDependencies(JsonObject root, List<String> required, List<String> optional) {
        JsonElement dependencies = root.get("dependencies");
        if (dependencies == null || !dependencies.isJsonArray()) {
            return;
        }
        JsonArray entries = dependencies.getAsJsonArray();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject dependency = element.getAsJsonObject();
            String dependencyId = text(dependency, "id");
            if (dependencyId == null) {
                continue;
            }
            if (flag(dependency, "optional")) {
                optional.add(dependencyId.toLowerCase(Locale.ROOT));
            } else {
                required.add(dependencyId.toLowerCase(Locale.ROOT));
            }
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value.isBlank() ? null : value;
    }

    private static boolean flag(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }
}
