package com.vodmordia.railwaysuntold.worldgen.placement;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks heads that are currently steering toward a parallel line to merge into it (long-range
 * convergence). While a head is converging, the per-step convergence scan is skipped so the head
 * follows its converge target instead of re-detecting and re-planning every step; the flag clears when
 * the head completes the merge, retires, or is otherwise done.
 */
public final class ParallelConvergeRegistry {

    private static final Set<UUID> CONVERGING = ConcurrentHashMap.newKeySet();

    private ParallelConvergeRegistry() {}

    /** True if the head is already steering toward a parallel line. */
    public static boolean isConverging(UUID headId) {
        return CONVERGING.contains(headId);
    }

    /** Marks the head as steering toward a parallel line. */
    public static void mark(UUID headId) {
        CONVERGING.add(headId);
    }

    /** Clears the head's converging state (on merge completion, retire, or completion). */
    public static void clear(UUID headId) {
        CONVERGING.remove(headId);
    }

    /** Drops all converging state. Registered with SystemStateManager to clear on world transitions. */
    public static void clearAll() {
        CONVERGING.clear();
    }
}
