package com.volmit.bile.fixture;

import com.volmit.bile.fixture.library.FixtureLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class FixtureState {
    private static final Properties SETTINGS = readSettings();
    static int bootstraps;
    static int loads;
    static int enables;
    static int disables;
    static int bootstrapRegistrations;
    static int pluginRegistrations;

    private FixtureState() {
    }

    static String mode() {
        return SETTINGS.getProperty("mode");
    }

    static String status() {
        return "NATIVE_FIXTURE version=" + SETTINGS.getProperty("version")
                + " mode=" + mode() + " bootstrap=" + bootstraps + " load=" + loads
                + " enable=" + enables + " disable=" + disables
                + " bootstrapCommands=" + bootstrapRegistrations + " pluginCommands=" + pluginRegistrations
                + " library=" + FixtureLibrary.status() + " liveLoaders=" + liveLoaders()
                + " liveVersions=" + liveVersions();
    }

    @SuppressWarnings("unchecked")
    static void track(ClassLoader loader) {
        synchronized (System.getProperties()) {
            List<WeakReference<ClassLoader>> references = (List<WeakReference<ClassLoader>>) System.getProperties()
                    .computeIfAbsent("biletools.native.fixture.loaders", key -> new ArrayList<WeakReference<ClassLoader>>());
            references.add(new WeakReference<>(loader));
        }
    }

    private static int liveLoaders() {
        synchronized (System.getProperties()) {
            Object stored = System.getProperties().get("biletools.native.fixture.loaders");
            if (!(stored instanceof List<?> references)) {
                return 0;
            }
            int count = 0;
            for (Object entry : references) {
                if (entry instanceof WeakReference<?> reference && reference.get() != null) {
                    count++;
                }
            }
            return count;
        }
    }

    private static String liveVersions() {
        List<String> versions = new ArrayList<>();
        synchronized (System.getProperties()) {
            Object stored = System.getProperties().get("biletools.native.fixture.loaders");
            if (!(stored instanceof List<?> references)) {
                return "none";
            }
            for (Object entry : references) {
                if (!(entry instanceof WeakReference<?> reference)) {
                    continue;
                }
                Object loader = reference.get();
                if (loader == null) {
                    continue;
                }
                try {
                    Object metadata = loader.getClass().getMethod("getConfiguration").invoke(loader);
                    versions.add(String.valueOf(metadata.getClass().getMethod("getVersion").invoke(metadata)));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Could not inspect retained fixture classloader", exception);
                }
            }
        }
        return String.join(",", versions);
    }

    private static Properties readSettings() {
        Properties properties = new Properties();
        try (InputStream stream = FixtureState.class.getResourceAsStream("/fixture.properties")) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture.properties");
            }
            properties.load(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read fixture settings", exception);
        }
        return properties;
    }
}
