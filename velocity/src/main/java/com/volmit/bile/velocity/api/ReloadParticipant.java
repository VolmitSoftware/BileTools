package com.volmit.bile.velocity.api;

import com.volmit.bile.velocity.UnloadReason;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public interface ReloadParticipant {
    CompletionStage<ReloadPreparation> prepareReload(UnloadReason reason);

    CompletionStage<Void> cancelReload();

    default CompletionStage<Void> commitReload(UnloadReason reason) {
        return CompletableFuture.completedFuture(null);
    }
}
