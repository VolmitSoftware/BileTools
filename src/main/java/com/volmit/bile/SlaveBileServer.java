package com.volmit.bile;

import art.arcane.volmlib.util.crypto.Ed25519;
import art.arcane.volmlib.util.crypto.Ed25519Tls;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.network.IpRange;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import com.volmit.bile.localization.BileMessages;
import com.volmit.bile.config.BileConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class SlaveBileServer extends Thread {
    static final String TRUSTED_KEYS_PATH = "remote-deploy.slave.trusted-keys";

    private final ServerSocket socket;
    private final int clientTimeoutMs;
    private final long maxTransferBytes;
    private final SSLContext tls;
    private final List<IpRange> allowedAddresses;
    private volatile boolean running = true;

    public SlaveBileServer(BileConfig config, KeyPair identity) throws IOException {
        setName("Bile Slave Connection");
        setDaemon(true);
        clientTimeoutMs = Math.max(1000, config.getRemoteSocketTimeoutMs());
        maxTransferBytes = config.getRemoteMaxTransferBytes();
        tls = RemoteDeployProtocol.tlsContext(identity,
                RemoteDeployProtocol.decodeTrustedKeys(TRUSTED_KEYS_PATH, config.getRemoteSlaveTrustedKeys()));
        allowedAddresses = parseAllowedAddresses(config.getRemoteSlaveAllowedAddresses());
        socket = new ServerSocket(config.getRemoteSlavePort(), 50, InetAddress.getByName(config.getRemoteSlaveBindAddress()));
        this.socket.setSoTimeout(1000);
        this.socket.setPerformancePreferences(1, 1, 10);
    }

    static List<IpRange> parseAllowedAddresses(List<String> entries) throws IOException {
        List<IpRange> ranges = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            try {
                ranges.add(IpRange.parse(entries.get(index)));
            } catch (IllegalArgumentException e) {
                throw new IOException("remote-deploy.slave.allowed-addresses entry " + (index + 1) + " is invalid: " + e.getMessage(), e);
            }
        }
        return List.copyOf(ranges);
    }

    static boolean isAllowed(List<IpRange> allowedAddresses, InetAddress address) {
        if (allowedAddresses.isEmpty()) {
            return true;
        }
        for (IpRange range : allowedAddresses) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
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
            String host = client.getInetAddress() == null ? "unknown" : client.getInetAddress().getHostAddress();
            if (!isAllowed(allowedAddresses, client.getInetAddress())) {
                BileTools.bile.getLogger().warning("Refused remote deploy connection from " + host
                        + ": address is not in remote-deploy.slave.allowed-addresses");
                return;
            }
            client.setSoTimeout(clientTimeoutMs);
            client.setTcpNoDelay(true);

            File received;
            String master;
            try (SSLSocket secured = RemoteDeployProtocol.acceptHandshake(tls, client)) {
                master = Ed25519.fingerprint(Ed25519Tls.peerKey(secured.getSession()));
                received = RemoteDeployProtocol.receiveFile(secured, BileUtils.getPluginsFolder(), maxTransferBytes);
            }

            BileTools.bile.getLogger().info("Received file from " + host + " (master key " + master + ") -> " + received.getName());

            PlatformTasks.runGlobal(BileTools.bile, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!player.hasPermission("bile.use")) {
                        continue;
                    }
                    PlatformTasks.runForPlayer(BileTools.bile, player, () ->
                            ComponentMessenger.send(player, BileTools.bile.getLocalization().text(
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
                BileTools.bile.getLogger().log(
                        Level.WARNING, "Rejected or failed remote transfer: " + e.getMessage(), e);
            }
        }
    }
}
