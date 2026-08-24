package com.volmit.bile;

import art.arcane.volmlib.integration.IntegrationHandshakeRequest;
import art.arcane.volmlib.integration.IntegrationHandshakeResponse;
import art.arcane.volmlib.integration.IntegrationHeartbeat;
import art.arcane.volmlib.integration.IntegrationMetricDescriptor;
import art.arcane.volmlib.integration.IntegrationMetricSample;
import art.arcane.volmlib.integration.IntegrationMetricSchema;
import art.arcane.volmlib.integration.IntegrationProtocolNegotiator;
import art.arcane.volmlib.integration.IntegrationProtocolVersion;
import art.arcane.volmlib.integration.IntegrationServiceContract;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class BileToolsIntegrationService implements IntegrationServiceContract {
    private static final Set<IntegrationProtocolVersion> SUPPORTED_PROTOCOLS = Set.of(
            new IntegrationProtocolVersion(1, 0),
            new IntegrationProtocolVersion(1, 1)
    );
    private static final Set<String> CAPABILITIES = Set.of(
            "handshake",
            "heartbeat",
            "metrics"
    );

    private final BileTools plugin;
    private volatile IntegrationProtocolVersion negotiatedProtocol = new IntegrationProtocolVersion(1, 1);

    public BileToolsIntegrationService(BileTools plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getServicesManager().register(IntegrationServiceContract.class, this, plugin, ServicePriority.Normal);
        BileTools.debug(() -> "Integration provider registered.");
    }

    public void unregister() {
        Bukkit.getServicesManager().unregister(IntegrationServiceContract.class, this);
    }

    @Override
    public String pluginId() {
        return "biletools";
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public Set<IntegrationProtocolVersion> supportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    public Set<String> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public Set<IntegrationMetricDescriptor> metricDescriptors() {
        return IntegrationMetricSchema.descriptors().stream()
                .filter(descriptor -> descriptor.key().startsWith("biletools."))
                .collect(Collectors.toSet());
    }

    @Override
    public IntegrationHandshakeResponse handshake(IntegrationHandshakeRequest request) {
        long now = System.currentTimeMillis();
        if (request == null) {
            return new IntegrationHandshakeResponse(
                    pluginId(), pluginVersion(), false, null,
                    SUPPORTED_PROTOCOLS, CAPABILITIES, "missing request", now
            );
        }

        Optional<IntegrationProtocolVersion> negotiated = IntegrationProtocolNegotiator.negotiate(
                SUPPORTED_PROTOCOLS,
                request.supportedProtocols()
        );
        if (negotiated.isEmpty()) {
            return new IntegrationHandshakeResponse(
                    pluginId(), pluginVersion(), false, null,
                    SUPPORTED_PROTOCOLS, CAPABILITIES, "no-common-protocol", now
            );
        }

        negotiatedProtocol = negotiated.get();
        return new IntegrationHandshakeResponse(
                pluginId(), pluginVersion(), true, negotiatedProtocol,
                SUPPORTED_PROTOCOLS, CAPABILITIES, "ok", now
        );
    }

    @Override
    public IntegrationHeartbeat heartbeat() {
        return new IntegrationHeartbeat(negotiatedProtocol, true, System.currentTimeMillis(), "ok");
    }

    @Override
    public Map<String, IntegrationMetricSample> sampleMetrics(Set<String> metricKeys) {
        Set<String> requested = metricKeys == null || metricKeys.isEmpty()
                ? IntegrationMetricSchema.biletoolsKeys()
                : metricKeys;
        long now = System.currentTimeMillis();
        Map<String, IntegrationMetricSample> out = new HashMap<>();
        BileTools bile = BileTools.bile;

        for (String key : requested) {
            switch (key) {
                case IntegrationMetricSchema.BILETOOLS_WATCHED_JARS ->
                        out.put(key, bile == null
                                ? notReady(key, now)
                                : available(key, bile.watchedJarCount(), now));
                case IntegrationMetricSchema.BILETOOLS_DIRTY_PLUGINS ->
                        out.put(key, bile == null
                                ? notReady(key, now)
                                : available(key, bile.dirtyPluginCount(), now));
                case IntegrationMetricSchema.BILETOOLS_RELOADS_TOTAL ->
                        out.put(key, bile == null
                                ? notReady(key, now)
                                : available(key, bile.reloadsTotal(), now));
                case IntegrationMetricSchema.BILETOOLS_LAST_RELOAD_MS ->
                        out.put(key, bile == null
                                ? notReady(key, now)
                                : available(key, bile.lastReloadMs(), now));
                case IntegrationMetricSchema.BILETOOLS_REMOTE_SLAVE_ONLINE ->
                        out.put(key, bile == null
                                ? notReady(key, now)
                                : available(key, bile.remoteSlaveOnline() ? 1 : 0, now));
                default -> out.put(key, IntegrationMetricSample.unavailable(
                        IntegrationMetricSchema.descriptor(key),
                        "unsupported-key",
                        now
                ));
            }
        }

        return out;
    }

    private IntegrationMetricSample notReady(String key, long now) {
        return IntegrationMetricSample.unavailable(IntegrationMetricSchema.descriptor(key), "plugin-not-ready", now);
    }

    private IntegrationMetricSample available(String key, double value, long now) {
        return IntegrationMetricSample.available(IntegrationMetricSchema.descriptor(key), value, now);
    }
}
