package com.volmit.bile.velocity.api;

import java.util.Objects;

public record ReloadPreparation(boolean ready, String reason) {
    public ReloadPreparation {
        Objects.requireNonNull(reason, "reason");
    }

    public static ReloadPreparation readyToUnload() {
        return new ReloadPreparation(true, "");
    }

    public static ReloadPreparation refuse(String reason) {
        return new ReloadPreparation(false, reason);
    }
}
