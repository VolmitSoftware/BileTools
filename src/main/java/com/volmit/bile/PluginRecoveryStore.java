package com.volmit.bile;

import com.volmit.bile.watch.JarSnapshotStager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

public final class PluginRecoveryStore {
    private final Path directory;

    public PluginRecoveryStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public synchronized boolean contains(String pluginName) {
        return Files.isRegularFile(activePath(pluginName));
    }

    public synchronized void remember(String pluginName, Path source) throws IOException {
        JarSnapshotStager.StagedJar snapshot = JarSnapshotStager.stage(source, directory, 0L,
                JarSnapshotStager.BUKKIT_DESCRIPTOR_ENTRIES);
        try {
            Path destination = activePath(pluginName);
            try {
                Files.move(snapshot.staged(), destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(snapshot.staged(), destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            snapshot.delete();
        }
    }

    public synchronized JarSnapshotStager.StagedJar retain(String pluginName) throws IOException {
        Path source = activePath(pluginName);
        if (!Files.isRegularFile(source)) {
            throw new IOException("No running-version recovery jar is available for " + pluginName);
        }
        return JarSnapshotStager.stage(source, directory.resolve("transactions"), 0L,
                JarSnapshotStager.BUKKIT_DESCRIPTOR_ENTRIES);
    }

    public synchronized void forget(String pluginName) throws IOException {
        Files.deleteIfExists(activePath(pluginName));
    }

    private Path activePath(String pluginName) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                pluginName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return directory.resolve(encoded + ".jar");
    }
}
