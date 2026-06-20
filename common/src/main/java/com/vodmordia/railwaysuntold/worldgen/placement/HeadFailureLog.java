package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.state.VillageTargetingState;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import com.vodmordia.railwaysuntold.worldgen.village.StationPlan;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

/**
 * One greppable record per genuine head failure - a head that gives up or cannot proceed to its target.
 * Deliberately NOT emitted for the benign terminals: a head waiting on chunks (deferral) or a head finishing
 * via an intended parallel merge/converge. Every failure also drops the head's stored {@code [REPLAY]} record
 * so the failure reproduces with no world. Grep {@code [HEAD-FAIL]} for the list of heads that did not make it.
 */
public final class HeadFailureLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Kind {
        /** The decision engine could not place a next segment (e.g. an impossible descent). */
        CANNOT_PLACE,
        /** Blocked head-on by existing track; terminated into it as a junction instead of crossing. */
        CROSSING_JUNCTION,
        /** Stuck after repeated no-progress failures; terminated into a nearby line as a junction. */
        GIVEUP_JUNCTION,
        /** Stuck after repeated no-progress failures with no line to merge into; retired as a dead stub. */
        RETIRED_STUB
    }

    private HeadFailureLog() {}

    public static void report(TrackExpansionHead head, Kind kind, String detail) {
        BlockPos p = head.getPosition();
        BlockPos target = resolveTarget(head.getVillageState());
        LOGGER.warn("[HEAD-FAIL] head={} pos={},{},{} dir={} kind={} target={} detail={}",
                head.getHeadNumber(), p.getX(), p.getY(), p.getZ(), head.getDirection(), kind,
                target == null ? "none" : target.getX() + "," + target.getY() + "," + target.getZ(), detail);
        CoarseRouteFactory.emitReplayOnAnomaly(head.getHeadId(), head.getHeadNumber(), "HEAD-FAIL:" + kind);
    }

    /** The head's intended destination, in the same precedence the recovery path uses. */
    private static BlockPos resolveTarget(VillageTargetingState vs) {
        StationPlan locked = vs.getLockedStationPlan();
        if (locked != null) {
            return locked.arrivalPos();
        }
        if (vs.getApproachWaypoint() != null) {
            return vs.getApproachWaypoint();
        }
        if (vs.hasTargetVillage()) {
            return vs.getTargetVillageCenter();
        }
        return vs.getExplorationTarget();
    }
}
