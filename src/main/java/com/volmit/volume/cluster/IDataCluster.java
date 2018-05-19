package com.volmit.volume.cluster;

import java.util.List;

import com.volmit.volume.lang.collections.GList;
import com.volmit.volume.lang.collections.GMap;

public interface IDataCluster
{
	public ICluster<?> getCluster(String key);

	public GList<String> k();

	public GMap<String, ICluster<?>> map();

	public GMap<String, String> getComments();

	public void remove(String key);

	public void removeComment(String key);

	public void removeComments();

	public boolean hasComment(String key);

	public String getComment(String key);

	public void setComment(String key, String comment);

	public DataCluster crop(String key);

	public DataCluster copy();

	public boolean has(String key);

	public boolean has(String key, Class<?> c);

	public <T> T get(String key);

	public void set(String key, Object o);

	public String getString(String key);

	public Boolean getBoolean(String key);

	public Integer getInt(String key);

	public Long getLong(String key);

	public Float getFloat(String key);

	public Double getDouble(String key);

	public Short getShort(String key);

	public GList<String> getStringList(String key);

	public void set(String key, String o);

	public void set(String key, boolean o);

	public void set(String key, int o);

	public void set(String key, long o);

	public void set(String key, float o);

	public void set(String key, double o);

	public void set(String key, short o);

	public void set(String key, GList<String> o);

	public void set(String key, List<String> o);
}
