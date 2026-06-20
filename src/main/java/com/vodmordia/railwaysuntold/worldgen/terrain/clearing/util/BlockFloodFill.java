package com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;
import java.util.function.Predicate;

/**
 * Generic graph traversal algorithms for blocks - no domain knowledge of trees/leaves/etc.
 */
public class BlockFloodFill {

    public static final BlockPos[] ALL_ADJACENT_DIRECTIONS = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1),
            new BlockPos(1, 1, 0), new BlockPos(1, -1, 0),
            new BlockPos(-1, 1, 0), new BlockPos(-1, -1, 0),
            new BlockPos(1, 0, 1), new BlockPos(1, 0, -1),
            new BlockPos(-1, 0, 1), new BlockPos(-1, 0, -1),
            new BlockPos(0, 1, 1), new BlockPos(0, 1, -1),
            new BlockPos(0, -1, 1), new BlockPos(0, -1, -1),
            new BlockPos(1, 1, 1), new BlockPos(1, 1, -1),
            new BlockPos(1, -1, 1), new BlockPos(1, -1, -1),
            new BlockPos(-1, 1, 1), new BlockPos(-1, 1, -1),
            new BlockPos(-1, -1, 1), new BlockPos(-1, -1, -1)
    };

    /**
     * 6-directional adjacency offsets (not diagonal) - matches vanilla leaf decay propagation.
     */
    static final BlockPos[] CARDINAL_DIRECTIONS = {
            new BlockPos(1, 0, 0), new BlockPos(-1, 0, 0),
            new BlockPos(0, 1, 0), new BlockPos(0, -1, 0),
            new BlockPos(0, 0, 1), new BlockPos(0, 0, -1)
    };

    /**
     * Context for simple (single-matcher) flood fill operations.
     */
    private static class SimpleFloodFillContext {
        final ServerLevel level;
        final Predicate<BlockState> blockMatcher;
        final BlockPos[] directions;
        final Set<BlockPos> visited = new HashSet<>();
        final Queue<BlockPos> toProcess = new LinkedList<>();
        final List<BlockPos> blocksToRemove = new ArrayList<>();

        SimpleFloodFillContext(ServerLevel level, Predicate<BlockState> blockMatcher, BlockPos[] directions) {
            this.level = level;
            this.blockMatcher = blockMatcher;
            this.directions = directions;
        }
    }

    public static List<BlockPos> findConnectedBlocks(
            ServerLevel level,
            BlockPos startPos,
            Predicate<BlockState> blockMatcher,
            int maxBlocks) {
        return findConnectedBlocks(level, startPos, blockMatcher, ALL_ADJACENT_DIRECTIONS, maxBlocks);
    }

    public static List<BlockPos> findConnectedBlocks(
            ServerLevel level,
            BlockPos startPos,
            Predicate<BlockState> blockMatcher,
            BlockPos[] directions,
            int maxBlocks) {

        LevelChunk startChunk = ChunkCoordinateUtil.getLoadedChunk(level, startPos);
        if (startChunk == null) {
            return Collections.emptyList();
        }

        SimpleFloodFillContext ctx = new SimpleFloodFillContext(level, blockMatcher, directions);
        ctx.toProcess.add(startPos);
        ctx.visited.add(startPos);

        while (!ctx.toProcess.isEmpty() && ctx.blocksToRemove.size() < maxBlocks) {
            BlockPos current = ctx.toProcess.poll();
            processConnectedBlock(current, ctx);
        }

        return ctx.blocksToRemove;
    }

    private static void processConnectedBlock(BlockPos current, SimpleFloodFillContext ctx) {
        LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(ctx.level, current);
        if (chunk == null) {
            return;
        }

        BlockState currentState = chunk.getBlockState(current);
        if (!ctx.blockMatcher.test(currentState)) {
            return;
        }

        ctx.blocksToRemove.add(current);
        queueUnvisitedNeighbors(current, ctx);
    }

    private static void queueUnvisitedNeighbors(BlockPos current, SimpleFloodFillContext ctx) {
        for (BlockPos offset : ctx.directions) {
            BlockPos neighbor = current.offset(offset);
            if (ctx.visited.contains(neighbor)) {
                continue;
            }
            ctx.visited.add(neighbor);
            if (ChunkCoordinateUtil.getLoadedChunk(ctx.level, neighbor) != null) {
                ctx.toProcess.add(neighbor);
            }
        }
    }

    /**
     * Flood-fill with configurable directions. Primary blocks are spread through,
     * secondary blocks are collected but not spread through (e.g., logs vs leaves).
     */
    public static FloodFillResult findConnectedBlocksConfigurable(
            ServerLevel level,
            BlockPos startPos,
            Predicate<BlockState> primaryMatcher,
            Predicate<BlockState> secondaryMatcher,
            BlockPos[] directions,
            int maxBlocks) {

        LevelChunk startChunk = ChunkCoordinateUtil.getLoadedChunk(level, startPos);
        if (startChunk == null) {
            return new FloodFillResult(Collections.emptyList(), Collections.emptyList());
        }

        FloodFillContext ctx = new FloodFillContext(level, primaryMatcher, secondaryMatcher, directions, maxBlocks);
        performPrimaryFloodFill(ctx, startPos);

        if (secondaryMatcher != null) {
            collectSecondaryBlocksAdjacentToPrimaries(ctx);
        }

        return ctx.toResult();
    }

    private static void performPrimaryFloodFill(FloodFillContext ctx, BlockPos startPos) {
        ctx.toProcess.add(startPos);
        ctx.visited.add(startPos);

        while (!ctx.toProcess.isEmpty() && ctx.hasCapacity()) {
            BlockPos current = ctx.toProcess.poll();
            processFloodFillPosition(ctx, current);
        }
    }

    private static void processFloodFillPosition(FloodFillContext ctx, BlockPos current) {
        LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(ctx.level, current);
        if (chunk == null) {
            return;
        }

        BlockState currentState = chunk.getBlockState(current);

        if (ctx.primaryMatcher.test(currentState)) {
            ctx.primaryBlocks.add(current);
            queueUnvisitedNeighbors(ctx, current);
        } else if (ctx.secondaryMatcher != null && ctx.secondaryMatcher.test(currentState)) {
            ctx.secondaryBlocks.add(current);
        }
    }

    private static void queueUnvisitedNeighbors(FloodFillContext ctx, BlockPos current) {
        for (BlockPos offset : ctx.directions) {
            BlockPos neighbor = current.offset(offset);
            if (!ctx.visited.contains(neighbor)) {
                ctx.visited.add(neighbor);
                if (ChunkCoordinateUtil.getLoadedChunk(ctx.level, neighbor) != null) {
                    ctx.toProcess.add(neighbor);
                }
            }
        }
    }

    private static void collectSecondaryBlocksAdjacentToPrimaries(FloodFillContext ctx) {
        for (BlockPos primaryPos : ctx.primaryBlocks) {
            collectSecondaryNeighbors(ctx, primaryPos);
        }
    }

    private static void collectSecondaryNeighbors(FloodFillContext ctx, BlockPos primaryPos) {
        for (BlockPos offset : ctx.directions) {
            BlockPos neighbor = primaryPos.offset(offset);

            if (ctx.visited.contains(neighbor)) {
                continue;
            }

            LevelChunk neighborChunk = ChunkCoordinateUtil.getLoadedChunk(ctx.level, neighbor);
            if (neighborChunk == null) {
                continue;
            }

            BlockState neighborState = neighborChunk.getBlockState(neighbor);
            if (ctx.secondaryMatcher.test(neighborState)) {
                ctx.secondaryBlocks.add(neighbor);
                ctx.visited.add(neighbor);
            }
        }
    }

    public static List<BlockPos> findVerticalConnectedBlocks(
            ServerLevel level,
            BlockPos startPos,
            Predicate<BlockState> blockMatcher,
            int maxHeight) {

        LevelChunk startChunk = ChunkCoordinateUtil.getLoadedChunk(level, startPos);
        if (startChunk == null) {
            return Collections.emptyList();
        }

        List<BlockPos> blocksToRemove = new ArrayList<>();
        BlockPos scanPos = startPos;
        for (int i = 0; i < maxHeight; i++) {
            BlockPos below = scanPos.below();
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, below);
            if (chunk == null) break;

            BlockState belowState = chunk.getBlockState(below);
            if (blockMatcher.test(belowState)) {
                blocksToRemove.add(below);
                scanPos = below;
            } else {
                break;
            }
        }

        blocksToRemove.add(startPos);
        scanPos = startPos;
        for (int i = 0; i < maxHeight; i++) {
            BlockPos above = scanPos.above();
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, above);
            if (chunk == null) break;

            BlockState aboveState = chunk.getBlockState(above);
            if (blockMatcher.test(aboveState)) {
                blocksToRemove.add(above);
                scanPos = above;
            } else {
                break;
            }
        }

        return blocksToRemove;
    }

    /**
     * Finds all connected vines using 6-directional flood-fill.
     * Vines can spread horizontally to adjacent block faces, not just vertically.
     */
    public static List<BlockPos> findConnectedVines(
            ServerLevel level,
            BlockPos startPos,
            int maxBlocks) {
        return findConnectedBlocks(level, startPos, BlockTypeUtil::isVine, CARDINAL_DIRECTIONS, maxBlocks);
    }

    public record FloodFillResult(List<BlockPos> primaryBlocks, List<BlockPos> secondaryBlocks) {
    }

    /**
     * Encapsulates configuration and mutable state for flood-fill operations.
     */
    private static class FloodFillContext {
        final ServerLevel level;
        final Predicate<BlockState> primaryMatcher;
        final Predicate<BlockState> secondaryMatcher;
        final BlockPos[] directions;
        final int maxBlocks;

        final Set<BlockPos> visited = new HashSet<>();
        final List<BlockPos> primaryBlocks = new ArrayList<>();
        final List<BlockPos> secondaryBlocks = new ArrayList<>();
        final Queue<BlockPos> toProcess = new LinkedList<>();

        FloodFillContext(ServerLevel level, Predicate<BlockState> primaryMatcher,
                         Predicate<BlockState> secondaryMatcher, BlockPos[] directions, int maxBlocks) {
            this.level = level;
            this.primaryMatcher = primaryMatcher;
            this.secondaryMatcher = secondaryMatcher;
            this.directions = directions;
            this.maxBlocks = maxBlocks;
        }

        int totalCollected() {
            return primaryBlocks.size() + secondaryBlocks.size();
        }

        boolean hasCapacity() {
            return totalCollected() < maxBlocks;
        }

        FloodFillResult toResult() {
            return new FloodFillResult(primaryBlocks, secondaryBlocks);
        }
    }
}
