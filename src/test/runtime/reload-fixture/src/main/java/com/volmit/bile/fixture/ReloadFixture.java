package com.volmit.bile.fixture;

import art.arcane.volmlib.integration.ReloadAware;
import art.arcane.volmlib.integration.ReloadPreparation;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class ReloadFixture extends JavaPlugin implements ReloadAware {
    private int loads;
    private int enables;
    private int prepares;
    private int commits;
    private int cancels;
    private boolean draining;

    @Override
    public void onLoad() {
        loads++;
        rememberLoader();
        if ("fail-load".equals(mode())) {
            throw new IllegalStateException("FIXTURE deliberate onLoad failure");
        }
    }

    @Override
    public void onEnable() {
        enables++;
        getLogger().info(status());
        if ("fail-enable".equals(mode())) {
            throw new IllegalStateException("FIXTURE deliberate onEnable failure");
        }
    }

    @Override
    public void onDisable() {
        if (Files.exists(getDataFolder().toPath().resolve("fail-disable"))) {
            throw new IllegalStateException("FIXTURE deliberate disable failure");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (arguments.length > 0 && "gc".equals(arguments[0])) {
            System.gc();
        }
        sender.sendMessage(status());
        return true;
    }

    @Override
    public CompletionStage<ReloadPreparation> prepareReload(PreUnloadReason reason) {
        prepares++;
        draining = true;
        if (Files.exists(getDataFolder().toPath().resolve("veto"))) {
            return CompletableFuture.completedFuture(ReloadPreparation.refuse("fixture veto"));
        }
        CompletableFuture<ReloadPreparation> result = new CompletableFuture<>();
        FoliaScheduler.runGlobal(this, () -> result.complete(ReloadPreparation.readyToUnload()), 2L);
        return result;
    }

    @Override
    public CompletionStage<Void> cancelReload() {
        cancels++;
        draining = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Void> commitReload(PreUnloadReason reason) {
        commits++;
        CompletableFuture<Void> result = new CompletableFuture<>();
        FoliaScheduler.runGlobal(this, () -> {
            if (Files.exists(getDataFolder().toPath().resolve("fail-commit"))) {
                result.completeExceptionally(new IllegalStateException("FIXTURE deliberate drain failure"));
            } else {
                result.complete(null);
            }
        }, 2L);
        return result;
    }

    private String mode() {
        Properties properties = new Properties();
        try (InputStream input = getResource("fixture.properties")) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read fixture mode", exception);
        }
        return properties.getProperty("mode", "good");
    }

    private String status() {
        int live;
        List<WeakReference<ClassLoader>> loaders = loaders();
        synchronized (loaders) {
            live = 0;
            for (WeakReference<ClassLoader> reference : loaders) {
                if (reference.get() != null) {
                    live++;
                }
            }
        }
        return "FIXTURE name=" + getName() + " version=" + getDescription().getVersion()
                + " load=" + loads + " enable=" + enables + " prepare=" + prepares
                + " commit=" + commits + " cancel=" + cancels + " draining=" + draining
                + " liveLoaders=" + live;
    }

    private void rememberLoader() {
        List<WeakReference<ClassLoader>> loaders = loaders();
        synchronized (loaders) {
            loaders.add(new WeakReference<>(getClass().getClassLoader()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<WeakReference<ClassLoader>> loaders() {
        String key = "biletools.fixture.loaders." + getName();
        Properties properties = System.getProperties();
        synchronized (properties) {
            return (List<WeakReference<ClassLoader>>) properties.computeIfAbsent(key,
                    ignored -> new ArrayList<WeakReference<ClassLoader>>());
        }
    }
}
