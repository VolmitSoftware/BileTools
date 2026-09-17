package com.volmit.bile.velocity;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class HotloadException extends Exception {
    private final Kind kind;
    private final Set<Path> dependentSources;

    public HotloadException(Kind kind, String message) {
        this(kind, message, null, Set.of());
    }

    public HotloadException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, Set.of());
    }

    HotloadException(Kind kind, String message, Throwable cause, Set<Path> dependentSources) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.dependentSources = Set.copyOf(Objects.requireNonNull(dependentSources, "dependentSources"));
    }

    public Kind kind() {
        return kind;
    }

    Set<Path> dependentSources() {
        return dependentSources;
    }

    public enum Kind {
        UNSUPPORTED_CAPABILITY,
        INVALID_DESCRIPTOR,
        MISSING_DEPENDENCY,
        ALREADY_LOADED,
        NOT_LOADED,
        SELF,
        LOAD_FAILED,
        UNLOAD_FAILED,
        HEALTH_FAILED,
        TIMEOUT
    }
}
