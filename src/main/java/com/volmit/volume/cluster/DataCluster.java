package com.volmit.volume.cluster;

import java.util.ArrayList;
import java.util.List;

import com.volmit.volume.lang.collections.GList;
import com.volmit.volume.lang.collections.GMap;
import com.volmit.volume.reflect.ClassUtil;

public class DataCluster implements IDataCluster
{
	private GMap<String, ICluster<?>> clusters;
	private GMap<String, String> comments;

	public DataCluster()
	{
		this(new GMap<String, ICluster<?>>());
	}

	public DataCluster(GMap<String, ICluster<?>> clusters)
	{
		this.clusters = clusters;
		comments = new GMap<String, String>();
	}

	@Override
	public ICluster<?> getCluster(String key)
	{
		return clusters.get(key);
	}

	@Override
	public GList<String> k()
	{
		return clusters.k();
	}

	@Override
	public GMap<String, ICluster<?>> map()
	{
		return clusters.copy();
	}

	@Override
	public DataCluster crop(String key)
	{
		DataCluster cc = new DataCluster();

		for(String i : k())
		{
			if(i.startsWith(key + "."))
			{
				cc.set((i).replace(key + ".", ""), (Object) get(i));

				if(hasComment(i))
				{
					cc.setComment((i).replace(key + ".", ""), getComment(i));
				}
			}
		}

		return cc;
	}

	@Override
	public DataCluster copy()
	{
		return new DataCluster(clusters);
	}

	@Override
	public boolean has(String key)
	{
		return k().contains(key);
	}

	@Override
	public boolean has(String key, Class<?> c)
	{
		if(!has(key))
		{
			return false;
		}

		if(ClassUtil.isWrapperOrPrimative(c))
		{
			Class<?> cc = get(key).getClass();

			if(ClassUtil.isPrimative(c) != ClassUtil.isPrimative(cc))
			{
				return cc.equals(ClassUtil.flip(c));
			}

			return cc.equals(c);
		}

		return get(key).getClass().equals(c);
	}

	@SuppressWarnings("unchecked")
	@Override
	public <T> T get(String key)
	{
		return (T) clusters.get(key).get();
	}

	@SuppressWarnings("unchecked")
	@Override
	public void set(String key, Object o)
	{
		Class<?> c = o.getClass();

		if(c.equals(Integer.class))
		{
			set(key, ((Integer) o).intValue());
		}

		else if(c.equals(int.class))
		{
			set(key, (int) o);
		}

		else if(c.equals(Double.class))
		{
			set(key, ((Double) o).doubleValue());
		}

		else if(c.equals(double.class))
		{
			set(key, (double) o);
		}

		else if(c.equals(Float.class))
		{
			set(key, ((Float) o).floatValue());
		}

		else if(c.equals(float.class))
		{
			set(key, (float) o);
		}

		else if(c.equals(Long.class))
		{
			set(key, ((Long) o).longValue());
		}

		else if(c.equals(long.class))
		{
			set(key, (long) o);
		}

		else if(c.equals(Boolean.class))
		{
			set(key, ((Boolean) o).booleanValue());
		}

		else if(c.equals(boolean.class))
		{
			set(key, (boolean) o);
		}

		else if(c.equals(Short.class))
		{
			set(key, ((Short) o).shortValue());
		}

		else if(c.equals(short.class))
		{
			set(key, (short) o);
		}

		else if(c.equals(GList.class))
		{
			set(key, (GList<String>) o);
		}

		else if(c.equals(ArrayList.class))
		{
			set(key, new GList<String>((ArrayList<String>) o));
		}

		else if(c.equals(String.class))
		{
			set(key, (String) o);
		}

		else
		{
			throw new RuntimeException("Unable to set type from " + c.toString());
		}
	}

	@Override
	public String getString(String key)
	{
		return get(key);
	}

	@Override
	public Boolean getBoolean(String key)
	{
		return get(key);
	}

	@Override
	public Integer getInt(String key)
	{
		return get(key);
	}

	@Override
	public Long getLong(String key)
	{
		return get(key);
	}

	@Override
	public Float getFloat(String key)
	{
		return get(key);
	}

	@Override
	public Double getDouble(String key)
	{
		return get(key);
	}

	@Override
	public Short getShort(String key)
	{
		return get(key);
	}

	@Override
	public GList<String> getStringList(String key)
	{
		return get(key);
	}

	@Override
	public void set(String key, String o)
	{
		clusters.put(key, new ClusterString(o));
	}

	@Override
	public void set(String key, boolean o)
	{
		clusters.put(key, new ClusterBoolean(o));
	}

	@Override
	public void set(String key, int o)
	{
		clusters.put(key, new ClusterInteger(o));
	}

	@Override
	public void set(String key, long o)
	{
		clusters.put(key, new ClusterLong(o));
	}

	@Override
	public void set(String key, float o)
	{
		clusters.put(key, new ClusterFloat(o));
	}

	@Override
	public void set(String key, double o)
	{
		clusters.put(key, new ClusterDouble(o));
	}

	@Override
	public void set(String key, short o)
	{
		clusters.put(key, new ClusterShort(o));
	}

	@Override
	public void set(String key, GList<String> o)
	{
		clusters.put(key, new ClusterStringList(o));
	}

	@Override
	public void set(String key, List<String> o)
	{
		set(key, new GList<String>(o));
	}

	@Override
	public GMap<String, String> getComments()
	{
		return comments;
	}

	@Override
	public boolean hasComment(String key)
	{
		return getComment(key) != null;
	}

	@Override
	public String getComment(String key)
	{
		return comments.get(key);
	}

	@Override
	public void setComment(String key, String comment)
	{
		comments.put(key, comment);
	}

	@Override
	public void remove(String key)
	{
		removeComment(key);
		clusters.remove(key);
	}

	@Override
	public void removeComment(String key)
	{
		comments.remove(key);
	}

	@Override
	public void removeComments()
	{
		comments.clear();
	}
}
