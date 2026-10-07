package com.volmit.bile.velocity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class VelocityPacketCleanup {
    private VelocityPacketCleanup() {
    }

    static void validateLayout(ClassLoader proxyLoader) throws ReflectiveOperationException {
        Class<?> state = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry", false, proxyLoader);
        Class<?> packets = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry$PacketRegistry", false, proxyLoader);
        Class<?> protocol = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry$PacketRegistry$ProtocolRegistry", false, proxyLoader);
        for (String field : List.of("clientbound", "serverbound")) {
            state.getDeclaredField(field).setAccessible(true);
        }
        packets.getDeclaredField("versions").setAccessible(true);
        protocol.getDeclaredField("packetClassToId").setAccessible(true);
        protocol.getDeclaredField("packetIdToSupplier").setAccessible(true);
    }

    static void remove(ClassLoader proxyLoader, ClassLoader owner) throws ReflectiveOperationException {
        Class<?> stateType = Class.forName("com.velocitypowered.proxy.protocol.StateRegistry", false, proxyLoader);
        Object[] states = stateType.getEnumConstants();
        if (states == null) {
            throw new IllegalStateException("Velocity packet state registry is not an enum");
        }
        List<PacketMaps> registries = new ArrayList<>();
        for (Object state : states) {
            for (String direction : List.of("clientbound", "serverbound")) {
                Object registry = read(stateType, state, direction);
                Object versions = read(registry.getClass(), registry, "versions");
                if (!(versions instanceof Map<?, ?> versionMap)) {
                    throw new IllegalStateException("Velocity packet versions are not a map");
                }
                for (Object version : versionMap.values()) {
                    Object classes = read(version.getClass(), version, "packetClassToId");
                    Object suppliers = read(version.getClass(), version, "packetIdToSupplier");
                    if (!(classes instanceof Map<?, ?> classMap) || !(suppliers instanceof Map<?, ?> supplierMap)) {
                        throw new IllegalStateException("Velocity packet registrations are not maps");
                    }
                    registries.add(new PacketMaps(classMap, supplierMap));
                }
            }
        }
        for (PacketMaps registry : registries) {
            removeOwned(registry.classes(), registry.suppliers(), owner);
        }
    }

    static void removeOwned(Map<?, ?> classes, Map<?, ?> suppliers, ClassLoader owner) {
        List<Object> ownedClasses = new ArrayList<>();
        for (Map.Entry<?, ?> entry : classes.entrySet()) {
            if (entry.getKey() instanceof Class<?> type && type.getClassLoader() == owner) {
                ownedClasses.add(type);
            }
        }
        for (Object type : ownedClasses) {
            classes.remove(type);
        }
        List<Object> ownedIds = new ArrayList<>();
        for (Map.Entry<?, ?> entry : suppliers.entrySet()) {
            Object supplier = entry.getValue();
            if (supplier != null && supplier.getClass().getClassLoader() == owner && !classes.containsValue(entry.getKey())) {
                ownedIds.add(entry.getKey());
            }
        }
        for (Object id : ownedIds) {
            suppliers.remove(id);
        }
    }

    private static Object read(Class<?> type, Object instance, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private record PacketMaps(Map<?, ?> classes, Map<?, ?> suppliers) {
    }
}
