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
    public void runtimeOutputUsesSharedComponentSinks() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.contains("System.out")
                            || line.contains("System.err")
                            || line.contains(".printStackTrace(")
                            || line.contains(".sendMessage(")) {
                        violations.add(SOURCE_ROOT.relativize(file) + ":" + (index + 1) + " " + line.trim());
                    }
                }
            }
        }

        assertTrue(violations.toString(), violations.isEmpty());
        String splash = Files.readString(SOURCE_ROOT.resolve("SplashScreen.java"));
        assertTrue(splash.contains("BileTools.logLegacy(Level.INFO, splash, null)"));
        assertFalse(splash.contains("Bukkit.getConsoleSender()"));
        String localization = Files.readString(SOURCE_ROOT.resolve("localization/BileLocalization.java"));
        assertTrue(localization.contains("render(selectedSnapshot"));
        assertTrue(localization.contains("MessageArgumentKind.UNTRUSTED"));
        assertTrue(localization.contains("Remote language catalog is unavailable; code-owned English and installed local language files remain active"));
        assertTrue(localization.contains("Configured language could not be loaded; BileTools is continuing with code-owned English"));
        assertFalse(localization.contains("BileTools remote language catalog is unavailable"));
        assertFalse(localization.contains("ChatColor.translateAlternateColorCodes"));
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
