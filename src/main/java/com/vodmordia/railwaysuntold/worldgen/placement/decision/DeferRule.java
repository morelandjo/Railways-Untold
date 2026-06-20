package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.constraint.AvoidanceConstraintService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Final rule in the pipeline that creates a defer decision when no other rule handles the situation.
 * Applies village boundary and avoidance constraints.
 */
public class DeferRule implements PlacementRule {

    @Override
    public Optional<PlacementDecision> decide(DeciderContext context) {
        // Check if chunks in lookahead are actually unloaded
        Set<ChunkPos> unloadedChunks = findUnloadedChunksInLookahead(context);

        PlacementDecision decision;
        if (unloadedChunks.isEmpty()) {
            // All chunks are loaded but no rule produced a decision - defer and
            // let the orchestrator's defer-count logic halt the head after the
            // configured threshold if the stall persists.
            decision = PlacementDecision.defer();
        } else {
            decision = PlacementDecision.deferForChunks(unloadedChunks);
        }

        decision = AvoidanceConstraintService.apply(
                context.head(), decision, context.start(),
                context.direction(), context.scan(), context.level());

        return Optional.of(decision);
    }

    private Set<ChunkPos> findUnloadedChunksInLookahead(DeciderContext context) {
        BlockPos start = context.start();
        Direction dir = context.direction();
        int lookahead = PlacementConstants.REQUIRED_LOOKAHEAD;
        BlockPos end = start.relative(dir, lookahead);

        Set<ChunkPos> chunks = ChunkCoordinateUtil.getChunksInBoundingBox(start, end);
        return chunks.stream()
                .filter(chunk -> ChunkCoordinateUtil.getLoadedChunk(context.level(), chunk) == null)
                .collect(Collectors.toSet());
    }

    @Override
    public String getName() {
        return "Defer";
    }

    @Override
    public int getPriority() {
        return Integer.MAX_VALUE;
    }
}
