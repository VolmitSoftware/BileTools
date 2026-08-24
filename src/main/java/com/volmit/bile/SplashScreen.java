package com.volmit.bile;

import art.arcane.volmlib.util.plugin.SplashScreenSupport;
import net.md_5.bungee.api.ChatColor;

import java.util.logging.Level;

public final class SplashScreen {
    private SplashScreen() {
    }

    public static void print(BileTools plugin) {
        ChatColor dark = ChatColor.DARK_GRAY;
        ChatColor accent = ChatColor.GREEN;
        ChatColor meta = ChatColor.GRAY;
        String pluginVersion = plugin.getDescription().getVersion();
        String releaseTrain = SplashScreenSupport.releaseTrain(pluginVersion);
        String serverVersion = SplashScreenSupport.serverVersionWithoutMcSuffix();
        String startupDate = SplashScreenSupport.startupDate();
        String supportedMcVersion = "1.20.1 - 26.x";

        String splash =
                "\n"
                        + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗" + dark + "██" + accent + "╗     " + dark + "███████" + accent + "╗" + dark + "████████" + accent + "╗ " + dark + "██████" + accent + "╗  " + dark + "██████" + accent + "╗ " + dark + "██" + accent + "╗      " + dark + "███████" + accent + "╗\n"
                        + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝╚══" + dark + "██" + accent + "╔══╝" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "╔═══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔════╝" + accent + "   BileTools, " + ChatColor.DARK_GREEN + "Hotload Everything" + ChatColor.RED + "[" + releaseTrain + "]\n"
                        + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "█████" + accent + "╗     " + dark + "██" + accent + "║   " + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "███████" + accent + "╗" + meta + "   Version: " + accent + pluginVersion + "\n"
                        + dark + "██" + accent + "╔══" + dark + "██" + accent + "╗" + dark + "██" + accent + "║" + dark + "██" + accent + "║     " + dark + "██" + accent + "╔══╝     " + dark + "██" + accent + "║   " + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║   " + dark + "██" + accent + "║" + dark + "██" + accent + "║     ╚════" + dark + "██" + accent + "║" + meta + "   By: " + rainbowStudioName() + meta + " | " + accent + "VolmitSoftware.com" + "\n"
                        + dark + "██████" + accent + "╔╝" + dark + "██" + accent + "║" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "╗   " + dark + "██" + accent + "║   ╚" + dark + "██████" + accent + "╔╝╚" + dark + "██████" + accent + "╔╝" + dark + "███████" + accent + "╗" + dark + "███████" + accent + "║" + meta + "   Server: " + accent + serverVersion + meta + " | MC Support: " + accent + supportedMcVersion + "\n"
                        + accent + "╚═════╝ ╚═╝╚══════╝╚══════╝   ╚═╝    ╚═════╝  ╚═════╝ ╚══════╝╚══════╝" + meta + "   Java: " + accent + SplashScreenSupport.javaMajorVersion() + meta + " | Date: " + accent + startupDate + "\n";

        BileTools.logLegacy(Level.INFO, splash, null);
    }

    private static String rainbowStudioName() {
        return ChatColor.DARK_AQUA + "Volmit Software (Arcane Arts)";
    }
}
