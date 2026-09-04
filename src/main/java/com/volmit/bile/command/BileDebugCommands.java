package com.volmit.bile.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.bile.BileTools;
import com.volmit.bile.localization.BileMessages;
import org.bukkit.command.CommandSender;

@Director(name = "debug", description = "BileTools diagnostic tools", descriptionKey = "command.debug")
public final class BileDebugCommands {
    private final BileTools plugin;

    public BileDebugCommands(BileTools plugin) {
        this.plugin = plugin;
    }

    @Director(name = "dump", sync = true, description = "Create a comprehensive BileTools diagnostic report", descriptionKey = "command.debug_dump")
    public void dump(
            @Param(name = "upload", defaultValue = "true", description = "Upload the report to mclo.gs", descriptionKey = "parameter.debug_upload") boolean upload,
            @Param(name = "sender", contextual = true) CommandSender sender
    ) {
        if (!sender.hasPermission("biletools.debug")) {
            ComponentMessenger.send(sender, plugin.getLocalization().text(sender,
                    BileMessages.PERMISSION_DENIED,
                    MessageArgs.builder().untrusted("permission", "biletools.debug").build()));
            return;
        }
        plugin.debugDump().request(sender, upload);
    }
}
