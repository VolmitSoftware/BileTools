package com.volmit.bile.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.localization.MessageCatalog;
import art.arcane.volmlib.util.localization.MessageKey;
import art.arcane.volmlib.util.localization.TextKey;
import com.volmit.bile.localization.BileMessages;
import org.bukkit.command.CommandSender;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandDescriptionCatalogTest {
    @Test
    public void directorDescriptionsMatchTypedCatalog() {
        MessageCatalog catalog = BileMessages.catalog();
        assertCommandType(CommandBile.class, catalog);
        assertCommandType(BileDebugCommands.class, catalog);
    }

    @Test
    public void debugIsAGroupWhoseHelpContainsDump() {
        assertEquals("debug", BileDebugCommands.class.getAnnotation(Director.class).name());
        assertTrue(Arrays.stream(BileDebugCommands.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Director.class)).filter(Objects::nonNull)
                .anyMatch(director -> director.name().equals("dump")));
        assertFalse(Arrays.stream(CommandBile.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Director.class)).filter(Objects::nonNull)
                .anyMatch(director -> director.name().equals("debug")));
        DirectorMiniMenu.DirectorHelpPage page = DirectorMiniMenu.resolveHelp(
                DirectorEngineFactory.create(new CommandBile(null)), List.of("debug")).orElseThrow();
        assertEquals("debug", page.node().getDescriptor().getName());
        assertEquals(List.of("dump", "version"), page.entries().stream()
                .map(node -> node.getDescriptor().getName()).toList());
    }

    @Test
    public void versionAliasIsHiddenFromRootHelp() throws ReflectiveOperationException {
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandBile(null));
        DirectorMiniMenu.DirectorHelpPage page = DirectorMiniMenu.resolveHelp(engine, List.of()).orElseThrow();

        assertTrue(CommandBile.class.getMethod("version", CommandSender.class)
                .getAnnotation(Director.class).hidden());
        assertFalse(page.entries().stream().anyMatch(node -> node.getDescriptor().getName().equals("version")));
    }

    private void assertCommandType(Class<?> type, MessageCatalog catalog) {
        assertDescription(type.getAnnotation(Director.class), catalog);
        for (Method method : type.getDeclaredMethods()) {
            Director director = method.getAnnotation(Director.class);
            if (director == null) {
                continue;
            }
            assertDescription(director, catalog);
            for (Parameter parameter : method.getParameters()) {
                Param param = parameter.getAnnotation(Param.class);
                if (param != null && !param.descriptionKey().isBlank()) {
                    MessageKey key = catalog.require(param.descriptionKey());
                    assertTrue(key instanceof TextKey);
                    assertEquals(param.description(), ((TextKey) key).english());
                }
            }
        }
    }

    private void assertDescription(Director director, MessageCatalog catalog) {
        MessageKey key = catalog.require(director.descriptionKey());
        assertTrue(key instanceof TextKey);
        assertEquals(director.description(), ((TextKey) key).english());
    }
}
