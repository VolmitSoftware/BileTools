package com.volmit.bile.velocity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VelocityEventCacheCleanupTest {
    @Test
    void onlyOwnedMethodAndEventTypeKeysAreEvicted() throws Exception {
        try (URLClassLoader owner = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            Object instance = Proxy.newProxyInstance(owner, new Class<?>[]{Runnable.class}, (proxy, method, arguments) -> null);
            Class<?> owned = instance.getClass();
            Method method = owned.getMethod("run");
            Map<Object, Object> cache = new LinkedHashMap<>();
            cache.put(owned, new Object());
            cache.put(method, new Object());
            cache.put(String.class, new Object());
            cache.put(Runnable.class.getMethod("run"), new Object());

            assertEquals(List.of(owned, method), VelocityEventCacheCleanup.ownedKeys(cache, owner));
        }
    }
}
