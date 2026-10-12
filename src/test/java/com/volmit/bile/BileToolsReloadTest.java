package com.volmit.bile;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.util.inventorygui.BukkitInventoryShutdown;
import art.arcane.volmlib.util.localization.BukkitLanguageSwitcher;
import com.volmit.bile.watch.AutomaticReloadQueue;
import com.volmit.bile.watch.DeletionGraceQueue;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class BileToolsReloadTest {
    @Test
    public void repeatedReloadHooksWaitForTheSameEditorCleanup() throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<Void> first = fixture.plugin.commitReload(ReloadAware.PreUnloadReason.HOT_RELOAD)
                .toCompletableFuture();
        CompletableFuture<Void> second = fixture.plugin.commitReload(ReloadAware.PreUnloadReason.HOT_UNLOAD)
                .toCompletableFuture();
        AtomicBoolean unloaded = new AtomicBoolean();
        first.thenRun(() -> unloaded.set(true));

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertFalse(unloaded.get());
        assertSame(fixture.switcher, fixture.plugin.languageSwitcher());

        fixture.cleanup.complete(null);

        first.join();
        second.join();
        assertTrue(unloaded.get());
    }

    @Test
    public void failedEditorCleanupPreventsUnloadAndRetainsTheSwitcher() throws Exception {
        Fixture fixture = fixture();
        CompletableFuture<Void> completion = fixture.plugin.commitReload(ReloadAware.PreUnloadReason.HOT_RELOAD)
                .toCompletableFuture();
        AtomicBoolean unloaded = new AtomicBoolean();
        completion.thenRun(() -> unloaded.set(true));
        IllegalStateException failure = new IllegalStateException("Inventory owner rejected cleanup");

        fixture.cleanup.completeExceptionally(failure);

        assertSame(failure, assertThrows(CompletionException.class, completion::join).getCause());
        assertFalse(unloaded.get());
        assertSame(fixture.switcher, fixture.plugin.languageSwitcher());
    }

    @Test
    public void reloadWithoutLanguageEditorsCompletesImmediately() throws Exception {
        BileTools plugin = plugin();

        plugin.commitReload(ReloadAware.PreUnloadReason.HOT_UNLOAD).toCompletableFuture().join();
    }

    private static Fixture fixture() throws Exception {
        BileTools plugin = plugin();
        Unsafe unsafe = unsafe();
        BukkitLanguageSwitcher switcher = (BukkitLanguageSwitcher) unsafe.allocateInstance(BukkitLanguageSwitcher.class);
        Class<?> editorType = Class.forName("art.arcane.volmlib.util.localization.BukkitLanguageEditor");
        Object editor = unsafe.allocateInstance(editorType);
        CompletableFuture<Void> cleanup = new CompletableFuture<>();
        set(editorType, editor, "inventoryShutdown", new BukkitInventoryShutdown(plugin, List.of()));
        set(editorType, editor, "shutdownCompletion", cleanup);
        set(BukkitLanguageSwitcher.class, switcher, "editor", editor);
        set(BukkitLanguageSwitcher.class, switcher, "closed", true);
        set(BileTools.class, plugin, "languageSwitcher", switcher);
        return new Fixture(plugin, switcher, cleanup);
    }

    private static BileTools plugin() throws Exception {
        BileTools plugin = (BileTools) unsafe().allocateInstance(TestPlugin.class);
        for (Field field : BileTools.class.getDeclaredFields()) {
            if (field.getType() == Map.class) {
                set(BileTools.class, plugin, field.getName(), new HashMap<>());
            } else if (field.getType() == Set.class) {
                set(BileTools.class, plugin, field.getName(), new HashSet<>());
            } else if (field.getType() == ConcurrentLinkedQueue.class) {
                set(BileTools.class, plugin, field.getName(), new ConcurrentLinkedQueue<>());
            }
        }
        set(BileTools.class, plugin, "deletionTombstones", new DeletionGraceQueue(1L));
        set(BileTools.class, plugin, "automaticReloadQueue", new AutomaticReloadQueue(1L));
        return plugin;
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Fixture(BileTools plugin, BukkitLanguageSwitcher switcher, CompletableFuture<Void> cleanup) {
    }

    private static final class TestPlugin extends BileTools {
        @Override
        public Logger getLogger() {
            return Logger.getAnonymousLogger();
        }
    }
}
