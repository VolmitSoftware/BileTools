package com.volmit.bile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PluginArchivePreloaderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void preloadsNestedClassesAndRetainsOptionalFailureDetails() throws Exception {
        String outerClass = PluginArchivePreloaderTest.class.getName();
        String nestedClass = outerClass + "$Nested";
        String missingOptionalClass = "optional.integration.Missing";
        Path archive = createArchive(List.of(
                outerClass.replace('.', '/') + ".class",
                nestedClass.replace('.', '/') + ".class",
                missingOptionalClass.replace('.', '/') + ".class",
                "META-INF/versions/21/ignored.Versioned.class",
                "languages/de_DE.yml"));

        PluginArchivePreloader.PreloadReport report = PluginArchivePreloader.preload(
                archive, PluginArchivePreloaderTest.class.getClassLoader());

        assertTrue(report.discoveredClasses().contains(nestedClass));
        assertTrue(report.loadedClasses().contains(nestedClass));
        assertTrue(report.requiredFailures().isEmpty());
        assertEquals(1, report.optionalFailures().size());
        assertEquals(missingOptionalClass, report.optionalFailures().get(0).className());
        assertTrue(report.optionalFailures().get(0).cause() instanceof ClassNotFoundException);
    }

    @Test
    public void reportsAnUnresolvedBileToolsClassAsRequired() throws Exception {
        String missingRequiredClass = "com.volmit.bile.missing.Required";
        Path archive = createArchive(List.of(missingRequiredClass.replace('.', '/') + ".class"));

        PluginArchivePreloader.PreloadReport report = PluginArchivePreloader.preload(
                archive, PluginArchivePreloaderTest.class.getClassLoader());

        assertEquals(1, report.requiredFailures().size());
        assertEquals(missingRequiredClass, report.requiredFailures().get(0).className());
        assertTrue(report.optionalFailures().isEmpty());
    }

    @Test
    public void everyCompiledBileToolsClassIncludingWatcherSignalPreloadsSuccessfully() throws Exception {
        Path classesRoot = Path.of("build/classes/java/main");
        Path packageRoot = classesRoot.resolve("com/volmit/bile");
        List<String> classEntries = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(packageRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(classesRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .forEach(classEntries::add);
        }
        Path archive = createArchive(classEntries);

        PluginArchivePreloader.PreloadReport report = PluginArchivePreloader.preload(
                archive, PluginArchivePreloaderTest.class.getClassLoader());

        assertTrue(report.requiredFailures().toString(), report.requiredFailures().isEmpty());
        assertTrue(report.loadedClasses().contains("com.volmit.bile.watch.PluginJarDirectoryWatcher$Signal"));
        assertEquals(report.discoveredClasses().size(), report.loadedClasses().size());
    }

    @Test
    public void startupPreloadIsTheFirstEnableActionAndPrecedesWatcherInitialization() throws Exception {
        Path source = Path.of("src/main/java/com/volmit/bile/BileTools.java");
        String content = Files.readString(source, StandardCharsets.UTF_8);
        String enableStart = "public void onEnable() {\n        preloadSelfHostedArchive();";

        assertTrue(content.contains(enableStart));
        assertTrue(content.indexOf("preloadSelfHostedArchive();") < content.indexOf("initializePluginWatcher();"));
    }

    private Path createArchive(List<String> entries) throws Exception {
        Path archive = temporaryFolder.newFile("preload-" + System.nanoTime() + ".jar").toPath();
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (String entryName : entries) {
                output.putNextEntry(new ZipEntry(entryName));
                output.closeEntry();
            }
        }
        return archive;
    }

    private static final class Nested {
    }
}
