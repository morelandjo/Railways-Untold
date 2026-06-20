package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.VillageHeadAssigner;
import com.vodmordia.railwaysuntold.worldgen.placement.decision.CoarseRouteExecutionRule;
import com.vodmordia.railwaysuntold.worldgen.placement.decision.DeciderContext;
import com.vodmordia.railwaysuntold.worldgen.placement.decision.DeferRule;
import com.vodmordia.railwaysuntold.worldgen.placement.decision.PlacementPipeline;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.Random;

/**
 * Orchestrates track placement decisions by delegating to a pipeline of specialized rules.
 * Acts as a facade that coordinates the decision-making process.
 */
public class PlacementDecisionEngine {

    private static class PipelineHolder {
        // Events are handled inside CoarseRouteExecutionRule (so they preempt terrain-following). A
        // standalone EventPlacementRule here would only re-roll the event dice a second time in the
        // no-route window, doubling the event rate there - so it is intentionally not registered.
        static final PlacementPipeline INSTANCE = PlacementPipeline.builder()
                .add(new CoarseRouteExecutionRule())
                .add(new DeferRule())
                .build();
    }

    private static PlacementPipeline getPlacementPipeline() {
        return PipelineHolder.INSTANCE;
    }

    public static PlacementDecision decidePlacementFromTerrain(TrackExpansionHead head, BlockPos start,
                                                               Direction direction, TerrainScanner.TerrainScan scan,
                                                               ServerLevel level, RailwaysUntoldConfig config,
                                                               int availableLookahead, Random random,
                                                               com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager headManager) {

        tryPeriodicRetargeting(head, level, config);

        DeciderContext context = new DeciderContext(head, start, direction, scan, level, config, availableLookahead, random, headManager);
        return getPlacementPipeline().execute(context);
    }

    private static void tryPeriodicRetargeting(TrackExpansionHead head, ServerLevel level, RailwaysUntoldConfig config) {
        if (!head.getVillageState().hasTargetVillage()) {
            if (head.getVillageState().isExploring()) {
                VillageAssignmentTracker tracker = VillageTargetingSavedData.get(level).getAssignmentTracker();
                VillageHeadAssigner.tryReassignVillageWhileExploring(head, level, config, tracker);
            } else {
                VillageHeadAssigner.tryRetargetHead(head, level, config);
            }
        }
    }
}
