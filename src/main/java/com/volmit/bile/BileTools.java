package com.volmit.bile;

import com.volmit.volume.cluster.DataCluster;
import com.volmit.volume.lang.collections.GList;
import com.volmit.volume.lang.collections.YAMLClusterPort;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor {
    private SlaveBileServer srv;
    public static BileTools bile;
    private HashMap<File, Long> mod;
    private HashMap<File, Long> las;
    private File folder;
    private File backoff;
    public String tag;
    private Sound sx;
    private int cd = 10;
    public static DataCluster cfg;

    public static void streamFile(File f, String address, int port, String password) throws IOException {
        Socket s = new Socket(address, port);
        DataOutputStream dos = new DataOutputStream(s.getOutputStream());
        dos.writeUTF(password);
        dos.writeUTF(f.getName());

        FileInputStream fin = new FileInputStream(f);
        byte[] buffer = new byte[8192];
        int read;

        while ((read = fin.read(buffer)) != -1) {
            dos.write(buffer, 0, read);
        }

        fin.close();
        dos.flush();
        s.close();
    }

    private void readTheConfig() throws Exception {
        DataCluster cc = new DataCluster();
        cc.set("remote-deploy.slave.slave-enabled", false);
        cc.set("remote-deploy.slave.slave-port", 9876);
        cc.set("remote-deploy.slave.slave-payload", "pickapassword");
        cc.set("remote-deploy.master.master-enabled", false);
        cc.set("remote-deploy.master.master-deploy-to", new GList<String>().qadd("yourserver.com:9876:password"));
        cc.set("remote-deploy.master.master-deploy-signatures", new GList<String>().qadd("MyPlugin").qadd("AnotherPlugin"));
        cc.set("archive-plugins", true);
        cfg = cc;

        File f = new File(getDataFolder(), "config.yml");
        f.getParentFile().mkdirs();

        if (!f.exists()) {
            new YAMLClusterPort().fromCluster(cc).save(f);
        }

        FileConfiguration fc = new YamlConfiguration();
        fc.load(f);
        cfg = new YAMLClusterPort().toCluster(fc);
    }

    @Override
    public void onEnable() {
        try {
            readTheConfig();
        } catch (Exception e) {
            this.getLogger().log(Level.SEVERE, "Unable to read the config...", e);
        }

        if (cfg.getBoolean("remote-deploy.slave.slave-enabled")) {
            getLogger().info("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));

            try {
                srv = new SlaveBileServer();
                srv.start();
                getLogger().info("Remote Slave Server online!");
            } catch (Throwable e) {
                getLogger().warning("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));
                e.printStackTrace();
            }
        }

        cd = 10;
        bile = this;
        tag = ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY;
        mod = new HashMap<>();
        las = new HashMap<>();
        folder = getDataFolder().getParentFile();
        backoff = new File(getDataFolder(), "backoff");
        backoff.mkdirs();
        getCommand("bile").setExecutor(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        sx = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;

        getServer().getScheduler().runTaskTimer(this, this::onTick, 10, 0);
    }

    public boolean isBackoff(Player p) {
        return new File(backoff, p.getUniqueId().toString()).exists();
    }

    public void toggleBackoff(Player p) {
        if (new File(backoff, p.getUniqueId().toString()).exists()) {
            new File(backoff, p.getUniqueId().toString()).delete();
        } else {
            new File(backoff, p.getUniqueId().toString()).mkdirs();
        }
    }

    @Override
    public void onDisable() {
        if (srv != null && srv.isAlive()) {
            srv.interrupt();

            try {
                srv.join();
                this.getLogger().info("Bile Slave Server shut down.");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void reset(File f) {
        mod.put(f, f.length());
        las.put(f, f.lastModified());
    }

    public void onTick() {
        if (cd > 0) {
            cd--;
        }

        for (File i : folder.listFiles()) {
            if (i.getName().toLowerCase().endsWith(".jar") && i.isFile()) {
                if (!mod.containsKey(i)) {
                    getLogger().log(Level.INFO, "Now Tracking: " + i.getName());

                    if (cfg.getBoolean("archive-plugins")) {
                        Bukkit.getScheduler().runTaskAsynchronously(bile, () -> {
                            Plugin pp = BileUtils.getPlugin(i);

                            if (pp != null) {
                                try {
                                    BileUtils.backup(pp);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        });
                    }

                    mod.put(i, i.length());
                    las.put(i, i.lastModified());

                    if (cd == 0) {
                        Bukkit.getScheduler().runTaskLater(this, () -> {
                            try {
                                BileUtils.load(i);

                                for (Player k : Bukkit.getOnlinePlayers()) {
                                    if (k.hasPermission("bile.use")) {
                                        k.sendMessage(tag + "Hot Dropped " + ChatColor.WHITE + i.getName());
                                        k.playSound(k.getLocation(), sx, 1f, 1.9f);
                                    }
                                }
                            } catch (Throwable e) {
                                for (Player k : Bukkit.getOnlinePlayers()) {
                                    if (k.hasPermission("bile.use")) {
                                        k.sendMessage(tag + "Failed to hot drop " + ChatColor.RED + i.getName());
                                    }
                                }
                            }
                        }, 5);
                    }
                }

                if (mod.get(i) != i.length() || las.get(i) != i.lastModified()) {
                    mod.put(i, i.length());
                    las.put(i, i.lastModified());

                    for (Plugin j : Bukkit.getServer().getPluginManager().getPlugins()) {
                        if (BileUtils.getPluginFile(j).getName().equals(i.getName())) {
                            getLogger().log(Level.INFO, "File change detected: " + i.getName());
                            getLogger().log(Level.INFO, "Identified Plugin: " + j.getName() + " <-> " + i.getName());
                            getLogger().log(Level.INFO, "Reloading: " + j.getName());

                            try {
                                if (cfg.getBoolean("remote-deploy.master.master-enabled")) {
                                    if (cfg.getStringList("remote-deploy.master.master-deploy-signatures").contains(j.getName())) {
                                        Bukkit.getScheduler().runTaskAsynchronously(BileTools.bile, () -> {
                                            for (String g : cfg.getStringList("remote-deploy.master.master-deploy-to")) {
                                                try {
                                                    streamFile(i, g.split(":")[0], Integer.parseInt(g.split(":")[1]), g.split(":")[2]);
                                                } catch (NumberFormatException e) {
                                                    this.getLogger().warning("Invalid format");
                                                    e.printStackTrace();
                                                } catch (UnknownHostException e) {
                                                    this.getLogger().warning("Invalid host");
                                                    e.printStackTrace();
                                                } catch (IOException e) {
                                                    this.getLogger().warning("Invalid connection");
                                                    e.printStackTrace();
                                                }
                                            }

                                            for (Player k : Bukkit.getOnlinePlayers()) {
                                                if (k.hasPermission("bile.use")) {
                                                    k.sendMessage(tag + "Deployed " + ChatColor.WHITE + j.getName() + ChatColor.GRAY + " to " + cfg.getStringList("remote-deploy.master.master-deploy-to").size() + " remote server(s)");
                                                }
                                            }

                                            Bukkit.getScheduler().runTaskLater(BileTools.bile, () -> {
                                                try {
                                                    BileUtils.reload(j);

                                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                                        if (k.hasPermission("bile.use")) {
                                                            k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
                                                            k.playSound(k.getLocation(), sx, 1f, 1.9f);
                                                        }
                                                    }
                                                } catch (Throwable e) {
                                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                                        if (k.hasPermission("bile.use")) {
                                                            k.sendMessage(tag + "Failed to Reload " + ChatColor.RED + j.getName());
                                                        }
                                                    }

                                                    e.printStackTrace();
                                                }
                                            }, 5);
                                        });
                                    }
                                } else {
                                    BileUtils.reload(j);

                                    for (Player k : Bukkit.getOnlinePlayers()) {
                                        if (k.hasPermission("bile.use")) {
                                            k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
                                            k.playSound(k.getLocation(), sx, 1f, 1.9f);
                                        }
                                    }
                                }
                            } catch (Throwable e) {
                                for (Player k : Bukkit.getOnlinePlayers()) {
                                    if (k.hasPermission("bile.use")) {
                                        k.sendMessage(tag + "Failed to Reload " + ChatColor.RED + j.getName());
                                    }
                                }

                                e.printStackTrace();
                            }

                            break;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equals("biletools")) {
            if (!sender.hasPermission("bile.use")) {
                sender.sendMessage(tag + "You need bile.use or OP.");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage(tag + "/// - Ingame dev mode toggle");
                sender.sendMessage(tag + "/bile load <plugin>");
                sender.sendMessage(tag + "/bile unload <plugin>");
                sender.sendMessage(tag + "/bile reload <plugin>");
                sender.sendMessage(tag + "/bile install <plugin> [version]");
                sender.sendMessage(tag + "/bile uninstall <plugin>");
                sender.sendMessage(tag + "/bile library [plugin]");
            } else {
                if (args[0].equalsIgnoreCase("load")) {
                    if (args.length > 1) {
                        for (int i = 1; i < args.length; i++) {
                            try {
                                File s = BileUtils.getPluginFile(args[i]);

                                if (s == null) {
                                    sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
                                    continue;
                                }

                                try {
                                    BileUtils.load(s);
                                    String n = BileUtils.getPluginByName(args[i]).getName();
                                    sender.sendMessage(tag + "Loaded " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
                                } catch (Throwable e) {
                                    sender.sendMessage(tag + "Couldn't load \"" + args[i] + "\".");
                                    e.printStackTrace();
                                }
                            } catch (Throwable e) {
                                sender.sendMessage(tag + "Couldn't load or find \"" + args[i] + "\".");
                                e.printStackTrace();
                            }
                        }
                    } else {
                        sender.sendMessage(tag + "/bile load <PLUGIN>");
                    }
                }

                if (args[0].equalsIgnoreCase("uninstall")) {
                    if (args.length > 1) {
                        for (int i = 1; i < args.length; i++) {
                            try {
                                File s = BileUtils.getPluginFile(args[i]);

                                if (s == null) {
                                    sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
                                    continue;
                                }

                                try {
                                    String n = BileUtils.getPluginName(s);
                                    BileUtils.delete(s);

                                    if (!s.exists()) {
                                        sender.sendMessage(tag + "Uninstalled " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
                                    } else {
                                        sender.sendMessage(tag + "Uninstalled " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
                                        sender.sendMessage(tag + "But it looks like we can't delete it. You may need to delete " + ChatColor.RED + s.getName() + ChatColor.GRAY + " before installing it again.");
                                    }
                                } catch (Throwable e) {
                                    sender.sendMessage(tag + "Couldn't uninstall \"" + args[i] + "\".");
                                    e.printStackTrace();
                                }
                            } catch (Throwable e) {
                                sender.sendMessage(tag + "Couldn't uninstall or find \"" + args[i] + "\".");
                                e.printStackTrace();
                            }
                        }
                    } else {
                        sender.sendMessage(tag + "/bile uninstall <PLUGIN>");
                    }
                }

                if (args[0].equalsIgnoreCase("install")) {
                    if (args.length > 1) {
                        try {
                            for (File i : new File(getDataFolder(), "library").listFiles()) {
                                if (i.getName().equalsIgnoreCase(args[1])) {
                                    if (args.length == 2) {
                                        long highest = -100000;
                                        File latest = null;

                                        for (File j : i.listFiles()) {
                                            String v = j.getName().replace(".jar", "");
                                            List<Integer> d = new ArrayList<>();

                                            for (char k : v.toCharArray()) {
                                                if (Character.isDigit(k)) {
                                                    d.add(Integer.valueOf(k + ""));
                                                }
                                            }

                                            Collections.reverse(d);
                                            long g = 0;

                                            for (int k = 0; k < d.size(); k++) {
                                                g += (Math.pow(d.get(k), (k + 2)));
                                            }

                                            if (g > highest) {
                                                highest = g;
                                                latest = j;
                                            }
                                        }

                                        if (latest != null) {
                                            File ff = new File(BileUtils.getPluginsFolder(), i.getName() + "-" + latest.getName());
                                            BileUtils.copy(latest, ff);
                                            BileUtils.load(ff);
                                            sender.sendMessage(tag + "Installed " + ChatColor.WHITE + ff.getName() + ChatColor.GRAY + " from library.");
                                        }
                                    } else {
                                        for (File j : i.listFiles()) {
                                            String v = j.getName().replace(".jar", "");

                                            if (v.equals(args[2])) {
                                                File ff = new File(BileUtils.getPluginsFolder(), i.getName() + "-" + v);
                                                BileUtils.copy(j, ff);
                                                BileUtils.load(ff);
                                                sender.sendMessage(tag + "Installed " + ChatColor.WHITE + ff.getName() + ChatColor.GRAY + " from library.");
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            sender.sendMessage(tag + "Couldn't install or find \"" + args[1] + "\".");
                            e.printStackTrace();
                        }
                    } else {
                        sender.sendMessage(tag + "/bile install <PLUGIN> [VERSION]");
                    }
                }

                if (args[0].equalsIgnoreCase("library")) {
                    if (args.length == 1) {
                        try {
                            for (File i : new File(getDataFolder(), "library").listFiles()) {
                                long highest = -100000;
                                File latest = null;

                                for (File j : i.listFiles()) {
                                    String v = j.getName().replace(".jar", "");
                                    List<Integer> d = new ArrayList<>();

                                    for (char k : v.toCharArray()) {
                                        if (Character.isDigit(k)) {
                                            d.add(Integer.valueOf(k + ""));
                                        }
                                    }

                                    Collections.reverse(d);
                                    long g = 0;

                                    for (int k = 0; k < d.size(); k++) {
                                        g += (Math.pow(d.get(k), (k + 2)));
                                    }

                                    if (g > highest) {
                                        highest = g;
                                        latest = j;
                                    }
                                }

                                if (latest != null) {
                                    boolean inst = false;
                                    String v = null;

                                    for (File k : BileUtils.getPluginsFolder().listFiles()) {
                                        if (BileUtils.isPluginJar(k) && i.getName().equalsIgnoreCase(BileUtils.getPluginName(k))) {
                                            v = BileUtils.getPluginVersion(k);
                                            inst = true;
                                            break;
                                        }
                                    }

                                    if (inst) {
                                        sender.sendMessage(tag + i.getName() + " " + ChatColor.GREEN + "(" + v + " installed) " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
                                    } else {
                                        sender.sendMessage(tag + i.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
                                    }
                                }
                            }
                        } catch (Throwable e) {
                            sender.sendMessage(tag + "Couldn't list library.");
                            e.printStackTrace();
                        }
                    } else {
                        try {
                            boolean dx = false;

                            for (File i : new File(getDataFolder(), "library").listFiles()) {
                                if (!i.getName().equalsIgnoreCase(args[1])) {
                                    continue;
                                }

                                dx = true;
                                long highest = -100000;
                                File latest = null;

                                for (File j : i.listFiles()) {
                                    String v = j.getName().replace(".jar", "");
                                    List<Integer> d = new ArrayList<>();

                                    for (char k : v.toCharArray()) {
                                        if (Character.isDigit(k)) {
                                            d.add(Integer.valueOf(k + ""));
                                        }
                                    }

                                    Collections.reverse(d);
                                    long g = 0;

                                    for (int k = 0; k < d.size(); k++) {
                                        g += (Math.pow(d.get(k), (k + 2)));
                                    }

                                    if (g > highest) {
                                        highest = g;
                                        latest = j;
                                    }
                                }

                                if (latest != null) {
                                    for (File j : i.listFiles()) {
                                        sender.sendMessage(tag + j.getName().replace(".jar", ""));
                                    }

                                    sender.sendMessage(tag + i.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
                                }
                            }

                            if (!dx) {
                                sender.sendMessage(tag + "Couldn't find " + args[1] + " in library.");
                            }
                        } catch (Throwable e) {
                            sender.sendMessage(tag + "Couldn't list library.");
                            e.printStackTrace();
                        }
                    }
                } else if (args[0].equalsIgnoreCase("unload")) {
                    if (args.length > 1) {
                        for (int i = 1; i < args.length; i++) {
                            try {
                                Plugin s = BileUtils.getPluginByName(args[i]);

                                if (s == null) {
                                    sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
                                    continue;
                                }

                                String sn = s.getName();
                                BileUtils.unload(s);
                                File n = BileUtils.getPluginFile(args[i]);
                                sender.sendMessage(tag + "Unloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n.getName() + ChatColor.GRAY + ")");
                            } catch (Throwable e) {
                                sender.sendMessage(tag + "Couldn't unload \"" + args[i] + "\".");
                                e.printStackTrace();
                            }
                        }
                    } else {
                        sender.sendMessage(tag + "/bile unload <PLUGIN>");
                    }
                } else if (args[0].equalsIgnoreCase("reload")) {
                    if (args.length > 1) {
                        for (int i = 1; i < args.length; i++) {
                            try {
                                Plugin s = BileUtils.getPluginByName(args[i]);

                                if (s == null) {
                                    sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
                                    continue;
                                }

                                try {
                                    String sn = s.getName();
                                    BileUtils.reload(s);
                                    File n = BileUtils.getPluginFile(args[i]);
                                    sender.sendMessage(tag + "Reloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n.getName() + ChatColor.GRAY + ")");
                                } catch (Throwable e) {
                                    sender.sendMessage(tag + "Couldn't reload \"" + args[i] + "\".");
                                    e.printStackTrace();
                                }
                            } catch (Throwable e) {
                                sender.sendMessage(tag + "Couldn't reload or find \"" + args[i] + "\".");
                                e.printStackTrace();
                            }
                        }
                    } else {
                        sender.sendMessage(tag + "/bile reload <PLUGIN>");
                    }
                }
            }

            return true;
        }

        return false;
    }
}
