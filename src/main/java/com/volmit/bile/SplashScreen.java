package com.volmit.bile;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class SplashScreen {
    private SplashScreen() {
    }

    public static void print(BileTools plugin) {
        ChatColor dark = ChatColor.DARK_GRAY;
        ChatColor accent = ChatColor.GREEN;
        ChatColor meta = ChatColor.GRAY;
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = getReleaseTrain(pluginVersion);
        String serverVersion = getServerVersion();
        String startupDate = getStartupDate();
        String supportedMcVersion = "1.21.11";

        String splash =
                "\n"
                        + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗" + dark + "██" + accent + "╗     " + dark + "███████" + accent + "╗" + dark + "████████" + accent + "╗ " + dark + "██████" + accent + "╗  " + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗      " + dark + "███████" + accent + "╗\n"
                        + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝╚══" + dark + "██" + accent + "╔══╝" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝" + accent + "   BileTools, " + ChatColor.DARK_GREEN + "Hotload Everything" + ChatColor.RED + "[" + releaseTrain + "]\n"
                        + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "█████" + accent + "╗     " + dark + "██" + accent + "║   " + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "███████" + accent + "╗" + meta + "   Version: " + accent + pluginVersion + "\n"
                        + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔══╝     " + dark + "██" + accent + "║   " + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     ╚════" + dark + "██" + accent + "║" + meta + "   By: " + rainbowStudioName() + "\n"
                        + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗   " + dark + "██" + accent + "║   ╚" + dark + "██████" + accent + "╔╝╚" + dark + "██████" + accent + "╔╝" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "║" + meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + supportedMcVersion + "\n"
                        + accent + "╚═════╝ ╚═╝╚══════╝╚══════╝   ╚═╝    ╚═════╝  ╚═════╝ ╚══════╝╚══════╝" + meta + "   Java: " + accent + getJavaVersion() + meta + " | Date: " + accent + startupDate + "\n";

        Bukkit.getConsoleSender().sendMessage(splash);
    }

    private static int getJavaVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2, 3);
        } else {
            int dot = version.indexOf('.');
            if (dot != -1) {
                version = version.substring(0, dot);
            }
        }
        return Integer.parseInt(version);
    }

    private static String getServerVersion() {
        String version = Bukkit.getVersion();
        int mcMarkerIndex = version.indexOf(" (MC:");
        if (mcMarkerIndex != -1) {
            version = version.substring(0, mcMarkerIndex);
        }
        return version;
    }

    private static String getStartupDate() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private static String getReleaseTrain(String version) {
        String value = version;
        int suffixIndex = value.indexOf('-');
        if (suffixIndex >= 0) {
            value = value.substring(0, suffixIndex);
        }
        String[] split = value.split("\\.");
        if (split.length >= 2) {
            return split[0] + "." + split[1];
        }
        return value;
    }

    private static String rainbowStudioName() {
        return ChatColor.DARK_AQUA + "Volmit Software (Arcane Arts)";
    }
}
