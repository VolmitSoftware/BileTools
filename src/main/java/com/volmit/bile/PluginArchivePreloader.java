package com.volmit.bile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class PluginArchivePreloader {
    private static final String REQUIRED_CLASS_PREFIX = "com.volmit.bile.";

    private PluginArchivePreloader() {
    }

    static PreloadReport preload(Path archive, ClassLoader classLoader) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        List<String> discoveredClasses = discoverClassNames(archive);
        List<String> loadedClasses = new ArrayList<>(discoveredClasses.size());
        List<ClassLoadFailure> requiredFailures = new ArrayList<>();
        List<ClassLoadFailure> optionalFailures = new ArrayList<>();
        for (String className : discoveredClasses) {
            try {
                Class.forName(className, false, classLoader);
                loadedClasses.add(className);
            } catch (ClassNotFoundException | LinkageError | SecurityException failure) {
                ClassLoadFailure classLoadFailure = new ClassLoadFailure(className, failure);
                if (isRequiredClass(className)) {
                    requiredFailures.add(classLoadFailure);
                } else {
                    optionalFailures.add(classLoadFailure);
                }
            }
        }
        return new PreloadReport(discoveredClasses, loadedClasses, requiredFailures, optionalFailures);
    }

    static List<String> discoverClassNames(Path archive) throws IOException {
        Path normalizedArchive = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedArchive)) {
            throw new IOException("Plugin startup archive is not a regular file: " + normalizedArchive);
        }

        Set<String> classNames = new LinkedHashSet<>();
        try (ZipFile jar = new ZipFile(normalizedArchive.toFile())) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entry.isDirectory()
                        || !entryName.endsWith(".class")
                        || entryName.startsWith("META-INF/")
                        || entryName.endsWith("module-info.class")) {
                    continue;
                }
                classNames.add(entryName.substring(0, entryName.length() - 6).replace('/', '.'));
            }
        }
        List<String> orderedClassNames = new ArrayList<>(classNames);
        Collections.sort(orderedClassNames);
        return List.copyOf(orderedClassNames);
    }

    private static boolean isRequiredClass(String className) {
        return className.startsWith(REQUIRED_CLASS_PREFIX);
    }

    record ClassLoadFailure(String className, Throwable cause) {
        ClassLoadFailure {
            className = Objects.requireNonNull(className, "className");
            cause = Objects.requireNonNull(cause, "cause");
        }
    }

    record PreloadReport(List<String> discoveredClasses,
                         List<String> loadedClasses,
                         List<ClassLoadFailure> requiredFailures,
                         List<ClassLoadFailure> optionalFailures) {
        PreloadReport {
            discoveredClasses = List.copyOf(discoveredClasses);
            loadedClasses = List.copyOf(loadedClasses);
            requiredFailures = List.copyOf(requiredFailures);
            optionalFailures = List.copyOf(optionalFailures);
        }
    }
}
