package com.volmit.bile;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class SpigotPluginLoaderCleanupTest {
    @Test
    public void detachesDisabledLoaderAndOnlyItsSerializationClasses() throws Exception {
        LoadedClasses owner = new LoadedClasses();
        LoadedClasses survivor = new LoadedClasses();
        LoaderRegistry registry = new LoaderRegistry(List.of(owner, survivor));
        Class<? extends ConfigurationSerializable> owned = owner.defineSerializable();
        owner.classes.add(SerializedValue.class);
        ConfigurationSerialization.registerClass(owned, "BileOwnedSerialization");
        ConfigurationSerialization.registerClass(SerializedValue.class, "BileSharedSerialization");
        try {
            SpigotPluginLoaderCleanup.inspect(registry, owner).detach();

            assertEquals(List.of(survivor), registry.loaders);
            assertNull(ConfigurationSerialization.getClassByAlias("BileOwnedSerialization"));
            assertSame(SerializedValue.class, ConfigurationSerialization.getClassByAlias("BileSharedSerialization"));
            SpigotPluginLoaderCleanup.inspect(registry, owner).detach();
            assertEquals(List.of(survivor), registry.loaders);
        } finally {
            ConfigurationSerialization.unregisterClass(owned);
            ConfigurationSerialization.unregisterClass(SerializedValue.class);
        }
    }

    @Test
    public void leavesSerializationAliasThatAnotherLoaderReplaced() throws Exception {
        LoadedClasses owner = new LoadedClasses();
        Class<? extends ConfigurationSerializable> owned = owner.defineSerializable();
        ConfigurationSerialization.registerClass(owned, "BileReplacedSerialization");
        ConfigurationSerialization.registerClass(SerializedValue.class, "BileReplacedSerialization");
        try {
            SpigotPluginLoaderCleanup.inspect(new LoaderRegistry(List.of(owner)), owner).detach();

            assertSame(SerializedValue.class, ConfigurationSerialization.getClassByAlias("BileReplacedSerialization"));
        } finally {
            ConfigurationSerialization.unregisterClass(owned);
            ConfigurationSerialization.unregisterClass(SerializedValue.class);
        }
    }

    @Test
    public void missingLoaderCapabilitiesRejectPreflight() {
        assertThrows(NoSuchFieldException.class,
                () -> SpigotPluginLoaderCleanup.inspect(new Object(), new LoadedClasses()));
        assertThrows(NoSuchMethodException.class,
                () -> SpigotPluginLoaderCleanup.inspect(new LoaderRegistry(List.of()), getClass().getClassLoader()));
    }

    private static final class LoaderRegistry {
        private final List<ClassLoader> loaders;

        private LoaderRegistry(List<ClassLoader> loaders) {
            this.loaders = new ArrayList<>(loaders);
        }
    }

    private static final class LoadedClasses extends ClassLoader {
        private final List<Class<?>> classes = new ArrayList<>();

        private LoadedClasses() {
            super(SpigotPluginLoaderCleanupTest.class.getClassLoader());
        }

        private Collection<Class<?>> getClasses() {
            return classes;
        }

        private Class<? extends ConfigurationSerializable> defineSerializable() throws Exception {
            String name = SerializedValue.class.getName();
            try (InputStream source = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                byte[] bytes = source.readAllBytes();
                Class<?> type = defineClass(name, bytes, 0, bytes.length);
                classes.add(type);
                return type.asSubclass(ConfigurationSerializable.class);
            }
        }
    }

    public static final class SerializedValue implements ConfigurationSerializable {
        @Override
        public Map<String, Object> serialize() {
            return Map.of();
        }
    }
}
