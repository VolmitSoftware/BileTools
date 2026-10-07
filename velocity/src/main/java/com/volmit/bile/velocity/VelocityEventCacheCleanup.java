package com.volmit.bile.velocity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class VelocityEventCacheCleanup {
    private VelocityEventCacheCleanup() {
    }

    static void validateLayout(ClassLoader proxyLoader) throws ReflectiveOperationException {
        Class<?> manager = Class.forName("com.velocitypowered.proxy.event.VelocityEventManager", false, proxyLoader);
        manager.getDeclaredField("untargetedMethodHandlers").setAccessible(true);
        manager.getDeclaredField("handlersCache").setAccessible(true);
        manager.getDeclaredField("eventTypeTracker").setAccessible(true);
        Class<?> tracker = Class.forName("com.velocitypowered.proxy.event.EventTypeTracker", false, proxyLoader);
        tracker.getDeclaredField("friends").setAccessible(true);
        Class<?> cache = Class.forName("com.github.benmanes.caffeine.cache.Cache", false, proxyLoader);
        cache.getMethod("asMap");
        cache.getMethod("invalidateAll", Iterable.class);
        cache.getMethod("cleanUp");
    }

    static void remove(Object manager, ClassLoader owner) throws ReflectiveOperationException {
        Class<?> cacheType = Class.forName("com.github.benmanes.caffeine.cache.Cache", false,
                manager.getClass().getClassLoader());
        for (String name : List.of("untargetedMethodHandlers", "handlersCache")) {
            Object cache = read(manager, name);
            Map<?, ?> map = (Map<?, ?>) cacheType.getMethod("asMap").invoke(cache);
            List<Object> keys = ownedKeys(map, owner);
            cacheType.getMethod("invalidateAll", Iterable.class).invoke(cache, keys);
            cacheType.getMethod("cleanUp").invoke(cache);
        }
        Object tracker = read(manager, "eventTypeTracker");
        Map<?, ?> friends = (Map<?, ?>) read(tracker, "friends");
        List<Object> affected = new ArrayList<>();
        for (Map.Entry<?, ?> entry : friends.entrySet()) {
            if (owned(entry.getKey(), owner) || containsOwnedType(entry.getValue(), owner)) {
                affected.add(entry.getKey());
            }
        }
        for (Object key : affected) {
            friends.remove(key);
        }
    }

    static List<Object> ownedKeys(Map<?, ?> cache, ClassLoader owner) {
        List<Object> keys = new ArrayList<>();
        for (Object key : cache.keySet()) {
            if (owned(key, owner)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static boolean containsOwnedType(Object value, ClassLoader owner) {
        if (value instanceof Collection<?> classes) {
            for (Object type : classes) {
                if (owned(type, owner)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean owned(Object key, ClassLoader owner) {
        Class<?> type = key instanceof Class<?> candidate ? candidate
                : key instanceof Method method ? method.getDeclaringClass() : null;
        return type != null && type.getClassLoader() == owner;
    }

    private static Object read(Object instance, String name) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
