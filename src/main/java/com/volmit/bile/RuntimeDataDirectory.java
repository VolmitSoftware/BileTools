package com.volmit.bile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class RuntimeDataDirectory {
    private RuntimeDataDirectory() {
    }

    static void prepare(Path runtimeDirectory, Path pluginsDirectory, String pluginName) throws IOException {
        Path runtime = runtimeDirectory.toAbsolutePath().normalize();
        Path plugins = pluginsDirectory.toAbsolutePath().normalize();
        Path link = runtime.resolve(pluginName).normalize();
        Path target = plugins.resolve(pluginName).normalize();
        if (!runtime.equals(link.getParent()) || !plugins.equals(target.getParent())) {
            throw new IOException("Invalid plugin data directory for " + pluginName);
        }
        Files.createDirectories(runtime);
        Files.createDirectories(target);
        if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isSymbolicLink(link) || !Files.isSameFile(link, target)) {
                throw new IOException("Runtime data directory does not point to " + target);
            }
            return;
        }
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | SecurityException failure) {
            throw new IOException("Cannot preserve the data directory for " + pluginName, failure);
        }
    }
}
