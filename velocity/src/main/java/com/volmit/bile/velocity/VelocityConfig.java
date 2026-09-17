package com.volmit.bile.velocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class VelocityConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long DEFAULT_IDLE_POLL_MILLIS = 1000L;
    private static final long DEFAULT_ACTIVE_POLL_MILLIS = 250L;
    private static final int DEFAULT_DEBOUNCE_POLLS = 8;
    private static final long DEFAULT_TIMEOUT_SECONDS = 120L;
    private static final long MINIMUM_IDLE_POLL_MILLIS = 100L;
    private static final long MAXIMUM_IDLE_POLL_MILLIS = 60000L;
    private static final long MINIMUM_ACTIVE_POLL_MILLIS = 50L;
    private static final int MINIMUM_DEBOUNCE_POLLS = 1;
    private static final int MAXIMUM_DEBOUNCE_POLLS = 200;
    private static final long MINIMUM_TIMEOUT_SECONDS = 5L;
    private static final long MAXIMUM_TIMEOUT_SECONDS = 3600L;

    private final boolean watcherEnabled;
    private final long idlePollMillis;
    private final long activePollMillis;
    private final int fingerprintDebouncePolls;
    private final List<String> watcherIgnore;
    private final List<String> watcherOnly;
    private final boolean archivePlugins;
    private final boolean healthCheck;
    private final boolean logTimings;
    private final long lifecycleTimeoutSeconds;
    private final boolean notifyPlayers;

    private VelocityConfig(JsonObject document) {
        JsonObject watcher = childObject(document, "watcher");
        JsonObject lifecycle = childObject(document, "lifecycle");
        JsonObject observability = childObject(document, "observability");
        JsonObject notifications = childObject(document, "notifications");
        this.watcherEnabled = booleanValue(watcher, "enabled", true);
        this.idlePollMillis = clamp(longValue(watcher, "idle-poll-millis", DEFAULT_IDLE_POLL_MILLIS),
                MINIMUM_IDLE_POLL_MILLIS, MAXIMUM_IDLE_POLL_MILLIS);
        this.activePollMillis = clamp(longValue(watcher, "active-poll-millis", DEFAULT_ACTIVE_POLL_MILLIS),
                MINIMUM_ACTIVE_POLL_MILLIS, idlePollMillis);
        this.fingerprintDebouncePolls = (int) clamp(longValue(watcher, "fingerprint-debounce-polls", DEFAULT_DEBOUNCE_POLLS),
                MINIMUM_DEBOUNCE_POLLS, MAXIMUM_DEBOUNCE_POLLS);
        this.watcherIgnore = stringList(watcher, "ignore");
        this.watcherOnly = stringList(watcher, "only");
        this.archivePlugins = booleanValue(document, "archive-plugins", true);
        this.healthCheck = booleanValue(lifecycle, "health-check", true);
        this.lifecycleTimeoutSeconds = clamp(longValue(lifecycle, "operation-timeout-seconds", DEFAULT_TIMEOUT_SECONDS),
                MINIMUM_TIMEOUT_SECONDS, MAXIMUM_TIMEOUT_SECONDS);
        this.logTimings = booleanValue(observability, "log-timings", true);
        this.notifyPlayers = booleanValue(notifications, "players", true);
    }

    public static VelocityConfig load(Path file, Logger logger) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(logger, "logger");
        VelocityConfig config = new VelocityConfig(readDocument(file, logger));
        try {
            write(file, config.toDocument());
        } catch (IOException exception) {
            logger.warn("Could not write the BileTools proxy configuration to {}", file, exception);
        }
        return config;
    }

    public boolean watcherEnabled() {
        return watcherEnabled;
    }

    public long idlePollMillis() {
        return idlePollMillis;
    }

    public long activePollMillis() {
        return activePollMillis;
    }

    public int fingerprintDebouncePolls() {
        return fingerprintDebouncePolls;
    }

    public List<String> watcherIgnore() {
        return watcherIgnore;
    }

    public List<String> watcherOnly() {
        return watcherOnly;
    }

    public boolean archivePlugins() {
        return archivePlugins;
    }

    public boolean healthCheck() {
        return healthCheck;
    }

    public boolean logTimings() {
        return logTimings;
    }

    public long lifecycleTimeoutSeconds() {
        return lifecycleTimeoutSeconds;
    }

    public boolean notifyPlayers() {
        return notifyPlayers;
    }

    private static JsonObject readDocument(Path file, Logger logger) {
        if (!Files.isRegularFile(file)) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
            logger.warn("BileTools proxy configuration {} is not a JSON object; restoring defaults", file);
        } catch (IOException | JsonParseException exception) {
            logger.warn("Could not read the BileTools proxy configuration {}; restoring defaults", file, exception);
        }
        return new JsonObject();
    }

    private static void write(Path file, JsonObject document) throws IOException {
        Path directory = file.toAbsolutePath().normalize().getParent();
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(document) + "\n", StandardCharsets.UTF_8);
            move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonObject childObject(JsonObject document, String key) {
        JsonElement element = document.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static boolean booleanValue(JsonObject document, String key, boolean fallback) {
        JsonPrimitive primitive = primitive(document, key);
        return primitive != null && primitive.isBoolean() ? primitive.getAsBoolean() : fallback;
    }

    private static long longValue(JsonObject document, String key, long fallback) {
        JsonPrimitive primitive = primitive(document, key);
        return primitive != null && primitive.isNumber() ? primitive.getAsLong() : fallback;
    }

    private static JsonPrimitive primitive(JsonObject document, String key) {
        JsonElement element = document.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsJsonPrimitive() : null;
    }

    private static List<String> stringList(JsonObject document, String key) {
        JsonElement element = document.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                continue;
            }
            String value = entry.getAsString().trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private JsonObject toDocument() {
        JsonObject watcher = new JsonObject();
        watcher.addProperty("enabled", watcherEnabled);
        watcher.addProperty("idle-poll-millis", idlePollMillis);
        watcher.addProperty("active-poll-millis", activePollMillis);
        watcher.addProperty("fingerprint-debounce-polls", fingerprintDebouncePolls);
        watcher.add("ignore", toArray(watcherIgnore));
        watcher.add("only", toArray(watcherOnly));

        JsonObject lifecycle = new JsonObject();
        lifecycle.addProperty("health-check", healthCheck);
        lifecycle.addProperty("operation-timeout-seconds", lifecycleTimeoutSeconds);

        JsonObject observability = new JsonObject();
        observability.addProperty("log-timings", logTimings);

        JsonObject notifications = new JsonObject();
        notifications.addProperty("players", notifyPlayers);

        JsonObject document = new JsonObject();
        document.add("watcher", watcher);
        document.addProperty("archive-plugins", archivePlugins);
        document.add("lifecycle", lifecycle);
        document.add("observability", observability);
        document.add("notifications", notifications);
        return document;
    }

    private JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray(values.size());
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
