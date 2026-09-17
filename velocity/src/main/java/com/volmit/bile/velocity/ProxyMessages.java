package com.volmit.bile.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

import java.util.Objects;

public final class ProxyMessages {
    public static final String PERMISSION = "bile.use";

    private static final Component PREFIX = Component.text("[Bile] ", NamedTextColor.GREEN);

    private final ProxyServer proxy;
    private final Logger logger;
    private final boolean notifyPlayers;

    public ProxyMessages(ProxyServer proxy, Logger logger, boolean notifyPlayers) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.notifyPlayers = notifyPlayers;
    }

    public Component info(String text) {
        return body(text, NamedTextColor.WHITE);
    }

    public Component success(String text) {
        return body(text, NamedTextColor.GREEN);
    }

    public Component failure(String text) {
        return body(text, NamedTextColor.RED);
    }

    public void send(CommandSource source, Component message) {
        Objects.requireNonNull(source, "source").sendMessage(Objects.requireNonNull(message, "message"));
    }

    public void notifyOperators(Component message) {
        Objects.requireNonNull(message, "message");
        logger.info(PlainTextComponentSerializer.plainText().serialize(message));
        if (!notifyPlayers) {
            return;
        }
        for (Player player : proxy.getAllPlayers()) {
            if (player.hasPermission(PERMISSION)) {
                player.sendMessage(message);
            }
        }
    }

    private Component body(String text, NamedTextColor color) {
        return PREFIX.append(Component.text(Objects.requireNonNull(text, "text"), color));
    }
}
