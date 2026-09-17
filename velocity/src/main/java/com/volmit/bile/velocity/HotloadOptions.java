package com.volmit.bile.velocity;

import java.time.Duration;
import java.util.Objects;

public record HotloadOptions(boolean healthCheck, boolean logTimings, boolean archivePlugins, Duration lifecycleTimeout) {
    public HotloadOptions {
        Objects.requireNonNull(lifecycleTimeout, "lifecycleTimeout");
    }
}
