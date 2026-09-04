package com.volmit.bile.debug;

import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import com.volmit.bile.BileTools;

import java.util.Objects;

public final class BileDebugContributor implements DebugDumpContributor {
    private final BileTools plugin;

    public BileDebugContributor(BileTools plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @Override
    public Report capture() {
        BileDebugSnapshot snapshot = new BileDebugSnapshot(
                plugin.schedulerName(),
                plugin.getLocalization().activeLocale(),
                plugin.getLocalization().availableLocales(),
                plugin.getLocalization().remoteCatalogFailure()
                        .map(failure -> "unavailable (" + failure.getClass().getSimpleName() + ")")
                        .orElse("ready"),
                plugin.getLocalization().remoteCatalogReference().orElse("unavailable"),
                BileTools.cfg,
                plugin.watchedJarCount(),
                plugin.dirtyPluginCount(),
                plugin.reloadsTotal(),
                plugin.lastReloadMs(),
                plugin.remoteSlaveOnline(),
                plugin.isWatcherActive(),
                plugin.isMetricsActive(),
                plugin.getDataFolder().toPath()
        );
        return () -> BileDebugReport.create(snapshot);
    }
}
