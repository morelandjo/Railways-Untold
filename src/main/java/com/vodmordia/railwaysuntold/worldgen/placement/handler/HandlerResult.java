package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.Set;

/**
 * Result of a placement handling operation.
 * Indicates success, failure, or deferral.
 */
public record HandlerResult(
        boolean success,
        boolean shouldDefer,
        Set<ChunkPos> deferChunks,
        String deferReason,
        boolean skipContinuation,
        boolean companionHandled
) {
    /**
     * Creates a successful result indicating the placement operation completed.
     *
     * @return A successful HandlerResult
     */
    public static HandlerResult succeeded() {
        return new HandlerResult(true, false, Collections.emptySet(), null, false, false);
    }

    /**
     * Creates a successful result where the handler has already scheduled its own continuation.
     *
     * @return A successful HandlerResult that skips automatic continuation
     */
    public static HandlerResult succeededSkipContinuation() {
        return new HandlerResult(true, false, Collections.emptySet(), null, true, false);
    }

    /**
     * Creates a deferred result indicating the operation should wait for chunks to load.
     *
     * @param chunks The chunks we're waiting for
     * @param reason Description of what operation is being deferred
     * @return A deferred HandlerResult with the specified chunks and reason
     */
    public static HandlerResult defer(Set<ChunkPos> chunks, String reason) {
        return new HandlerResult(false, true, chunks, reason, false, false);
    }

    /**
     * Creates a failed result indicating the placement operation could not complete.
     *
     * @param reason Description of why the operation failed
     * @return A failed HandlerResult with the specified reason
     */
    public static HandlerResult failed(String reason) {
        return new HandlerResult(false, false, Collections.emptySet(), reason, false, false);
    }

}
