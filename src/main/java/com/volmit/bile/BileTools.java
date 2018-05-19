package com.volmit.bile;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

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

import com.volmit.volume.cluster.DataCluster;
import com.volmit.volume.lang.collections.GList;
import com.volmit.volume.lang.collections.YAMLClusterPort;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import net.md_5.bungee.api.ChatColor;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor
{
	private SlaveBileServer srv;
	public static BileTools bile;
	private static HashMap<File, Long> mod;
	private static HashMap<File, Long> las;
	private File folder;
	private File backoff;
	public String tag;
	private Sound sx;
	private int cd = 10;
	public static DataCluster cfg;

	public static void l(String s)
	{
		System.out.println("[Bile]: " + s);
	}

	public static void play()
	{
		try
		{
			MediaPlayer mp = new MediaPlayer(new Media(BileTools.class.getResource("/done.wav").toURI().toString()));
			mp.play();
		}

		catch(Exception exc)
		{
			exc.printStackTrace(System.out);
		}
	}

	@SuppressWarnings("restriction")
	public static void main(String[] a)
	{
		l("Init Standalone");
		File ff = new File("plugins");
		File fb = new File(ff, "BileTools");
		fb.mkdirs();
		l("===========================================");
		l("Plugins Folder: " + ff.getAbsolutePath());
		l("Bile Folder: " + fb.getAbsolutePath());
		File cf = new File(fb, "config.yml");
		l("Init Ghost Plugin");
		l("Load config: " + cf.getAbsolutePath());
		l("Start Pool");
		ExecutorService svc = Executors.newWorkStealingPool(8);
		l("Init FX");
		com.sun.javafx.application.PlatformImpl.startup(() ->
		{
		});
		try
		{
			readTheConfig(fb);
		}

		catch(Exception e)
		{
			e.printStackTrace();
			System.exit(1);
		}

		if(!cfg.getBoolean("remote-deploy.master.master-enabled"))
		{
			l("Nothing to do. Master is not enabled");
			System.exit(0);
		}

		l("Service Start");
		l("===========================================");
		mod = new HashMap<File, Long>();
		las = new HashMap<File, Long>();

		for(File i : ff.listFiles())
		{
			if(i.isFile() && i.getName().endsWith(".jar"))
			{
				mod.put(i, i.lastModified());
				las.put(i, i.length());
				l("Now tracking: " + i.getName());
			}
		}

		new Thread(new Runnable()
		{
			@Override
			public void run()
			{
				play();
				while(!Thread.interrupted())
				{
					try
					{
						try
						{
							for(File i : ff.listFiles())
							{
								if(i.isDirectory() || !i.getName().endsWith(".jar"))
								{
									continue;
								}

								boolean send = false;

								if(!mod.containsKey(i))
								{
									send = true;
									mod.put(i, i.lastModified());
									las.put(i, i.length());
								}

								else if(mod.get(i) != i.lastModified() || las.get(i) != i.length())
								{
									send = true;
									mod.put(i, i.lastModified());
									las.put(i, i.length());
								}

								if(send)
								{
									boolean al = false;

									for(String j : cfg.getStringList("remote-deploy.master.master-deploy-signatures"))
									{
										if(i.getName().toLowerCase().startsWith(j.toLowerCase()))
										{
											al = true;
											break;
										}
									}

									if(al)
									{
										for(String j : cfg.getStringList("remote-deploy.master.master-deploy-to"))
										{
											svc.submit(new Runnable()
											{
												@Override
												public void run()
												{
													try
													{
														streamFile(i, j.split(":")[0], Integer.valueOf(j.split(":")[1]), j.split(":")[2]);
														l(i.getName() + " -> " + j.split(":")[0] + ":" + j.split(":")[1]);

														svc.submit(new Runnable()
														{
															@Override
															public void run()
															{
																play();
															}
														});
													}

													catch(Exception e)
													{
														e.printStackTrace();
													}
												}
											});
										}
									}
								}
							}
						}

						catch(Throwable e)
						{
							e.printStackTrace();
						}

						Thread.sleep(500);
					}

					catch(InterruptedException e)
					{
						e.printStackTrace();
					}
				}
			}
		}, "File Tracker").start();
		l("File Threads Started: 1t/1000ms");
		l("===========================================");
	}

	public static void streamFile(File f, String address, int port, String password) throws UnknownHostException, IOException
	{
		Socket s = new Socket(address, port);
		DataOutputStream dos = new DataOutputStream(s.getOutputStream());
		dos.writeUTF(password);
		dos.writeUTF(f.getName());

		FileInputStream fin = new FileInputStream(f);
		byte[] buffer = new byte[8192];
		int read = 0;

		while((read = fin.read(buffer)) != -1)
		{
			dos.write(buffer, 0, read);
		}

		fin.close();
		dos.flush();
		s.close();
	}

	private static void readTheConfig(File df) throws IOException, Exception
	{
		DataCluster cc = new DataCluster();
		cc.set("remote-deploy.slave.slave-enabled", false);
		cc.set("remote-deploy.slave.slave-port", 9876);
		cc.set("remote-deploy.slave.slave-payload", "pickapassword");
		cc.set("remote-deploy.master.master-enabled", false);
		cc.set("remote-deploy.master.master-deploy-to", new GList<String>().qadd("yourserver.com:9876:password"));
		cc.set("remote-deploy.master.master-deploy-signatures", new GList<String>().qadd("MyPlugin").qadd("AnotherPlugin"));
		cfg = cc;

		File f = new File(df, "config.yml");
		f.getParentFile().mkdirs();

		if(!f.exists())
		{
			new YAMLClusterPort().fromCluster(cc).save(f);
		}

		FileConfiguration fc = new YamlConfiguration();
		fc.load(f);
		cfg = new YAMLClusterPort().toCluster(fc);
	}

	@Override
	public void onEnable()
	{
		try
		{
			readTheConfig(getDataFolder());
		}

		catch(Exception e)
		{
			System.out.println("Unable to read the config...");
			e.printStackTrace();
		}

		if(cfg.getBoolean("remote-deploy.slave.slave-enabled"))
		{
			getLogger().info("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));

			try
			{
				srv = new SlaveBileServer();
				srv.start();
				getLogger().info("Remote Slave Server online!");
			}

			catch(Throwable e)
			{
				getLogger().warning("Starting Remote Slave Server on *:" + cfg.getInt("remote-deploy.slave.slave-port"));
				e.printStackTrace();
			}
		}

		cd = 10;
		bile = this;
		tag = ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY;
		mod = new HashMap<File, Long>();
		las = new HashMap<File, Long>();
		folder = getDataFolder().getParentFile();
		backoff = new File(getDataFolder(), "backoff");
		backoff.mkdirs();
		getCommand("bile").setExecutor(this);
		Bukkit.getPluginManager().registerEvents(this, this);

		for(Sound f : Sound.values())
		{
			if(f.name().contains("ORB"))
			{
				sx = f;
			}
		}

		getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable()
		{
			@Override
			public void run()
			{
				onTick();
			}
		}, 10, 0);
	}

	public boolean isBackoff(Player p)
	{
		return new File(backoff, p.getUniqueId().toString()).exists();
	}

	public void toggleBackoff(Player p)
	{
		if(new File(backoff, p.getUniqueId().toString()).exists())
		{
			new File(backoff, p.getUniqueId().toString()).delete();
		}

		else
		{
			new File(backoff, p.getUniqueId().toString()).mkdirs();
		}
	}

	@Override
	public void onDisable()
	{
		if(srv != null && srv.isAlive())
		{
			srv.interrupt();

			try
			{
				srv.join();
				System.out.println("Bile Slave Server shut down.");
			}

			catch(InterruptedException e)
			{
				e.printStackTrace();
			}
		}
	}

	public void reset(File f)
	{
		mod.put(f, f.length());
		las.put(f, f.lastModified());
	}

	@SuppressWarnings("deprecation")
	public void onTick()
	{
		if(cd > 0)
		{
			cd--;
		}

		for(File i : folder.listFiles())
		{
			if(i.getName().toLowerCase().endsWith(".jar") && i.isFile())
			{
				if(!mod.containsKey(i))
				{
					getLogger().log(Level.INFO, "Now Tracking: " + i.getName());

					Bukkit.getScheduler().scheduleAsyncDelayedTask(bile, new Runnable()
					{
						@Override
						public void run()
						{
							Plugin pp = BileUtils.getPlugin(i);

							if(pp != null)
							{
								try
								{
									BileUtils.backup(pp);
								}

								catch(IOException e)
								{
									e.printStackTrace();
								}
							}
						}
					});

					mod.put(i, i.length());
					las.put(i, i.lastModified());

					if(cd == 0)
					{
						Bukkit.getScheduler().scheduleSyncDelayedTask(this, new Runnable()
						{
							@Override
							public void run()
							{
								try
								{
									BileUtils.load(i);

									for(Player k : Bukkit.getOnlinePlayers())
									{
										if(k.hasPermission("bile.use"))
										{
											k.sendMessage(tag + "Hot Dropped " + ChatColor.WHITE + i.getName());
											k.playSound(k.getLocation(), sx, 1f, 1.9f);
										}
									}
								}

								catch(Throwable e)
								{
									for(Player k : Bukkit.getOnlinePlayers())
									{
										if(k.hasPermission("bile.use"))
										{
											k.sendMessage(tag + "Failed to hot drop " + ChatColor.RED + i.getName());
										}
									}
								}
							}
						}, 5);
					}
				}

				if(mod.get(i) != i.length() || las.get(i) != i.lastModified())
				{
					mod.put(i, i.length());
					las.put(i, i.lastModified());

					for(Plugin j : Bukkit.getServer().getPluginManager().getPlugins())
					{
						if(BileUtils.getPluginFile(j).getName().equals(i.getName()))
						{
							getLogger().log(Level.INFO, "File change detected: " + i.getName());
							getLogger().log(Level.INFO, "Identified Plugin: " + j.getName() + " <-> " + i.getName());
							getLogger().log(Level.INFO, "Reloading: " + j.getName());

							try
							{
								if(cfg.getBoolean("remote-deploy.master.master-enabled"))
								{
									if(cfg.getStringList("remote-deploy.master.master-deploy-signatures").contains(j.getName()))
									{
										Bukkit.getScheduler().scheduleAsyncDelayedTask(BileTools.bile, new Runnable()
										{
											@Override
											public void run()
											{
												for(String g : cfg.getStringList("remote-deploy.master.master-deploy-to"))
												{
													try
													{
														streamFile(i, g.split(":")[0], Integer.valueOf(g.split(":")[1]), g.split(":")[2]);
													}

													catch(NumberFormatException e)
													{
														System.out.println("Invalid format");
														e.printStackTrace();
													}

													catch(UnknownHostException e)
													{
														System.out.println("Invalid host");
														e.printStackTrace();
													}

													catch(IOException e)
													{
														System.out.println("Invalid connection");
														e.printStackTrace();
													}
												}

												for(Player k : Bukkit.getOnlinePlayers())
												{
													if(k.hasPermission("bile.use"))
													{
														k.sendMessage(tag + "Deployed " + ChatColor.WHITE + j.getName() + ChatColor.GRAY + " to " + cfg.getStringList("remote-deploy.master.master-deploy-to").size() + " remote server(s)");
													}
												}

												Bukkit.getScheduler().scheduleSyncDelayedTask(BileTools.bile, new Runnable()
												{
													@Override
													public void run()
													{
														try
														{
															BileUtils.reload(j);

															for(Player k : Bukkit.getOnlinePlayers())
															{
																if(k.hasPermission("bile.use"))
																{
																	k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
																	k.playSound(k.getLocation(), sx, 1f, 1.9f);
																}
															}
														}

														catch(Throwable e)
														{
															for(Player k : Bukkit.getOnlinePlayers())
															{
																if(k.hasPermission("bile.use"))
																{
																	k.sendMessage(tag + "Failed to Reload " + ChatColor.RED + j.getName());
																}
															}

															e.printStackTrace();
														}
													}
												}, 5);
											}
										});
									}
								}

								else
								{
									BileUtils.reload(j);

									for(Player k : Bukkit.getOnlinePlayers())
									{
										if(k.hasPermission("bile.use"))
										{
											k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
											k.playSound(k.getLocation(), sx, 1f, 1.9f);
										}
									}
								}
							}

							catch(Throwable e)
							{
								for(Player k : Bukkit.getOnlinePlayers())
								{
									if(k.hasPermission("bile.use"))
									{
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
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
	{
		if(command.getName().equals("biletools"))
		{
			if(!sender.hasPermission("bile.use"))
			{
				sender.sendMessage(tag + "You need bile.use or OP.");
				return true;
			}

			if(args.length == 0)
			{
				sender.sendMessage(tag + "/// - Ingame dev mode toggle");
				sender.sendMessage(tag + "/bile load <plugin>");
				sender.sendMessage(tag + "/bile unload <plugin>");
				sender.sendMessage(tag + "/bile reload <plugin>");
				sender.sendMessage(tag + "/bile install <plugin> [version]");
				sender.sendMessage(tag + "/bile uninstall <plugin>");
				sender.sendMessage(tag + "/bile library [plugin]");
			}

			else
			{
				if(args[0].equalsIgnoreCase("load"))
				{
					if(args.length > 1)
					{
						for(int i = 1; i < args.length; i++)
						{
							try
							{
								File s = BileUtils.getPluginFile(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									BileUtils.load(s);
									String n = BileUtils.getPluginByName(args[i]).getName();
									sender.sendMessage(tag + "Loaded " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
								}

								catch(Throwable e)
								{
									sender.sendMessage(tag + "Couldn't load \"" + args[i] + "\".");
									e.printStackTrace();
								}
							}

							catch(Throwable e)
							{
								sender.sendMessage(tag + "Couldn't load or find \"" + args[i] + "\".");
								e.printStackTrace();
							}
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile load <PLUGIN>");
					}
				}

				if(args[0].equalsIgnoreCase("uninstall"))
				{
					if(args.length > 1)
					{
						for(int i = 1; i < args.length; i++)
						{
							try
							{
								File s = BileUtils.getPluginFile(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									String n = BileUtils.getPluginName(s);
									BileUtils.delete(s);

									if(!s.exists())
									{
										sender.sendMessage(tag + "Uninstalled " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
									}

									else
									{
										sender.sendMessage(tag + "Uninstalled " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s.getName());
										sender.sendMessage(tag + "But it looks like we can't delete it. You may need to delete " + ChatColor.RED + s.getName() + ChatColor.GRAY + " before installing it again.");
									}
								}

								catch(Throwable e)
								{
									sender.sendMessage(tag + "Couldn't uninstall \"" + args[i] + "\".");
									e.printStackTrace();
								}
							}

							catch(Throwable e)
							{
								sender.sendMessage(tag + "Couldn't uninstall or find \"" + args[i] + "\".");
								e.printStackTrace();
							}
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile uninstall <PLUGIN>");
					}
				}

				if(args[0].equalsIgnoreCase("install"))
				{
					if(args.length > 1)
					{
						try
						{
							for(File i : new File(getDataFolder(), "library").listFiles())
							{
								if(i.getName().toLowerCase().equals(args[1].toLowerCase()))
								{
									if(args.length == 2)
									{
										long highest = -100000;
										File latest = null;

										for(File j : i.listFiles())
										{
											String v = j.getName().replace(".jar", "");
											List<Integer> d = new ArrayList<Integer>();

											for(char k : v.toCharArray())
											{
												if(Character.isDigit(k))
												{
													d.add(Integer.valueOf(k + ""));
												}
											}

											Collections.reverse(d);
											long g = 0;

											for(int k = 0; k < d.size(); k++)
											{
												g += (Math.pow(d.get(k), (k + 2)));
											}

											if(g > highest)
											{
												highest = g;
												latest = j;
											}
										}

										if(latest != null)
										{
											File ff = new File(BileUtils.getPluginsFolder(), i.getName() + "-" + latest.getName());
											BileUtils.copy(latest, ff);
											BileUtils.load(ff);
											sender.sendMessage(tag + "Installed " + ChatColor.WHITE + ff.getName() + ChatColor.GRAY + " from library.");
										}
									}

									else
									{
										for(File j : i.listFiles())
										{
											String v = j.getName().replace(".jar", "");

											if(v.equals(args[2]))
											{
												File ff = new File(BileUtils.getPluginsFolder(), i.getName() + "-" + v);
												BileUtils.copy(j, ff);
												BileUtils.load(ff);
												sender.sendMessage(tag + "Installed " + ChatColor.WHITE + ff.getName() + ChatColor.GRAY + " from library.");
											}
										}
									}
								}
							}
						}

						catch(Throwable e)
						{
							sender.sendMessage(tag + "Couldn't install or find \"" + args[1] + "\".");
							e.printStackTrace();
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile install <PLUGIN> [VERSION]");
					}
				}

				if(args[0].equalsIgnoreCase("library"))
				{
					if(args.length == 1)
					{
						try
						{
							for(File i : new File(getDataFolder(), "library").listFiles())
							{
								long highest = -100000;
								File latest = null;

								for(File j : i.listFiles())
								{
									String v = j.getName().replace(".jar", "");
									List<Integer> d = new ArrayList<Integer>();

									for(char k : v.toCharArray())
									{
										if(Character.isDigit(k))
										{
											d.add(Integer.valueOf(k + ""));
										}
									}

									Collections.reverse(d);
									long g = 0;

									for(int k = 0; k < d.size(); k++)
									{
										g += (Math.pow(d.get(k), (k + 2)));
									}

									if(g > highest)
									{
										highest = g;
										latest = j;
									}
								}

								if(latest != null)
								{
									boolean inst = false;
									String v = null;

									for(File k : BileUtils.getPluginsFolder().listFiles())
									{
										if(BileUtils.isPluginJar(k) && i.getName().equalsIgnoreCase(BileUtils.getPluginName(k)))
										{
											v = BileUtils.getPluginVersion(k);
											inst = true;
											break;
										}
									}

									if(inst)
									{
										sender.sendMessage(tag + i.getName() + " " + ChatColor.GREEN + "(" + v + " installed) " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
									}

									else
									{
										sender.sendMessage(tag + i.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
									}
								}
							}
						}

						catch(Throwable e)
						{
							sender.sendMessage(tag + "Couldn't list library.");
							e.printStackTrace();
						}
					}

					else if(args.length > 1)
					{
						try
						{
							boolean dx = false;

							for(File i : new File(getDataFolder(), "library").listFiles())
							{
								if(!i.getName().equalsIgnoreCase(args[1]))
								{
									continue;
								}

								dx = true;
								long highest = -100000;
								File latest = null;

								for(File j : i.listFiles())
								{
									String v = j.getName().replace(".jar", "");
									List<Integer> d = new ArrayList<Integer>();

									for(char k : v.toCharArray())
									{
										if(Character.isDigit(k))
										{
											d.add(Integer.valueOf(k + ""));
										}
									}

									Collections.reverse(d);
									long g = 0;

									for(int k = 0; k < d.size(); k++)
									{
										g += (Math.pow(d.get(k), (k + 2)));
									}

									if(g > highest)
									{
										highest = g;
										latest = j;
									}
								}

								if(latest != null)
								{
									for(File j : i.listFiles())
									{
										sender.sendMessage(tag + j.getName().replace(".jar", ""));
									}

									sender.sendMessage(tag + i.getName() + " " + ChatColor.WHITE + latest.getName().replace(".jar", "") + ChatColor.GRAY + " (latest)");
								}
							}

							if(!dx)
							{
								sender.sendMessage(tag + "Couldn't find " + args[1] + " in library.");
							}
						}

						catch(Throwable e)
						{
							sender.sendMessage(tag + "Couldn't list library.");
							e.printStackTrace();
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile library [PLUGIN]");
					}
				}

				else if(args[0].equalsIgnoreCase("unload"))
				{
					if(args.length > 1)
					{
						for(int i = 1; i < args.length; i++)
						{
							try
							{
								Plugin s = BileUtils.getPluginByName(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								String sn = s.getName();
								BileUtils.unload(s);
								File n = BileUtils.getPluginFile(args[i]);
								sender.sendMessage(tag + "Unloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n.getName() + ChatColor.GRAY + ")");
							}

							catch(Throwable e)
							{
								sender.sendMessage(tag + "Couldn't unload \"" + args[i] + "\".");
								e.printStackTrace();
							}
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile unload <PLUGIN>");
					}
				}

				else if(args[0].equalsIgnoreCase("reload"))
				{
					if(args.length > 1)
					{
						for(int i = 1; i < args.length; i++)
						{
							try
							{
								Plugin s = BileUtils.getPluginByName(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									String sn = s.getName();
									BileUtils.reload(s);
									File n = BileUtils.getPluginFile(args[i]);
									sender.sendMessage(tag + "Reloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n.getName() + ChatColor.GRAY + ")");
								}

								catch(Throwable e)
								{
									sender.sendMessage(tag + "Couldn't reload \"" + args[i] + "\".");
									e.printStackTrace();
								}
							}

							catch(Throwable e)
							{
								sender.sendMessage(tag + "Couldn't reload or find \"" + args[i] + "\".");
								e.printStackTrace();
							}
						}
					}

					else
					{
						sender.sendMessage(tag + "/bile reload <PLUGIN>");
					}
				}
			}

			return true;
		}

		return false;
	}
}
