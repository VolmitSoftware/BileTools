package com.volmit.bile.command;

import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import com.volmit.bile.BileTools;
import com.volmit.bile.localization.BileLocalization;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CommandVersionPermissionTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void versionRequiresRootPermissionForCanonicalAndAbbreviatedCommands() throws Exception {
        try (BileLocalization localization = new BileLocalization(
                temporaryFolder.newFolder(), Logger.getAnonymousLogger(), "en_US")) {
            BileTools plugin = plugin(localization);
            for (Set<String> permissions : List.of(Set.<String>of(), Set.of("biletools.debug"))) {
                List<String> messages = new ArrayList<>();
                CommandSender sender = sender(messages, permissions);
                DirectorContextRegistry contexts = new DirectorContextRegistry();
                contexts.register(CommandSender.class, (invocation, arguments) -> sender);
                DirectorRuntimeEngine engine = DirectorEngineFactory.create(new CommandBile(plugin),
                        DirectorEngineOptions.builder().contexts(contexts).build());

                for (List<String> arguments : List.of(
                        List.of("version"), List.of("debug", "version"),
                        List.of("debug", "ver"), List.of("debug", "v"))) {
                    messages.clear();
                    assertTrue(engine.execute(new DirectorInvocation(new TestSender(sender), "biletools", arguments)).isSuccess());
                    assertEquals(List.of("[Bile]: You need bile.use or OP."), messages);
                }
            }
        }
    }

    private static BileTools plugin(BileLocalization localization) throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        BileTools plugin = (BileTools) unsafe.allocateInstance(BileTools.class);
        Field localizationField = BileTools.class.getDeclaredField("localization");
        localizationField.setAccessible(true);
        localizationField.set(plugin, localization);
        return plugin;
    }

    private static CommandSender sender(List<String> messages, Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(CommandVersionPermissionTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("hasPermission") || method.getName().equals("isPermissionSet")) {
                        return permissions.contains(String.valueOf(arguments[0]));
                    }
                    if (method.getName().equals("getName")) {
                        return "guest";
                    }
                    if (method.getName().equals("sendMessage") && arguments != null) {
                        for (Object argument : arguments) {
                            if (argument instanceof Component component) {
                                messages.add(PlainTextComponentSerializer.plainText().serialize(component));
                            } else if (argument instanceof String message) {
                                messages.add(message);
                            }
                        }
                        return null;
                    }
                    if (method.getName().equals("sendRichMessage") && arguments != null) {
                        messages.add(PlainTextComponentSerializer.plainText().serialize(
                                MiniMessage.miniMessage().deserialize(String.valueOf(arguments[0]))));
                        return null;
                    }
                    return method.getReturnType() == boolean.class ? false : null;
                });
    }

    private record TestSender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() {
            return sender.getName();
        }

        @Override
        public boolean isPlayer() {
            return false;
        }

        @Override
        public void sendMessage(String message) {
            sender.sendMessage(message);
        }
    }
}
