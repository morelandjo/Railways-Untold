package com.vodmordia.railwaysuntold.worldgen.placement.constraint;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Applies avoidance constraints for obstacles during track placement.
 *
 */
public final class AvoidanceConstraintService {

    private static final List<AvoidanceStrategy> STRATEGIES = List.of(
            new StationAvoidanceStrategy()
    );

    private static final List<AvoidanceStrategy> SORTED_STRATEGIES =
            STRATEGIES.stream()
                    .sorted(Comparator.comparingInt(AvoidanceStrategy::getPriority))
                    .toList();

    private AvoidanceConstraintService() {
    }

    /**
     * Applies avoidance logic for all obstacle types.
     *
     * @param head       The expansion head
     * @param decision   The current decision
     * @param currentPos Current position
     * @param expandDir  Direction of expansion
     * @param scan       Terrain scan
     * @param level      Server level
     * @return Modified decision with avoidance curve, or original if no avoidance needed
     */
    public static PlacementDecision apply(
            TrackExpansionHead head, PlacementDecision decision, BlockPos currentPos,
            Direction expandDir, TerrainScanner.TerrainScan scan, ServerLevel level) {

        if (decision.getType() == PlacementDecision.Type.DEFER) {
            return decision;
        }

        if (!RailwaysUntoldConfig.isAllAvoidanceEnabled()) {
            return decision;
        }

        AvoidanceStrategy.AvoidanceContext context = AvoidanceStrategy.AvoidanceContext.create(
                head, currentPos, expandDir, scan, level);

        for (AvoidanceStrategy strategy : SORTED_STRATEGIES) {
            Optional<PlacementDecision> result = strategy.apply(context);
            if (result.isPresent()) {
                return result.get();
            }
        }

        return decision;
    }

}
