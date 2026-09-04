package com.volmit.bile.debug;

import com.volmit.bile.config.BileConfig;

import java.nio.file.Path;
import java.util.List;

record BileDebugSnapshot(
        String scheduler,
        String activeLocale,
        List<String> availableLocales,
        String languageCatalogState,
        String languageCatalogReference,
        BileConfig config,
        int watchedJars,
        int dirtyPlugins,
        long completedReloads,
        long lastReloadMillis,
        boolean remoteReceiverOnline,
        boolean watcherActive,
        boolean metricsActive,
        Path dataDirectory
) {
    BileDebugSnapshot {
        availableLocales = List.copyOf(availableLocales);
        dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }
}
