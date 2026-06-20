package com.vodmordia.railwaysuntold.worldgen.head;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;

/**
 * Re-derives a village (re)assignment choice from a captured {@link VillageAssignRecord}, with no world -
 * the offline counterpart of {@code CoarseRoutePlanner.planRouteOffThread} for the village-targeting
 * subsystem. It recomputes the POSE-based filters ({@link DirectionUtil#isNotBehind}, the
 * backtrack-toward-spawn rule) from the captured head pose so that a change to those filters (e.g. a guard
 * that suppresses assignment while a head is swung off its axis by a detour) shows up here and in the
 * golden - while the tracker-dependent flags (already-assigned / already-attempted), which it cannot
 * recompute, are taken from the record as data.
 *
 * Selection mirrors {@code VillageHeadAssigner.searchAndAssignVillage} for the two sources a record
 * captures: predicted villages are preferred over located ones, nearest first within a source. The
 * planner-edge source is not captured, so a choice that came from a planner edge replays as that recorded
 * center directly (it is still the head's committed target) rather than being re-derived.
 */
public final class VillageAssignmentReplay {

    /** Same constant the runtime backtrack filter uses: reject a village closer to spawn than this fraction
     *  of the head's own distance from spawn. */
    private static final double BACKTRACK_FRACTION = 0.8;

    private VillageAssignmentReplay() {}

    /**
     * The village the selection would commit to for this record, or null if none is eligible - recomputing
     * the pose filters from the captured pose. A null result where the record's {@code chosen} is non-null
     * means a pose filter (or a guard) now rejects the choice the runtime made: exactly the signal a fix
     * for an off-course mis-assignment should produce.
     */
    @Nullable
    public static BlockPos choose(VillageAssignRecord rec) {
        VillageAssignRecord.Candidate best = null;
        for (VillageAssignRecord.Candidate c : rec.candidates) {
            if (!isEligible(rec, c)) continue;
            if (best == null || rank(rec, c) < rank(rec, best)) {
                best = c;
            }
        }
        return best == null ? null : best.center();
    }

    /** True if a candidate passes every gate the selection applies, recomputing the pose filters. The
     *  not-behind cone keys off the head's INITIAL direction, matching the runtime
     *  {@code searchAndAssignVillage} (which uses {@code head.getInitialDirection()}). */
    public static boolean isEligible(VillageAssignRecord rec, VillageAssignRecord.Candidate c) {
        if (c.assigned() || c.attempted()) {
            return false;
        }
        if (!DirectionUtil.isNotBehind(rec.pos, c.center(), rec.initDir)) {
            return false;
        }
        return !isBacktracking(rec, c.center());
    }

    /** The backtrack-toward-spawn rule from {@code VillageHeadAssigner.isBacktrackingToSpawn}, recomputed. */
    public static boolean isBacktracking(VillageAssignRecord rec, BlockPos center) {
        double villageDist = DirectionUtil.horizontalDistance(center, rec.spawn);
        return villageDist < rec.headDistFromSpawn * BACKTRACK_FRACTION;
    }

    /** Lower rank wins: predicted before located, then nearest. Encodes the source priority + nearest pick. */
    private static long rank(VillageAssignRecord rec, VillageAssignRecord.Candidate c) {
        long sourceBias = c.source() == VillageAssignRecord.Source.PREDICTED ? 0L : 1L << 60;
        long dx = (long) c.center().getX() - rec.pos.getX();
        long dz = (long) c.center().getZ() - rec.pos.getZ();
        return sourceBias + dx * dx + dz * dz;
    }
}
