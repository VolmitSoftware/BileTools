package com.volmit.volume.lang.collections;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.volmit.volume.cluster.DataCluster;
import com.volmit.volume.cluster.IClusterPort;
import com.volmit.volume.lang.format.F;

public class JSONClusterPort implements IClusterPort<FileConfiguration>
{
	public static String applyComments(DataCluster cc, String yml)
	{
		boolean f = false;
		String src = "";

		for(String i : yml.split("\n"))
		{
			if(i.contains(":"))
			{
				String key = i.trim().split("\\Q: \\E")[0];
				String spc = F.repeat(" ", i.length() - i.trim().length());

				search: for(String j : cc.k())
				{
					if(!cc.hasComment(j))
					{
						continue;
					}

					if(j.split("\\.")[j.split("\\.").length - 1].equals(key))
					{
						if(f)
						{
							src += "\n";
						}

						for(String k : F.wrap(cc.getComment(j), 64).split("\n"))
						{
							src += spc + "# " + k + "\n";
							break search;
						}
					}
				}
			}

			f = true;
			src += i + "\n";
		}

		return src;
	}

	@Override
	public DataCluster toCluster(FileConfiguration t) throws Exception
	{
		DataCluster cc = new DataCluster();

		for(String i : t.getKeys(true))
		{
			if(t.isConfigurationSection(i))
			{
				continue;
			}

			cc.set(i, t.get(i));
		}

		return cc;
	}

	@Override
	public FileConfiguration fromCluster(DataCluster c) throws Exception
	{
		FileConfiguration fc = new YamlConfiguration();

		for(String i : c.k())
		{
			fc.set(i, c.get(i));
		}

		return fc;
	}

}
