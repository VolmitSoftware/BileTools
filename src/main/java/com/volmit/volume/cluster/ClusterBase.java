package com.volmit.volume.cluster;

public class ClusterBase<T> implements ICluster<T>
{
	private T t;
	private Class<? extends T> type;

	public ClusterBase(Class<? extends T> type, T t)
	{
		this.t = t;
		this.type = type;
	}

	@Override
	public Class<? extends T> getType()
	{
		return type;
	}

	@Override
	public void set(T t)
	{
		this.t = t;
	}

	@Override
	public T get()
	{
		return t;
	}
}
