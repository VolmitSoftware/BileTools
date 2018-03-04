package com.volmit.bile;

import java.io.File;
import java.util.HashMap;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import net.md_5.bungee.api.ChatColor;

public class BileTools extends JavaPlugin implements Listener, CommandExecutor
{
	public static BileTools bile;
	private HashMap<File, Long> mod;
	private HashMap<File, Long> las;
	private File folder;
	private String tag;
	private Sound sx;

	@Override
	public void onEnable()
	{
		bile = this;
		tag = ChatColor.GREEN + "[" + ChatColor.DARK_GRAY + "Bile" + ChatColor.GREEN + "]: " + ChatColor.GRAY;
		mod = new HashMap<File, Long>();
		las = new HashMap<File, Long>();
		folder = getDataFolder().getParentFile();
		getCommand("bile").setExecutor(this);

		for(Sound f : Sound.values())
		{
			if(f.name().contains("ORB"))
			{
				sx = f;
			}
		}

		getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable()
		{
			public void run()
			{
				onTick();
			}
		}, 10, 0);
	}

	@Override
	public void onDisable()
	{

	}

	public void onTick()
	{
		for(File i : folder.listFiles())
		{
			if(i.getName().toLowerCase().endsWith(".jar") && i.isFile())
			{
				if(!mod.containsKey(i))
				{
					getLogger().log(Level.INFO, "Now Tracking: " + i.getName());
					mod.put(i, i.length());
					las.put(i, i.lastModified());
				}

				if(mod.get(i) != i.length() || las.get(i) != i.lastModified())
				{
					mod.put(i, i.length());
					las.put(i, i.lastModified());

					for(Plugin j : Bukkit.getServer().getPluginManager().getPlugins())
					{
						if(PluginUtil.getPluginFileName(j.getName()).equals(i.getName()))
						{
							getLogger().log(Level.INFO, "File change detected: " + i.getName());
							getLogger().log(Level.INFO, "Identified Plugin: " + j.getName() + " <-> " + i.getName());
							getLogger().log(Level.INFO, "Reloading: " + j.getName());
							PluginUtil.reload(j);

							for(Player k : Bukkit.getOnlinePlayers())
							{
								if(k.hasPermission("bile.use"))
								{
									k.sendMessage(tag + "Reloaded " + ChatColor.WHITE + j.getName());
									k.playSound(k.getLocation(), sx, 1f, 1.9f);
								}
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
				sender.sendMessage(tag + "/bile load [plugin]");
				sender.sendMessage(tag + "/bile unload [plugin]");
				sender.sendMessage(tag + "/bile reload [plugin]");
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
								String s = PluginUtil.getPluginFileName(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									PluginUtil.load(args[i]);
									String n = PluginUtil.getPluginByName(args[i]).getName();
									sender.sendMessage(tag + "Loaded " + ChatColor.WHITE + n + ChatColor.GRAY + " from " + ChatColor.WHITE + s);
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

				else if(args[0].equalsIgnoreCase("unload"))
				{
					if(args.length > 1)
					{
						for(int i = 1; i < args.length; i++)
						{
							try
							{
								Plugin s = PluginUtil.getPluginByName(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									String sn = s.getName();
									PluginUtil.unload(s);
									String n = PluginUtil.getPluginFileName(args[i]);
									sender.sendMessage(tag + "Unloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n + ChatColor.GRAY + ")");
								}

								catch(Throwable e)
								{
									sender.sendMessage(tag + "Couldn't unload \"" + args[i] + "\".");
									e.printStackTrace();
								}
							}

							catch(Throwable e)
							{
								sender.sendMessage(tag + "Couldn't unload or find \"" + args[i] + "\".");
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
								Plugin s = PluginUtil.getPluginByName(args[i]);

								if(s == null)
								{
									sender.sendMessage(tag + "Couldn't find \"" + args[i] + "\".");
									continue;
								}

								try
								{
									String sn = s.getName();
									PluginUtil.unload(s);
									PluginUtil.load(sn);
									String n = PluginUtil.getPluginFileName(args[i]);
									sender.sendMessage(tag + "Reloaded " + ChatColor.WHITE + sn + ChatColor.GRAY + " (" + ChatColor.WHITE + n + ChatColor.GRAY + ")");
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
						sender.sendMessage(tag + "/bile load <PLUGIN>");
					}
				}
			}

			return true;
		}

		return false;
	}
}
