package com.volmit.bile.velocity;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyCapabilityReportTest {
    @Test
    void supportsLoadAndUnloadWhenEveryKeyIsTrue() {
        ProxyCapabilityReport report = new ProxyCapabilityReport(all(true), List.of());

        assertTrue(report.supportsLoad());
        assertTrue(report.supportsUnload());
    }

    @Test
    void refusesLoadWhenALoadKeyIsMissing() {
        Map<String, Boolean> capabilities = all(true);
        capabilities.put("plugin-loader.create", Boolean.FALSE);

        ProxyCapabilityReport report = new ProxyCapabilityReport(capabilities, List.of());

        assertFalse(report.supportsLoad());
        assertTrue(report.supportsUnload());
    }

    @Test
    void refusesUnloadWhenAnUnloadKeyIsMissing() {
        Map<String, Boolean> capabilities = all(true);
        capabilities.put("command-manager.unregister", Boolean.FALSE);

        ProxyCapabilityReport report = new ProxyCapabilityReport(capabilities, List.of());

        assertFalse(report.supportsUnload());
        assertTrue(report.supportsLoad());
    }

    @Test
    void classLoaderRegistryIsInformationalAndNeverGatesUnload() {
        Map<String, Boolean> capabilities = all(true);
        capabilities.put("classloader.registry", Boolean.FALSE);

        ProxyCapabilityReport report = new ProxyCapabilityReport(capabilities, List.of());

        assertTrue(report.supportsUnload());
        assertTrue(report.supportsLoad());
    }

    @Test
    void unknownKeysAreTreatedAsUnsupported() {
        ProxyCapabilityReport report = new ProxyCapabilityReport(Map.of(), List.of());

        assertFalse(report.supports("plugin-manager.maps"));
        assertFalse(report.supportsLoad());
        assertFalse(report.supportsUnload());
    }

    @Test
    void summaryNamesEveryMissingCapability() {
        Map<String, Boolean> capabilities = all(true);
        capabilities.put("event-manager.scoped-fire", Boolean.FALSE);
        capabilities.put("container.executor", Boolean.FALSE);

        String summary = new ProxyCapabilityReport(capabilities, List.of()).summary();

        assertTrue(summary.contains("event-manager.scoped-fire"), summary);
        assertTrue(summary.contains("container.executor"), summary);
        assertFalse(summary.contains("\n"), summary);
    }

    @Test
    void summaryIsASingleLineWhenEverythingResolved() {
        String summary = new ProxyCapabilityReport(all(true), List.of()).summary();

        assertFalse(summary.contains("\n"), summary);
        assertFalse(summary.contains("missing"), summary);
    }

    private static Map<String, Boolean> all(boolean value) {
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (String key : List.of("plugin-manager.maps", "plugin-loader.candidate", "plugin-loader.create",
                "plugin-loader.module", "classloader.close", "classloader.registry",
                "event-manager.register-internally", "event-manager.scoped-fire", "container.executor",
                "command-manager.unregister")) {
            capabilities.put(key, value);
        }
        return capabilities;
    }
}
