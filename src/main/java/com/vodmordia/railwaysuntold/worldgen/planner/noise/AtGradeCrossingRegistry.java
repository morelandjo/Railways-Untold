package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One-shot per-head directive permitting the next crossing of a blocking line to be placed THROUGH at
 * grade - the planner leaves the route flat over the crossing and the placement collision check lets the
 * span pass - so Create forms a crossing instead of the head retiring into a dead stub.
 *
 * This is the opposite of {@link ForcedCrossingRegistry}: that forces a ramp over/under; this forces a
 * flat pass-through. It is the last resort when a perpendicular crossing can be neither merged (no tangent
 * junction fits) nor grade-separated (the head has no horizontal room to ramp to the clearance - the
 * common case is a head that has already advanced to within a few blocks of the crossing). Rather than
 * retire, the head crosses at whatever clearance it has.
 *
 * Honoring it on both the route-planner thread ({@link PlacedTrackConflictDetector}) and the server
 * thread (placement collision gate) means the directive must survive planning AND placement, so reads do
 * not consume it; the orchestrator clears it on real progress, completion, or retire. The backing maps
 * are concurrent for the same cross-thread reason as {@link ForcedCrossingRegistry}.
 */
public final class AtGradeCrossingRegistry {

    /** Heads permitted to cross their next blocking line at grade, mapped to the collision point so the
     *  planner skips the ramp for that specific crossing and leaves unrelated crossings resolved. */
    private static final Map<UUID, BlockPos> ALLOWED = new ConcurrentHashMap<>();

    /** Heads that have already had an at-grade crossing attempted this stuck-episode. Like a forced ramp,
     *  it is a last resort: a head whose at-grade pass-through also fails must retire, not re-allow forever. */
    private static final Set<UUID> ATTEMPTED = ConcurrentHashMap.newKeySet();

    private AtGradeCrossingRegistry() {}

    /** Permits the head to cross the line nearest {@code collision} at grade, and marks the head as having
     *  attempted an at-grade crossing this episode. */
    public static void allow(UUID headId, BlockPos collision) {
        ALLOWED.put(headId, collision);
        ATTEMPTED.add(headId);
    }

    /** True if the head may currently place through a crossing at grade. */
    public static boolean isAllowed(UUID headId) {
        return ALLOWED.containsKey(headId);
    }

    /** The collision point the head is permitted to cross at grade, or null if none is pending. Does not
     *  remove the directive - both the planner and the placement gate read it within one replan. */
    public static BlockPos point(UUID headId) {
        return ALLOWED.get(headId);
    }

    /** True if an at-grade crossing has already been attempted for the head since its last progress/clear. */
    public static boolean hasAttempted(UUID headId) {
        return ATTEMPTED.contains(headId);
    }

    /** Drops the head's directive and attempt record (on real progress, completion, or retire). */
    public static void clear(UUID headId) {
        ALLOWED.remove(headId);
        ATTEMPTED.remove(headId);
    }

    /** Drops all directives and attempt records. Registered with SystemStateManager to clear on world transitions. */
    public static void clearAll() {
        ALLOWED.clear();
        ATTEMPTED.clear();
    }
}
