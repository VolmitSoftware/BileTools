package com.volmit.bile;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class RuntimeArtifactLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private RuntimeArtifactLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    static RuntimeArtifactLease acquire(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new RuntimeArtifactLease(channel, channel.lock(0L, Long.MAX_VALUE, true));
        } catch (IOException | RuntimeException exception) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    static boolean isActive(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            try (FileLock lock = channel.tryLock()) {
                return lock == null;
            } catch (OverlappingFileLockException exception) {
                return true;
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } finally {
            channel.close();
        }
    }
}
