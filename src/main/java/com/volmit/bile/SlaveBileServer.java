package com.volmit.bile;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.logging.Level;

public class SlaveBileServer extends Thread {
    private final ServerSocket socket;

    public SlaveBileServer() throws IOException {
        setName("Bile Slave Connection");
        socket = new ServerSocket(BileTools.cfg.getInt("remote-deploy.slave.slave-port"));
        socket.setSoTimeout(1000);
        socket.setPerformancePreferences(1, 1, 10);
    }

    @Override
    public void run() {
        try {
            while (!interrupted()) {
                try {
                    Socket client = socket.accept();
                    DataInputStream din = new DataInputStream(client.getInputStream());
                    String password = din.readUTF();
                    String fileName = din.readUTF();

                    if (!password.equals(BileTools.cfg.getString("remote-deploy.slave.slave-payload"))) {
                        client.close();
                        continue;
                    }

                    File f = new File(BileTools.bile.getDataFolder().getParentFile(), fileName);
                    BileTools.bile.getLogger().info("Receiving File from " + client.getInetAddress().getHostAddress() + " -> " + f.getName());
                    FileOutputStream fos = new FileOutputStream(f);
                    byte[] buf = new byte[8192];
                    int read;

                    while ((read = din.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                    }

                    Runnable notify = () -> {
                        for (Player k : Bukkit.getOnlinePlayers()) {
                            if (k.hasPermission("bile.use")) {
                                k.sendMessage(BileTools.bile.tag + "Receiving " + ChatColor.WHITE + f.getName() + ChatColor.GRAY + " from " + ChatColor.WHITE + client.getInetAddress().getHostAddress());
                            }
                        }
                    };

                    if (!FoliaScheduler.runGlobal(BileTools.bile, notify)) {
                        try {
                            Bukkit.getScheduler().runTask(BileTools.bile, notify);
                        } catch (Throwable ignored) {
                        }
                    }

                    fos.close();
                    din.close();
                    client.close();
                } catch (SocketTimeoutException ignored) {

                } catch (Throwable e) {
                    BileTools.bile.getLogger().log(Level.SEVERE,"Error receiving file.",e);
                }
            }

            socket.close();
        } catch (Exception ignored) {

        }
    }
}
