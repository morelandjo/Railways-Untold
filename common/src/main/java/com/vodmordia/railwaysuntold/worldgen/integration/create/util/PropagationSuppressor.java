package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Controls suppression of Create's TrackPropagator.onRailAdded() during batch track placement.
 */
public final class PropagationSuppressor {

    private static final Set<Long> suppressedPositions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final ThreadLocal<Boolean> batchActive = ThreadLocal.withInitial(() -> false);

    private PropagationSuppressor() {}

    /**
     * Begins batch suppression. All onRailAdded calls will be suppressed until endBatch().
     * Must be paired with endBatch() in a finally block.
     */
    public static void beginBatch() {
        batchActive.set(true);
    }

    /**
     * Ends batch suppression. Must be called in a finally block after beginBatch().
     */
    public static void endBatch() {
        batchActive.set(false);
    }

    /**
     * Returns true if batch suppression is active on the current thread.
     */
    public static boolean isSuppressed() {
        return batchActive.get();
    }

    public static void clear() {
        suppressedPositions.clear();
        batchActive.remove();
    }
}
