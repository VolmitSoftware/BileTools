package com.volmit.volume.cluster;

public interface IClusterPort<T>
{
	public DataCluster toCluster(T t) throws Exception;

	public T fromCluster(DataCluster c) throws Exception;
}
