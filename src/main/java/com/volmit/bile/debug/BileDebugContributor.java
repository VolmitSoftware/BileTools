package com.volmit.bile.debug;

import art.arcane.volmlib.util.diagnostics.DebugDumpContributor;
import art.arcane.volmlib.util.localization.LanguageUpstreamSync;
import com.volmit.bile.BileTools;
import com.volmit.bile.localization.BileLocalization;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

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
                plugin.getLocalization().sourceDescription(),
                languageSourceState(plugin.getLocalization()),
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

    private static String languageSourceState(BileLocalization localization) {
        Optional<Throwable> failure = localization.sourceFailure();
        if (failure.isPresent()) {
            return "unavailable (" + failure.get().getClass().getSimpleName() + ")";
        }
        Optional<LanguageUpstreamSync.Status> status = localization.upstreamStatus();
        if (status.isEmpty()) {
            return "unavailable";
        }
        LanguageUpstreamSync.Status current = status.get();
        StringBuilder state = new StringBuilder();
        state.append(current.revision().map(sha -> "revision " + sha.substring(0, 7)).orElse("not checked"));
        if (current.checkedMillis() > 0L) {
            state.append(", checked ").append(Instant.ofEpochMilli(current.checkedMillis()));
        }
        current.lastError().ifPresent(error -> state.append(", last error: ").append(error));
        return state.toString();
    }
}
