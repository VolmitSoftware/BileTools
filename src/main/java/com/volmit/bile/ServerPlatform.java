package com.volmit.bile;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Server;

/**
 * Runtime detection for Spigot, Paper-family, and Folia-family servers.
 *
 * <pre>
 * Spigot          - Classic Bukkit scheduler + SimplePluginManager
 * Paper           - Paper plugin manager, paper-plugin.yml, GlobalRegionScheduler API stubs
 * Purpur          - Paper fork
 * Leaf            - Paper fork (+ Purpur patches)
 * Folia           - Paper fork with regionized threading
 * Canvas          - Folia fork
 * </pre>
 */
public final class ServerPlatform {
    public enum Family {
        SPIGOT,
        PAPER,
        PURPUR,
        LEAF,
        FOLIA,
        CANVAS,
        UNKNOWN
    }

    private static final boolean PAPER_RUNTIME = detectPaperRuntime(
            classPresent("io.papermc.paper.plugin.configuration.PluginMeta"),
            classPresent("io.papermc.paper.ServerBuildInfo"),
            classPresent("io.papermc.paper.plugin.manager.PaperPluginManagerImpl"));
    private static final boolean PAPER_SERVER = PAPER_RUNTIME;
    private static final boolean PURPUR = classPresent("org.purpurmc.purpur.PurpurConfig")
            || classPresent("org.purpurmc.purpur.PurpurServer");
    private static final boolean LEAF = classPresent("org.leavesmc.leaves.LeavesConfig")
            || classPresent("org.dreeam.leaf.config.LeafConfig")
            || classPresent("org.leafmc.leaf.LeafConfig")
            || brandContains("leaf");
    private static final boolean CANVAS = classPresent("io.canvasmc.canvas.CanvasConfig")
            || classPresent("io.canvasmc.server.CanvasServer")
            || brandContains("canvas");
    private static final boolean FOLIA_REGIONIZED = classPresent("io.papermc.paper.threadedregions.RegionizedServer");

    private static volatile Family cachedFamily;
    private static volatile String cachedSummary;

    private ServerPlatform() {
    }

    public static Family family() {
        Family local = cachedFamily;
        if (local != null) {
            return local;
        }

        synchronized (ServerPlatform.class) {
            if (cachedFamily != null) {
                return cachedFamily;
            }

            cachedFamily = classify(
                    isFoliaFamily(),
                    CANVAS || brandContains("canvas"),
                    LEAF,
                    PURPUR,
                    PAPER_SERVER);

            return cachedFamily;
        }
    }

    public static boolean isPaperFamily() {
        Family f = family();
        return f != Family.SPIGOT && f != Family.UNKNOWN;
    }

    public static boolean isPaperRuntime() {
        return PAPER_RUNTIME;
    }

    static boolean detectPaperRuntime(boolean pluginMetaPresent,
                                      boolean serverBuildInfoPresent,
                                      boolean pluginManagerPresent) {
        return pluginMetaPresent || serverBuildInfoPresent || pluginManagerPresent;
    }

    static Family classify(boolean folia,
                           boolean canvas,
                           boolean leaf,
                           boolean purpur,
                           boolean paper) {
        if (folia) {
            return canvas ? Family.CANVAS : Family.FOLIA;
        }
        if (leaf) {
            return Family.LEAF;
        }
        if (purpur) {
            return Family.PURPUR;
        }
        return paper ? Family.PAPER : Family.SPIGOT;
    }

    /**
     * True when regionized threading is active (Folia / Canvas).
     * Classic Bukkit scheduler must not be used.
     */
    public static boolean isRegionizedThreading() {
        Server server = Bukkit.getServer();
        if (server != null && FoliaScheduler.isFoliaThreading(server)) {
            return true;
        }
        return FOLIA_REGIONIZED || CANVAS;
    }

    public static boolean isFoliaFamily() {
        return isRegionizedThreading();
    }

    public static String summary() {
        String local = cachedSummary;
        if (local != null) {
            return local;
        }

        Server server = Bukkit.getServer();
        String name = server == null ? "unknown" : server.getName();
        String version = server == null ? "?" : server.getVersion();
        String bukkit = server == null ? "?" : server.getBukkitVersion();
        String result = family().name()
                + " name=" + name
                + " version=" + version
                + " bukkit=" + bukkit
                + " paperRuntime=" + PAPER_RUNTIME
                + " regionized=" + isRegionizedThreading();
        cachedSummary = result;
        return result;
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean brandContains(String token) {
        try {
            Server server = Bukkit.getServer();
            if (server == null) {
                return false;
            }
            String haystack = (server.getName() + " " + server.getVersion() + " " + server.getBukkitVersion()).toLowerCase();
            return haystack.contains(token.toLowerCase());
        } catch (Throwable ignored) {
            return false;
        }
    }
}
