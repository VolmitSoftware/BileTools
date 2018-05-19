package com.volmit.volume.cluster;

public interface ICluster<T>
{
	public Class<? extends T> getType();

	public void set(T t);

	public T get();
}
