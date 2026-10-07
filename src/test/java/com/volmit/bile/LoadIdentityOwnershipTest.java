package com.volmit.bile;

import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LoadIdentityOwnershipTest {
    @Test
    public void aliasCannotSelectAnUnpreparedProviderForReplacement() {
        Plugin provider = plugin("Provider");
        PluginManager manager = manager(new Plugin[]{provider}, provider);

        InvalidPluginException failure = assertThrows(InvalidPluginException.class,
                () -> BileUtils.resolveLoadedIdentity("ProvidedApi", manager));

        assertTrue(failure.getMessage().contains("already provided by Provider"));
    }

    @Test
    public void canonicalIdentityUsesExistingInstanceRegardlessOfCase() throws Exception {
        Plugin provider = plugin("Provider");

        assertSame(provider, BileUtils.resolveLoadedIdentity("provider", manager(new Plugin[]{provider}, null)));
    }

    @Test
    public void unclaimedIdentityCanCreateAFreshLoadPlan() throws Exception {
        assertNull(BileUtils.resolveLoadedIdentity("NewPlugin", manager(new Plugin[0], null)));
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(LoadIdentityOwnershipTest.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PluginManager manager(Plugin[] plugins, Plugin aliasOwner) {
        return (PluginManager) Proxy.newProxyInstance(LoadIdentityOwnershipTest.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getPlugins" -> plugins;
                    case "getPlugin" -> aliasOwner;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
