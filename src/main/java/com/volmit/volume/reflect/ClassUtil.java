package com.volmit.volume.reflect;

import com.volmit.volume.lang.collections.GMap;

/**
 * Class utilities (object boxing)
 *
 * @author cyberpwn
 */
public class ClassUtil
{
	public static GMap<Class<?>, Class<?>> boxes;
	public static GMap<Class<?>, Class<?>> unboxes;

	/**
	 * To wrapper from primative or the same class returned
	 *
	 * @param c
	 *            the class
	 * @return the wrapper class or the same class
	 */
	public static Class<?> toWrapper(Class<?> c)
	{
		if(isPrimative(c))
		{
			return boxes.get(c);
		}

		return c;
	}

	/**
	 * To primative from wrapper or the same class returned
	 *
	 * @param c
	 *            the class
	 * @return the primative class or the same class
	 */
	public static Class<?> toPrimative(Class<?> c)
	{
		if(isWrapper(c))
		{
			return unboxes.get(c);
		}

		return c;
	}

	/**
	 * Check if this class is involved with primative boxing
	 *
	 * @param c
	 *            the class
	 * @return true if its a wrapped primative or a primative
	 */
	public static boolean isWrapperOrPrimative(Class<?> c)
	{
		return isPrimative(c) || isWrapper(c);
	}

	/**
	 * Check if this class is a primative type
	 *
	 * @param c
	 *            the class
	 * @return true if it is
	 */
	public static boolean isPrimative(Class<?> c)
	{
		return boxes.containsKey(c);
	}

	/**
	 * Check if this class is a wrapper of a primative
	 *
	 * @param c
	 *            the class
	 * @return true if it is
	 */
	public static boolean isWrapper(Class<?> c)
	{
		return boxes.containsValue(c);
	}

	static
	{
		boxes = new GMap<Class<?>, Class<?>>();
		boxes.put(int.class, Integer.class);
		boxes.put(long.class, Long.class);
		boxes.put(short.class, Short.class);
		boxes.put(char.class, Character.class);
		boxes.put(byte.class, Byte.class);
		boxes.put(boolean.class, Boolean.class);
		boxes.put(float.class, Float.class);

		unboxes = new GMap<Class<?>, Class<?>>();
		unboxes.put(Integer.class, int.class);
		unboxes.put(Long.class, long.class);
		unboxes.put(Short.class, short.class);
		unboxes.put(Character.class, char.class);
		unboxes.put(Byte.class, byte.class);
		unboxes.put(Boolean.class, boolean.class);
		unboxes.put(Float.class, float.class);
	}

	public static Class<?> flip(Class<?> c)
	{
		if(isPrimative(c))
		{
			return toWrapper(c);
		}

		if(isWrapper(c))
		{
			return toPrimative(c);
		}

		return c;
	}
}
