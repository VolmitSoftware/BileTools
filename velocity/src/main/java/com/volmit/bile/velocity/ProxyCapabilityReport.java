package com.volmit.bile.velocity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public record ProxyCapabilityReport(Map<String, Boolean> capabilities, List<String> notes) {
    public static final List<String> LOAD_KEYS = List.of(
            "plugin-manager.maps",
            "plugin-loader.candidate",
            "plugin-loader.create",
            "plugin-loader.module",
            "event-manager.register-internally",
            "event-manager.scoped-fire");
    public static final List<String> UNLOAD_KEYS = List.of(
            "plugin-manager.maps",
            "classloader.close",
            "event-manager.scoped-fire",
            "container.executor",
            "command-manager.unregister");

    public ProxyCapabilityReport {
        capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        notes = List.copyOf(Objects.requireNonNull(notes, "notes"));
    }

    public boolean supports(String key) {
        return capabilities.getOrDefault(key, Boolean.FALSE);
    }

    public boolean supportsLoad() {
        return supportsAll(LOAD_KEYS);
    }

    public boolean supportsUnload() {
        return supportsAll(UNLOAD_KEYS);
    }

    public String summary() {
        StringJoiner missing = new StringJoiner(", ");
        for (Map.Entry<String, Boolean> entry : capabilities.entrySet()) {
            if (!entry.getValue()) {
                missing.add(entry.getKey());
            }
        }
        if (missing.length() == 0) {
            return "Proxy internals resolved: load and unload supported (" + capabilities.size() + " capabilities).";
        }
        return "Proxy internals partially resolved: load=" + supportsLoad() + " unload=" + supportsUnload()
                + " missing=[" + missing + "]";
    }

    private boolean supportsAll(List<String> keys) {
        for (String key : keys) {
            if (!supports(key)) {
                return false;
            }
        }
        return true;
    }
}
