package com.volmit.bile.paper;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativePaperLifecycleTest {
    @Test
    public void preflightRejectsStartupHandlersWithoutRemovingAnything() {
        Object metadata = new Object();
        EventType commands = new EventType("commands");
        EventType registries = new EventType("registries");
        commands.handlers.add(new Registration(new Owner(metadata)));
        registries.handlers.add(new Registration(new Owner(metadata)));
        assertThrows(IllegalStateException.class,
                () -> NativePaperSupport.visitLifecycle(List.of(commands, registries), commands, metadata, false));
        assertEquals(1, commands.handlers.size());
        assertEquals(1, registries.handlers.size());
    }

    @Test
    public void cleanupUsesMetadataIdentityAcrossBootstrapAndPluginOwners() throws Exception {
        Object target = new Object();
        Object other = new Object();
        EventType commands = new EventType("commands");
        EventType registries = new EventType("registries");
        commands.handlers.add(new Registration(new Owner(target)));
        commands.handlers.add(new Registration(new Owner(target)));
        commands.handlers.add(new Registration(new Owner(other)));
        registries.handlers.add(new Registration(new Owner(other)));
        NativePaperSupport.visitLifecycle(List.of(commands, registries), commands, target, false);
        NativePaperSupport.visitLifecycle(List.of(commands, registries), commands, target, true);
        assertEquals(1, commands.handlers.size());
        assertEquals(1, registries.handlers.size());
    }

    @Test
    public void reflectionChoosesCompatibleOverloads() throws Exception {
        Overloaded receiver = new Overloaded();
        assertEquals("plugin", PaperReflection.call(receiver, "add", new Owner(new Object())));
        assertEquals("metadata", PaperReflection.call(receiver, "add", "example"));
    }

    public record Owner(Object getPluginMeta) {
    }

    public record Registration(Owner owner) {
    }

    public static final class EventType {
        private final List<Registration> handlers = new ArrayList<>();
        private final String name;

        private EventType(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public void removeMatching(Predicate<Registration> predicate) {
            handlers.removeIf(predicate);
        }
    }

    public static final class Overloaded {
        public String add(Owner owner) {
            return "plugin";
        }

        public String add(String metadata) {
            return "metadata";
        }
    }
}
