package com.volmit.bile;

import org.junit.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class RuntimeLibraryBootstrapTest {
    @Test
    public void pluginInitializesBeforeRuntimeLibrariesAreAvailable() throws Exception {
        URL artifact = Path.of(System.getProperty("biletools.runtimeArtifact")).toUri().toURL();
        try (BootstrapClassLoader classLoader = new BootstrapClassLoader(artifact)) {
            Class<?> pluginClass = Class.forName("com.volmit.bile.BileTools", true, classLoader);

            assertNotNull(pluginClass.getConstructor());
            assertNotNull(Class.forName("com.volmit.bile.libs.slimjar.app.builder.SpigotApplicationBuilder",
                    true, classLoader));
            assertThrows(ClassNotFoundException.class,
                    () -> classLoader.loadClass("com.volmit.bile.libs.gson.Gson"));
            assertThrows(ClassNotFoundException.class,
                    () -> classLoader.loadClass("com.volmit.bile.libs.toml.Toml"));
            assertThrows(ClassNotFoundException.class,
                    () -> classLoader.loadClass("com.volmit.bile.libs.kyori.adventure.text.Component"));
        }
    }

    private static final class BootstrapClassLoader extends URLClassLoader {
        private BootstrapClassLoader(URL artifact) {
            super(new URL[]{artifact}, RuntimeLibraryBootstrapTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith("com.volmit.bile.") && !name.startsWith("art.arcane.volmlib.")) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
