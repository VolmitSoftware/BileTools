package com.volmit.bile;

import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class SpigotPluginLoaderCleanup {
    private SpigotPluginLoaderCleanup() {
    }

    static void validate(Plugin plugin) throws ReflectiveOperationException {
        inspect(plugin.getPluginLoader(), plugin.getClass().getClassLoader());
    }

    static void cleanup(Plugin plugin) {
        try {
            inspect(plugin.getPluginLoader(), plugin.getClass().getClassLoader()).detach();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot detach Spigot classloader for " + plugin.getName(), failure);
        }
    }

    static Registration inspect(Object pluginLoader, ClassLoader owner) throws ReflectiveOperationException {
        Field loadersField = pluginLoader.getClass().getDeclaredField("loaders");
        loadersField.setAccessible(true);
        Object loaders = loadersField.get(pluginLoader);
        if (!(loaders instanceof List<?> registrations)) {
            throw new IllegalStateException("Spigot plugin classloader registrations are unavailable");
        }
        Method classesMethod = owner.getClass().getDeclaredMethod("getClasses");
        classesMethod.setAccessible(true);
        Object classes = classesMethod.invoke(owner);
        if (!(classes instanceof Collection<?> loadedClasses)) {
            throw new IllegalStateException("Spigot loaded classes are unavailable");
        }
        List<Class<?>> ownedClasses = new ArrayList<>();
        for (Object value : loadedClasses) {
            if (!(value instanceof Class<?> type)) {
                throw new IllegalStateException("Spigot loaded-class registry contains a non-class entry");
            }
            if (type.getClassLoader() == owner) {
                ownedClasses.add(type);
            }
        }
        return new Registration(owner, registrations, ownedClasses);
    }

    record Registration(ClassLoader owner, List<?> loaders, List<Class<?>> classes) {
        void detach() {
            loaders.removeIf(loader -> loader == owner);
            for (Class<?> type : classes) {
                if (ConfigurationSerializable.class.isAssignableFrom(type)) {
                    ConfigurationSerialization.unregisterClass(type.asSubclass(ConfigurationSerializable.class));
                }
            }
        }
    }
}
