package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks per-head replan-failure fingerprints so the orchestrator can escalate when the
 * planner is deterministically producing the same bad route, and ultimately retire a head
 * that cannot make any progress.
 */
public final class ReplanEscalator {

    public static final int ESCALATE_THRESHOLD = 3;
    /**
     * Hard cap on consecutive failures at one position (regardless of how the target is re-chosen)
     * before the head is retired. Escalation re-explores toward a fresh target, but if the head still
     * cannot place a single segment from where it is standing, re-exploring only changes the target,
     * not the geometry - so without this cap a boxed-in head replans forever.
     */
    public static final int GIVE_UP_THRESHOLD = 12;

    private record FailureFingerprint(BlockPos pos, BlockPos target, int count, int posFailures) {}

    /**
     * Outcome of a replan attempt. {@code targetCount} is the run of identical (pos, target) failures
     * (drives escalation at {@link #ESCALATE_THRESHOLD}); {@code posCount} is the run of failures at the
     * same position regardless of target (drives give-up at {@link #GIVE_UP_THRESHOLD}). Both are logged
     * on every replan so a churning head shows up as a climbing counter.
     */
    public record EscalationCheck(boolean shouldEscalate, boolean shouldGiveUp, int targetCount, int posCount) {}

    private final Map<UUID, FailureFingerprint> failures = new HashMap<>();

    /**
     * Records a replan attempt against (pos, target) for headId. Returns shouldGiveUp when the head
     * has failed {@link #GIVE_UP_THRESHOLD} times at the same position without moving (no progress),
     * else shouldEscalate when this is the Nth identical (pos, target) attempt in a row. The position
     * tally accrues across escalations so re-exploring to new targets at the same spot still converges
     * on give-up; both counts reset once the head's position changes (real progress) or on {@link #clear}.
     */
    public EscalationCheck recordAndCheck(UUID headId, BlockPos pos, BlockPos target) {
        FailureFingerprint prev = failures.get(headId);
        boolean samePos = prev != null && prev.pos().equals(pos);
        boolean sameTarget = samePos && prev.target().equals(target);

        int count = sameTarget ? prev.count() + 1 : 1;
        int posFailures = samePos ? prev.posFailures() + 1 : 1;

        if (posFailures >= GIVE_UP_THRESHOLD) {
            failures.remove(headId);
            return new EscalationCheck(false, true, count, posFailures);
        }
        if (count >= ESCALATE_THRESHOLD) {
            // Re-explore (tier 1): reset the per-target count, but keep the position tally so the
            // give-up cap still accrues across escalations.
            failures.put(headId, new FailureFingerprint(pos, target, 0, posFailures));
            return new EscalationCheck(true, false, count, posFailures);
        }
        failures.put(headId, new FailureFingerprint(pos, target, count, posFailures));
        return new EscalationCheck(false, false, count, posFailures);
    }

    /** Forgets the head's failure history (on a successful placement, or when the caller has no target to replan toward). */
    public void clear(UUID headId) {
        failures.remove(headId);
    }
}
