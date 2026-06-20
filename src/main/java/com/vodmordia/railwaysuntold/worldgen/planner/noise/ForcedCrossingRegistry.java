package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot per-head directive forcing the next placed-track crossing detection to ramp a specific
 * collision over or under the blocking line instead of leaving it at-grade to merge.
 *
 * The at-grade rule is: try to merge into the blocking line first; only ramp when that merge is
 * impossible. Whether a merge can be formed is known at placement time, not plan time - a foreign
 * line met at a 45 degree diagonal cannot be joined by a tangent junction. When the orchestrator's
 * runtime merge attempt fails, it records the collision here, and the next route registration honors
 * it: the crossing nearest the recorded point ramps (under first, else over) rather than running
 * straight into the line again.
 *
 * Written on the server thread (orchestrator) and read on the route-planner thread
 * ({@link PlacedTrackConflictDetector}), so the backing map is concurrent.
 */
public final class ForcedCrossingRegistry {

    /** A pending forced crossing: ramp the crossing nearest {@code point}, preferring under when set. */
    public record ForcedCrossing(BlockPos point, boolean preferUnder) {}

    private static final Map<UUID, ForcedCrossing> PENDING = new ConcurrentHashMap<>();

    /** Heads that have already had a forced crossing attempted this stuck-episode. Forcing is a
     *  last resort; a head whose forced ramp also fails must retire rather than re-force forever. */
    private static final Set<UUID> ATTEMPTED = ConcurrentHashMap.newKeySet();

    private ForcedCrossingRegistry() {}

    /** Records that the head's next crossing detected near {@code collision} must ramp (under-first when
     *  set), and marks the head as having attempted a forced crossing this episode. */
    public static void force(UUID headId, BlockPos collision, boolean preferUnder) {
        PENDING.put(headId, new ForcedCrossing(collision, preferUnder));
        ATTEMPTED.add(headId);
    }

    /** Returns and removes the head's pending forced crossing, or null if none is pending. */
    public static ForcedCrossing consume(UUID headId) {
        return PENDING.remove(headId);
    }

    /** True if a forced crossing has already been attempted for the head since its last progress/clear. */
    public static boolean hasAttempted(UUID headId) {
        return ATTEMPTED.contains(headId);
    }

    /** Drops the head's pending directive and attempt record (on real progress, completion, or retire). */
    public static void clear(UUID headId) {
        PENDING.remove(headId);
        ATTEMPTED.remove(headId);
    }

    /** Drops all pending directives and attempt records. Registered with SystemStateManager to clear on world transitions. */
    public static void clearAll() {
        PENDING.clear();
        ATTEMPTED.clear();
    }
}
