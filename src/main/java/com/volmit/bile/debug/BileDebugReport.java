package com.volmit.bile.debug;

import art.arcane.volmlib.util.diagnostics.DebugDumpReport;
import com.volmit.bile.config.BileConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class BileDebugReport {
    private BileDebugReport() {
    }

    static String create(BileDebugSnapshot snapshot) {
        StringBuilder report = new StringBuilder(8_192);
        section(report, "BileTools services");
        value(report, "Java bytecode target", 17);
        value(report, "Scheduler", snapshot.scheduler());
        value(report, "Active locale", snapshot.activeLocale());
        value(report, "Available locales", String.join(", ", snapshot.availableLocales()));
        value(report, "Language catalog", snapshot.languageCatalogState());
        value(report, "Language source reference", snapshot.languageCatalogReference());
        value(report, "Plugin watcher", snapshot.watcherActive() ? "active" : "inactive");
        value(report, "Watched jars", snapshot.watchedJars());
        value(report, "Dirty plugins", snapshot.dirtyPlugins());
        value(report, "Completed reloads", snapshot.completedReloads());
        value(report, "Last reload milliseconds", snapshot.lastReloadMillis());
        value(report, "Remote receiver", snapshot.remoteReceiverOnline() ? "online" : "offline");
        value(report, "bStats integration", snapshot.metricsActive() ? "initialized" : "not initialized");
        appendConfig(report, snapshot.config());
        section(report, "BileTools files");
        report.append(DebugDumpReport.describeFiles(snapshot.dataDirectory(), List.of(
                Path.of("biletools.yml"),
                Path.of("languages", snapshot.activeLocale() + ".toml"),
                Path.of("languages", "language-preferences.properties"),
                Path.of("watcher-handoff.bin"),
                Path.of("watcher-handoff-completion.lock")
        )));
        return report.toString();
    }

    private static void appendConfig(StringBuilder report, BileConfig config) {
        section(report, "Effective BileTools configuration");
        value(report, "language", config.getLanguage());
        value(report, "metrics", config.isMetrics());
        value(report, "archive-plugins", config.isArchivePlugins());
        value(report, "remote-deploy.slave.slave-enabled", config.isRemoteSlaveEnabled());
        value(report, "remote-deploy.slave.slave-port", config.getRemoteSlavePort());
        value(report, "remote-deploy.slave.slave-payload", "redacted");
        value(report, "remote-deploy.master.master-enabled", config.isRemoteMasterEnabled());
        value(report, "remote-deploy.master.master-deploy-to", redactedTargets(config.getRemoteMasterDeployTargets()));
        value(report, "remote-deploy.master.master-deploy-signatures", String.join(", ", config.getRemoteMasterDeploySignatures()));
        value(report, "remote-deploy.socket-timeout-ms", config.getRemoteSocketTimeoutMs());
        value(report, "remote-deploy.max-transfer-bytes", config.getRemoteMaxTransferBytes());
        value(report, "watcher.idle-poll-ticks", config.getWatcherIdlePollTicks());
        value(report, "watcher.active-poll-ticks", config.getWatcherActivePollTicks());
        value(report, "watcher.fingerprint-debounce-ticks", config.getWatcherFingerprintDebounceTicks());
        value(report, "watcher.ignore", String.join(", ", config.getWatcherIgnore()));
        value(report, "watcher.only", String.join(", ", config.getWatcherOnly()));
        value(report, "observability.log-timings", config.isLogTimings());
        value(report, "lifecycle.health-check", config.isHealthCheck());
    }

    private static String redactedTargets(List<String> targets) {
        ArrayList<String> redacted = new ArrayList<>(targets.size());
        for (String target : targets) {
            String[] parts = target.split(":", 3);
            redacted.add(parts.length >= 2 ? parts[0] + ":" + parts[1] + ":redacted" : "invalid target");
        }
        return redacted.isEmpty() ? "none" : String.join(", ", redacted);
    }

    private static void section(StringBuilder report, String name) {
        if (!report.isEmpty()) {
            report.append('\n');
        }
        report.append("== ").append(sanitize(name)).append(" ==\n");
    }

    private static void value(StringBuilder report, String name, Object value) {
        report.append(sanitize(name)).append(": ")
                .append(sanitize(Objects.toString(value, "unavailable"))).append('\n');
    }

    private static String sanitize(String value) {
        return Objects.requireNonNullElse(value, "unavailable")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .trim();
    }
}
