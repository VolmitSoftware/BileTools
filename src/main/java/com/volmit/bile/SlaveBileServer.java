package com.volmit.bile;

import art.arcane.volmlib.util.localization.MessageArgs;
import com.volmit.bile.localization.BileMessages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;

public class SlaveBileServer extends Thread {
    private final ServerSocket socket;
    private final int clientTimeoutMs;
    private final long maxTransferBytes;
    private volatile boolean running = true;

    public SlaveBileServer() throws IOException {
        setName("Bile Slave Connection");
        setDaemon(true);
        this.clientTimeoutMs = Math.max(1000, BileTools.cfg.getRemoteSocketTimeoutMs());
        this.maxTransferBytes = BileTools.cfg.getRemoteMaxTransferBytes();
        this.socket = new ServerSocket(BileTools.cfg.getRemoteSlavePort());
        this.socket.setSoTimeout(1000);
        this.socket.setPerformancePreferences(1, 1, 10);
    }

    public void shutdown() {
        running = false;
        interrupt();
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    public boolean isServerSocketOpen() {
        return socket != null && !socket.isClosed();
    }

    @Override
    public void run() {
        try {
            while (running && !interrupted() && !socket.isClosed()) {
                try {
                    Socket client = socket.accept();
                    handleClient(client);
                } catch (SocketTimeoutException ignored) {
                    // accept poll
                } catch (Throwable e) {
                    if (running && !socket.isClosed()) {
                        BileTools.bile.getLogger().log(Level.SEVERE, "Error receiving file.", e);
                    }
                }
            }
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void handleClient(Socket client) {
        try (client) {
            client.setSoTimeout(clientTimeoutMs);
            client.setTcpNoDelay(true);

            File received = RemoteDeployProtocol.receiveFile(
                    client,
                    BileTools.cfg.getRemoteSlavePayload(),
                    BileUtils.getPluginsFolder(),
                    maxTransferBytes
            );

            String host = client.getInetAddress() == null ? "unknown" : client.getInetAddress().getHostAddress();
            BileTools.bile.getLogger().info("Received file from " + host + " -> " + received.getName());

            PlatformTasks.runGlobal(BileTools.bile, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.hasPermission("bile.use")) {
                        continue;
                    }
                    PlatformTasks.runForPlayer(BileTools.bile, player, () ->
                            player.sendMessage(BileTools.bile.getLocalization().text(
                                    BileMessages.REMOTE_RECEIVING,
                                    MessageArgs.builder()
                                            .untrusted("file", received.getName())
                                            .untrusted("host", host)
                                            .build()
                            )));
                }
            });
        } catch (Throwable e) {
            if (running) {
                BileTools.bile.getLogger().log(Level.WARNING, "Rejected or failed remote transfer: " + e.getMessage());
            }
        }
    }
}
