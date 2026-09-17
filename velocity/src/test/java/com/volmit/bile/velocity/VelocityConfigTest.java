package com.volmit.bile.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VelocityConfigTest {
    private static final String DEFAULT_DOCUMENT = """
            {
              "watcher": {
                "enabled": true,
                "idle-poll-millis": 1000,
                "active-poll-millis": 250,
                "fingerprint-debounce-polls": 8,
                "ignore": [],
                "only": []
              },
              "archive-plugins": true,
              "lifecycle": {
                "health-check": true,
                "operation-timeout-seconds": 120
              },
              "observability": {
                "log-timings": true
              },
              "notifications": {
                "players": true
              }
            }
            """;

    @TempDir
    Path directory;

    @Test
    public void writesEveryKeyInOrderWhenTheFileIsAbsent() throws Exception {
        Path file = directory.resolve("biletools").resolve("biletools.json");
        VelocityConfig config = VelocityConfig.load(file, logger());

        assertTrue(Files.isRegularFile(file));
        assertEquals(DEFAULT_DOCUMENT, Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(config.watcherEnabled());
        assertEquals(1000L, config.idlePollMillis());
        assertEquals(250L, config.activePollMillis());
        assertEquals(8, config.fingerprintDebouncePolls());
        assertEquals(List.of(), config.watcherIgnore());
        assertEquals(List.of(), config.watcherOnly());
        assertTrue(config.archivePlugins());
        assertTrue(config.healthCheck());
        assertTrue(config.logTimings());
        assertEquals(120L, config.lifecycleTimeoutSeconds());
        assertTrue(config.notifyPlayers());
    }

    @Test
    public void clampsValuesOutsideTheDocumentedRanges() throws Exception {
        Path file = directory.resolve("biletools.json");
        Files.writeString(file, """
                {
                  "watcher": {
                    "enabled": false,
                    "idle-poll-millis": 99999999,
                    "active-poll-millis": 1,
                    "fingerprint-debounce-polls": 900,
                    "ignore": ["Gloss"],
                    "only": []
                  },
                  "archive-plugins": false,
                  "lifecycle": {
                    "health-check": false,
                    "operation-timeout-seconds": 1
                  },
                  "observability": {
                    "log-timings": false
                  },
                  "notifications": {
                    "players": false
                  }
                }
                """, StandardCharsets.UTF_8);

        VelocityConfig config = VelocityConfig.load(file, logger());

        assertEquals(60000L, config.idlePollMillis());
        assertEquals(50L, config.activePollMillis());
        assertEquals(200, config.fingerprintDebouncePolls());
        assertEquals(5L, config.lifecycleTimeoutSeconds());
        assertEquals(List.of("Gloss"), config.watcherIgnore());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"idle-poll-millis\": 60000"));
    }

    @Test
    public void clampsTheActivePollToTheIdlePoll() throws Exception {
        Path file = directory.resolve("biletools.json");
        Files.writeString(file, """
                {"watcher": {"idle-poll-millis": 300, "active-poll-millis": 5000}}
                """, StandardCharsets.UTF_8);

        VelocityConfig config = VelocityConfig.load(file, logger());

        assertEquals(300L, config.idlePollMillis());
        assertEquals(300L, config.activePollMillis());
    }

    @Test
    public void dropsUnknownKeysAndRestoresMissingKeys() throws Exception {
        Path file = directory.resolve("biletools.json");
        Files.writeString(file, """
                {"watcher": {"enabled": false, "nonsense": 4}, "mystery": {"deep": true}}
                """, StandardCharsets.UTF_8);

        VelocityConfig config = VelocityConfig.load(file, logger());
        String written = Files.readString(file, StandardCharsets.UTF_8);

        assertEquals(false, config.watcherEnabled());
        assertEquals(1000L, config.idlePollMillis());
        assertTrue(config.archivePlugins());
        assertTrue(written.contains("\"enabled\": false"));
        assertTrue(written.contains("\"operation-timeout-seconds\": 120"));
        assertTrue(!written.contains("nonsense"));
        assertTrue(!written.contains("mystery"));
    }

    @Test
    public void rewritesAnUnparseableDocumentWithDefaults() throws Exception {
        Path file = directory.resolve("biletools.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        VelocityConfig config = VelocityConfig.load(file, logger());

        assertEquals(DEFAULT_DOCUMENT, Files.readString(file, StandardCharsets.UTF_8));
        assertTrue(config.watcherEnabled());
    }

    @Test
    public void reloadingTheWrittenDocumentIsByteStable() throws Exception {
        Path file = directory.resolve("biletools.json");
        VelocityConfig.load(file, logger());
        byte[] first = Files.readAllBytes(file);
        VelocityConfig.load(file, logger());

        assertArrayEquals(first, Files.readAllBytes(file));
    }

    private Logger logger() {
        return Mockito.mock(Logger.class);
    }
}
