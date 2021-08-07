package com.volmit.volume.cluster;

import com.volmit.volume.lang.collections.GList;

public class ClusterStringList extends ClusterBase<GList<String>>
{
	@SuppressWarnings("unchecked")
	public ClusterStringList(GList<String> t)
	{
		super((Class<? extends GList<String>>) (Class<? extends GList>) GList.class, t);
	}
}
