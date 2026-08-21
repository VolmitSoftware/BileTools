package com.volmit.bile.watch;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

public final class AutomaticReloadCompletionHandoff implements AutoCloseable {
    private final Path marker;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private AutomaticReloadCompletionHandoff(Path marker, FileChannel channel, FileLock lock) {
        this.marker = marker;
        this.channel = channel;
        this.lock = lock;
    }

    public static AutomaticReloadCompletionHandoff begin(Path marker) throws IOException {
        Path normalizedMarker = normalize(marker);
        Path parent = normalizedMarker.getParent();
        if (parent == null) {
            throw new IOException("Automatic reload completion marker has no parent: " + normalizedMarker);
        }
        FileChannel channel;
        try {
            Files.createDirectories(parent);
            channel = FileChannel.open(
                    normalizedMarker, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (SecurityException exception) {
            throw new IOException("Cannot create the automatic reload completion marker", exception);
        }
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                throw new IOException("An automatic reload completion handoff is already active", exception);
            }
            if (lock == null) {
                throw new IOException("Could not lock the automatic reload completion marker");
            }
            return new AutomaticReloadCompletionHandoff(normalizedMarker, channel, lock);
        } catch (IOException exception) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public static boolean completionObserved(Path marker) throws IOException {
        Path normalizedMarker = normalize(marker);
        FileChannel observedChannel;
        try {
            observedChannel = FileChannel.open(normalizedMarker, StandardOpenOption.WRITE);
        } catch (NoSuchFileException exception) {
            return true;
        } catch (SecurityException exception) {
            throw new IOException("Cannot inspect the automatic reload completion marker", exception);
        }

        try (FileChannel channel = observedChannel) {
            FileLock observedLock;
            try {
                observedLock = channel.tryLock();
            } catch (OverlappingFileLockException exception) {
                return false;
            }
            if (observedLock == null) {
                return false;
            }
            observedLock.release();
        }
        return true;
    }

    public static void deleteMarker(Path marker) throws IOException {
        try {
            Files.deleteIfExists(normalize(marker));
        } catch (SecurityException exception) {
            throw new IOException("Cannot delete the automatic reload completion marker", exception);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            lock.release();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        try {
            deleteMarker(marker);
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Path normalize(Path marker) {
        return Objects.requireNonNull(marker, "marker").toAbsolutePath().normalize();
    }
}
