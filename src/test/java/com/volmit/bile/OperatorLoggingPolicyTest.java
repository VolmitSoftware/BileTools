package com.volmit.bile;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OperatorLoggingPolicyTest {
    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/volmit/bile");

    @Test
    public void runtimeOutputUsesTheBileToolsLoggerExceptForTheBrandedSplash() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    boolean brandedSplash = file.endsWith("SplashScreen.java")
                            && line.contains("Bukkit.getConsoleSender().sendMessage(splash)");
                    if (line.contains("System.out")
                            || line.contains("System.err")
                            || line.contains(".printStackTrace(")
                            || (line.contains("getConsoleSender().sendMessage") && !brandedSplash)) {
                        violations.add(SOURCE_ROOT.relativize(file) + ":" + (index + 1) + " " + line.trim());
                    }
                }
            }
        }

        assertTrue(violations.toString(), violations.isEmpty());
    }

    @Test
    public void lifecycleUtilityUsesLevelledLogging() throws IOException {
        String source = Files.readString(SOURCE_ROOT.resolve("BileUtils.java"));

        assertFalse(source.contains("void stp("));
        assertFalse(source.contains("stp("));
        assertTrue(source.contains("BileTools.debug("));
        assertTrue(source.contains("BileTools.info("));
        assertTrue(source.contains("BileTools.warn("));
        assertTrue(source.contains("BileTools.severe("));
    }

    @Test
    public void watcherBookkeepingIsDebugOnly() throws IOException {
        String source = Files.readString(SOURCE_ROOT.resolve("BileTools.java"));

        assertTrue(source.contains("debug(() -> \"Tracking plugin jar "));
        assertTrue(source.contains("debug(() -> \"Queued automatic update for "));
        assertFalse(source.contains("getLogger().info(\"Now Tracking:"));
        assertFalse(source.contains("getLogger().info(\"Queued automatic update:"));
    }
}
