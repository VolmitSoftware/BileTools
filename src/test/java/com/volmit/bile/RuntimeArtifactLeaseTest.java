package com.volmit.bile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeArtifactLeaseTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void protectsPreparedArtifactUntilReleasedAndAllowsRepeatedClose() throws Exception {
        Path artifact = directory.newFile("prepared.jar").toPath();
        RuntimeArtifactLease lease = RuntimeArtifactLease.acquire(artifact);
        try {
            assertTrue(RuntimeArtifactLease.isActive(artifact));
            assertTrue(Files.exists(artifact));
        } finally {
            lease.close();
        }
        lease.close();
        assertFalse(RuntimeArtifactLease.isActive(artifact));
        Files.delete(artifact);
        assertFalse(Files.exists(artifact));
    }

    @Test
    public void detectsLeaseOwnedByAnOutgoingPluginClassloader() throws Exception {
        Path artifact = directory.newFile("dependent.jar").toPath();
        URL source = RuntimeArtifactLease.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader outgoing = new URLClassLoader(new URL[]{source}, null)) {
            Class<?> leaseType = outgoing.loadClass(RuntimeArtifactLease.class.getName());
            Method acquire = leaseType.getDeclaredMethod("acquire", Path.class);
            acquire.setAccessible(true);
            try (AutoCloseable lease = (AutoCloseable) acquire.invoke(null, artifact)) {
                assertTrue(RuntimeArtifactLease.isActive(artifact));
            }
            assertFalse(RuntimeArtifactLease.isActive(artifact));
        }
    }

    @Test
    public void doesNotMarkUnrelatedArtifactsActive() throws Exception {
        Path prepared = directory.newFile("prepared.jar").toPath();
        Path stale = directory.newFile("stale.jar").toPath();
        try (RuntimeArtifactLease lease = RuntimeArtifactLease.acquire(prepared)) {
            assertTrue(RuntimeArtifactLease.isActive(prepared));
            assertFalse(RuntimeArtifactLease.isActive(stale));
        }
    }
}
