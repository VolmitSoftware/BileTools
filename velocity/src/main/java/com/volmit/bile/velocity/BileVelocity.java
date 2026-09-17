package com.volmit.bile.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

@Plugin(id = "biletools", name = "BileTools", version = "@VERSION@",
        description = "Hot reload for proxy plugins", authors = {"VolmitSoftware"})
public final class BileVelocity {
    private static final String UNKNOWN_VERSION = "unknown";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityWatchOrchestrator orchestrator;

    @Inject
    public BileVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    @Subscribe
    public void initialize(ProxyInitializeEvent event) {
        VelocityWatchOrchestrator started = null;
        try {
            VelocityConfig config = VelocityConfig.load(dataDirectory.resolve("biletools.json"), logger);
            ProxyInternals internals = ProxyInternals.resolve(proxy, logger);
            reportCapabilities(internals.report());

            VelocityPluginHotloader hotloader = new VelocityPluginHotloader(proxy, logger, dataDirectory, this,
                    internals, new HotloadOptions(config.healthCheck(), config.logTimings(), config.archivePlugins(),
                    Duration.ofSeconds(config.lifecycleTimeoutSeconds())));
            ProxyMessages messages = new ProxyMessages(proxy, logger, config.notifyPlayers());
            Path pluginsDirectory = dataDirectory.toAbsolutePath().normalize().getParent();
            started = new VelocityWatchOrchestrator(proxy, this, logger, pluginsDirectory, dataDirectory, config,
                    hotloader, messages);
            if (config.watcherEnabled()) {
                started.start();
            } else {
                logger.info("Automatic proxy plugin watching is disabled; use /bile to load, unload and reload.");
            }

            CommandMeta meta = proxy.getCommandManager().metaBuilder("biletools").aliases("bile").plugin(this).build();
            proxy.getCommandManager().register(meta,
                    new BileVelocityCommand(started, hotloader, messages, pluginsDirectory, version()));
            orchestrator = started;
        } catch (Throwable throwable) {
            if (started != null) {
                started.close();
            }
            orchestrator = null;
            logger.error("BileTools proxy support failed to start; the plugin stays inert", throwable);
        }
    }

    @Subscribe
    public void shutdown(ProxyShutdownEvent event) {
        VelocityWatchOrchestrator current = orchestrator;
        orchestrator = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
            VelocityWatchOrchestrator.OrchestratorSnapshot snapshot = current.snapshot();
            logger.info("BileTools proxy support stopped ({} watched jars, {} reloads)",
                    snapshot.watchedJars(), snapshot.reloadsTotal());
        } catch (RuntimeException exception) {
            logger.error("BileTools proxy support did not stop cleanly", exception);
        }
    }

    private void reportCapabilities(ProxyCapabilityReport report) {
        if (report.supportsLoad() && report.supportsUnload()) {
            logger.info(report.summary());
            return;
        }
        logger.warn(report.summary());
        for (String note : report.notes()) {
            logger.warn("Proxy internals: {}", note);
        }
    }

    private String version() {
        Optional<PluginContainer> container = proxy.getPluginManager().fromInstance(this);
        if (container.isEmpty()) {
            return UNKNOWN_VERSION;
        }
        return container.get().getDescription().getVersion().orElse(UNKNOWN_VERSION);
    }
}
