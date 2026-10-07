package com.volmit.bile.velocity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocityPacketCleanupTest {
    @Test
    void removesOwnedMappingsWithoutInvokingPacketConstructors() throws Exception {
        try (URLClassLoader owner = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            Object supplier = Proxy.newProxyInstance(owner, new Class<?>[]{Supplier.class}, (proxy, method, arguments) -> {
                throw new AssertionError("packet factory must not run during cleanup");
            });
            Map<Class<?>, Integer> classes = new LinkedHashMap<>();
            classes.put(supplier.getClass(), 12);
            classes.put(String.class, 13);
            Map<Integer, Object> suppliers = new LinkedHashMap<>();
            suppliers.put(12, supplier);
            suppliers.put(13, new Object());

            VelocityPacketCleanup.removeOwned(classes, suppliers, owner);

            assertFalse(classes.containsKey(supplier.getClass()));
            assertFalse(suppliers.containsKey(12));
            assertTrue(classes.containsKey(String.class));
            assertTrue(suppliers.containsKey(13));
        }
    }

    @Test
    void sharedPacketIdentifiersAndAmbiguousSuppliersArePreserved() throws Exception {
        try (URLClassLoader owner = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            Object supplier = Proxy.newProxyInstance(owner, new Class<?>[]{Supplier.class}, (proxy, method, arguments) -> null);
            Map<Class<?>, Integer> classes = new LinkedHashMap<>();
            classes.put(supplier.getClass(), 12);
            classes.put(String.class, 12);
            Map<Integer, Object> suppliers = new LinkedHashMap<>();
            suppliers.put(12, supplier);
            suppliers.put(14, new Object());

            VelocityPacketCleanup.removeOwned(classes, suppliers, owner);

            assertFalse(classes.containsKey(supplier.getClass()));
            assertTrue(classes.containsKey(String.class));
            assertTrue(suppliers.containsKey(12));
            assertTrue(suppliers.containsKey(14));
        }
    }
}
