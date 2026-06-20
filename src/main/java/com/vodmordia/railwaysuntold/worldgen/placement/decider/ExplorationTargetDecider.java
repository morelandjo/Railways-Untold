package com.vodmordia.railwaysuntold.worldgen.placement.decider;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.state.VillageTargetingState;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.planner.PathPlanner;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
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
    /**
     * Attempts to handle track placement for a head exploring without a village target.
     * When headManager is provided, new exploration targets will avoid being too close to
     * other heads' positions and exploration targets.
     */
    public static Optional<PlacementDecision> tryApproach(
            TrackExpansionHead head,
            BlockPos start,
            Direction direction,
            ServerLevel level,
            @Nullable ExpansionHeadManager headManager) {

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
            BlockPos newTarget = generateTargetAvoiding(
                start, initialDirection, NEW_EXPLORATION_TARGET_DISTANCE, level.getSeed(), head, headManager);
            state.setExplorationTarget(newTarget);
            CoarseRouteFactory.createAndAttach(level, head, newTarget);
            // Let terrain planning handle this tick while the new route is set up
            return Optional.empty();
        }

        // Check if the head has been circling (360+ degrees of cumulative turns).
        // If so, regenerate the exploration target to break the loop.
        if (state.isCircling()) {
            Direction initialDir = head.getInitialDirection();
            BlockPos newTarget = generateTargetAvoiding(
                start, initialDir, NEW_EXPLORATION_TARGET_DISTANCE, level.getSeed(), head, headManager);
            state.setExplorationTarget(newTarget);
            CoarseRouteFactory.createAndAttach(level, head, newTarget);
            head.clearAllPaths();
            return Optional.empty();
        }

        // Let the coarse route + terrain planner handle steering toward the exploration target.
        // The coarse route already targets this position and the terrain planner executes it.
        return Optional.empty();
    }

    /**
     * Generates an exploration target, avoiding other heads' positions and exploration targets
     * when headManager is available.
     */
    private static BlockPos generateTargetAvoiding(
            BlockPos origin, Direction direction, int distance, long worldSeed,
            TrackExpansionHead currentHead, @Nullable ExpansionHeadManager headManager) {
        if (headManager == null) {
            return DirectionUtil.generateExplorationTarget(origin, direction, distance,
                    PositionRandom.createWithSalt(origin, worldSeed, SALT_EXPLORATION_TARGET));
        }

        List<BlockPos> otherPositions = collectOtherHeadPositions(currentHead, headManager);
        return DirectionUtil.generateExplorationTargetAvoiding(origin, direction, distance,
                PositionRandom.createWithSalt(origin, worldSeed, SALT_EXPLORATION_TARGET), otherPositions);
    }

    /**
     * Collects positions of all other active heads and their exploration targets
     * for use in separation checking.
     */
    public static List<BlockPos> collectOtherHeadPositions(TrackExpansionHead currentHead, ExpansionHeadManager headManager) {
        List<BlockPos> positions = new ArrayList<>();
        for (TrackExpansionHead other : headManager.getActiveHeads()) {
            if (other.getHeadId().equals(currentHead.getHeadId())) {
                continue;
            }
            positions.add(other.getPosition());

            // Also include the other head's exploration target if it has one
            BlockPos otherTarget = other.getVillageState().getExplorationTarget();
            if (otherTarget != null) {
                positions.add(otherTarget);
            }
        }
        return positions;
    }
}
