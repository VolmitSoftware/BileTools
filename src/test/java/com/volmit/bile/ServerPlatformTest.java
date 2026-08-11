package com.volmit.bile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerPlatformTest {
    @Test
    public void detectPaperRuntime_acceptsEachKnownCapabilityMarker() {
        assertTrue(ServerPlatform.detectPaperRuntime(true, false, false));
        assertTrue(ServerPlatform.detectPaperRuntime(false, true, false));
        assertTrue(ServerPlatform.detectPaperRuntime(false, false, true));
    }

    @Test
    public void detectPaperRuntime_rejectsRuntimeWithoutPaperCapabilities() {
        assertFalse(ServerPlatform.detectPaperRuntime(false, false, false));
    }

    @Test
    public void classify_identifiesSpigotAndPaperBoundary() {
        assertEquals(ServerPlatform.Family.SPIGOT,
                ServerPlatform.classify(false, false, false, false, false));
        assertEquals(ServerPlatform.Family.PAPER,
                ServerPlatform.classify(false, false, false, false, true));
    }

    @Test
    public void classify_prefersPaperForksInSpecificityOrder() {
        assertEquals(ServerPlatform.Family.PURPUR,
                ServerPlatform.classify(false, false, false, true, true));
        assertEquals(ServerPlatform.Family.LEAF,
                ServerPlatform.classify(false, false, true, true, true));
    }

    @Test
    public void classify_prefersRegionizedFamiliesOverPaperForkMarkers() {
        assertEquals(ServerPlatform.Family.FOLIA,
                ServerPlatform.classify(true, false, true, true, true));
        assertEquals(ServerPlatform.Family.CANVAS,
                ServerPlatform.classify(true, true, true, true, true));
    }
}
