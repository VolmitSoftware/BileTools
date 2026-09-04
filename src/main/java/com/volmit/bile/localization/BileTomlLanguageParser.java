package com.volmit.bile.localization;

import art.arcane.volmlib.util.config.TomlCodec;
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.LinesValue;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.MessageValue;
import art.arcane.volmlib.util.localization.PluralKey;
import art.arcane.volmlib.util.localization.PluralValue;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.localization.TextValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

final class BileTomlLanguageParser {
    private BileTomlLanguageParser() {
    }

    static Map<String, MessageValue> parse(String raw, MessageCatalog catalog) throws IOException {
        JsonElement parsed = TomlCodec.toJsonElement(raw);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IOException("Language source is not a TOML table");
        }
        JsonObject root = parsed.getAsJsonObject();
        LinkedHashMap<String, MessageValue> values = new LinkedHashMap<>();
        for (MessageKey key : catalog.keys()) {
            JsonElement value = find(root, key.id());
            if (value != null) {
                values.put(key.id(), parseValue(key, value));
            }
        }
        return Map.copyOf(values);
    }

    private static JsonElement find(JsonObject object, String path) throws IOException {
        if (object.has(path)) {
            return object.get(path);
        }
        int separator = path.indexOf('.');
        if (separator < 0) {
            return null;
        }
        String segment = path.substring(0, separator);
        JsonElement child = object.get(segment);
        if (child == null) {
            return null;
        }
        if (!child.isJsonObject()) {
            throw new IOException("Language key collides with a non-table value: " + segment);
        }
        return find(child.getAsJsonObject(), path.substring(separator + 1));
    }

    private static MessageValue parseValue(MessageKey key, JsonElement value) throws IOException {
        if (key instanceof TextKey) {
            return new TextValue(requireText(key.id(), value));
        }
        if (key instanceof LinesKey) {
            return parseLines(key.id(), value);
        }
        if (key instanceof PluralKey) {
            return parsePlural(key.id(), value);
        }
        throw new IOException("Unsupported language message shape: " + key.id());
    }

    private static LinesValue parseLines(String key, JsonElement value) throws IOException {
        if (!value.isJsonArray()) {
            throw new IOException("Language value must be an array of text: " + key);
        }
        JsonArray array = value.getAsJsonArray();
        ArrayList<String> lines = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            lines.add(requireText(key, element));
        }
        return new LinesValue(lines);
    }

    private static PluralValue parsePlural(String key, JsonElement value) throws IOException {
        if (!value.isJsonObject()) {
            throw new IOException("Language plural value must be a table: " + key);
        }
        LinkedHashMap<String, String> forms = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            forms.put(entry.getKey(), requireText(key + "." + entry.getKey(), entry.getValue()));
        }
        return new PluralValue(forms);
    }

    private static String requireText(String key, JsonElement value) throws IOException {
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            throw new IOException("Language value must be text: " + key);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw new IOException("Language value must be text: " + key);
        }
        return primitive.getAsString();
    }
}
