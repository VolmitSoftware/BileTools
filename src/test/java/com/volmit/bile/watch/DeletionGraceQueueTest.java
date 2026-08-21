package com.volmit.bile.watch;

import org.junit.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeletionGraceQueueTest {
    @Test
    public void recreationCancelsTombstoneBeforeThreeSeconds() {
        DeletionGraceQueue queue = new DeletionGraceQueue(3_000L);
        Path path = Path.of("Demo.jar");
        queue.schedule(path, "Demo", 4L, 100L);

        assertTrue(queue.expire(3_099L).isEmpty());
        queue.cancel(path);
        assertTrue(queue.expire(10_000L).isEmpty());
        assertTrue(queue.isEmpty());
    }

    @Test
    public void expiresAtThreeSecondDeadline() {
        DeletionGraceQueue queue = new DeletionGraceQueue(3_000L);
        Path path = Path.of("Demo.jar");
        queue.schedule(path, "Demo", 9L, 100L);

        List<DeletionGraceQueue.Tombstone> expired = queue.expire(3_100L);

        assertEquals(1, expired.size());
        assertEquals("Demo", expired.get(0).pluginName());
        assertEquals(9L, expired.get(0).generation());
    }

    @Test
    public void recreationUnderANewPathCancelsMatchingPluginTombstone() {
        DeletionGraceQueue queue = new DeletionGraceQueue(3_000L);
        Path oldPath = Path.of("Demo-old.jar");
        Path newPath = Path.of("Demo-new.jar");
        queue.schedule(oldPath, "Demo", 2L, 0L);

        List<DeletionGraceQueue.Tombstone> canceled = queue.cancelPlugin("demo", newPath);

        assertEquals(1, canceled.size());
        assertEquals(oldPath.toAbsolutePath().normalize(), canceled.get(0).path());
        assertTrue(queue.isEmpty());
    }
}
