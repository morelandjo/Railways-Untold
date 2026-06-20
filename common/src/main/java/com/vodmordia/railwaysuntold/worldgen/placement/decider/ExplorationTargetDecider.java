package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.state.VillageTargetingState;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.planner.PathPlanner;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;


/**
 * Handles track placement decisions for heads exploring without a village target.
 */
public final class ExplorationTargetDecider {

    /**
     * When forward distance to exploration target drops below this, pick a new one.
     */
    private static final int EXPLORATION_TARGET_FORWARD_THRESHOLD = 200;
    private static final int NEW_EXPLORATION_TARGET_DISTANCE = 800;

    /** Salt for deriving a deterministic exploration-target RNG from position + world seed. */
    private static final long SALT_EXPLORATION_TARGET = 7101L;

    private ExplorationTargetDecider() {
    }

    /**
     * Attempts to handle track placement for a head exploring without a village target.
     * Manages exploration target lifecycle and steers the head toward the current target.
     *
     * @param head               The expansion head (must be in exploration mode)
     * @param start              Current position
     * @param direction          Current heading
     * @param scan               Terrain scan data
     * @param level              The server level
     * @param availableLookahead Maximum segment length based on loaded chunks
     * @return PlacementDecision if steering is needed, empty to let terrain planning handle it
     */
    public static Optional<PlacementDecision> tryApproach(
            TrackExpansionHead head,
            BlockPos start,
            Direction direction,
            ServerLevel level) {

        // Don't regenerate exploration targets - or trigger replans of any kind -
        // while the head is in diagonal mode.
        if (head.isDiagonal()) {
            return Optional.empty();
        }

        VillageTargetingState state = head.getVillageState();
        BlockPos explorationTarget = state.getExplorationTarget();
        if (explorationTarget == null) {
            return Optional.empty();
        }

        // Use forward distance along the initial direction, not current heading.
        Direction initialDirection = head.getInitialDirection();
        int forwardDist = PathPlanner.calculateForwardDistance(start, initialDirection, explorationTarget);

        if (forwardDist < EXPLORATION_TARGET_FORWARD_THRESHOLD) {
            // Generate new target using the initial direction so exploration targets
            // always stay in the head's original hemisphere.
            BlockPos newTarget = DirectionUtil.generateExplorationTarget(
                start, initialDirection, NEW_EXPLORATION_TARGET_DISTANCE,
                PositionRandom.createWithSalt(start, level.getSeed(), SALT_EXPLORATION_TARGET));
            state.setExplorationTarget(newTarget);
            CoarseRouteFactory.createAndAttach(level, head, newTarget);
            // Let terrain planning handle this tick while the new route is set up
            return Optional.empty();
        }

        // Check if the head has been circling (360+ degrees of cumulative turns).
        // If so, regenerate the exploration target to break the loop.
        if (state.isCircling()) {
            Direction initialDir = head.getInitialDirection();
            BlockPos newTarget = DirectionUtil.generateExplorationTarget(
                start, initialDir, NEW_EXPLORATION_TARGET_DISTANCE,
                PositionRandom.createWithSalt(start, level.getSeed(), SALT_EXPLORATION_TARGET));
            state.setExplorationTarget(newTarget);
            CoarseRouteFactory.createAndAttach(level, head, newTarget);
            head.clearAllPaths();
            return Optional.empty();
        }

        // Let the coarse route + terrain planner handle steering toward the exploration target.
        // The coarse route already targets this position and the terrain planner executes it.
        return Optional.empty();
    }
}
