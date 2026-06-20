package com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.BlockFloodFill.FloodFillResult;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;

import java.util.*;
import java.util.function.Predicate;

/**
 * Tree/mushroom/bamboo/vine removal and leaf decay logic.
 * Package-private - only called from {@link RemovalUtil}.
 */
class VegetationRemover {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int MAX_TREE_BLOCKS = 500;
    static final int MAX_BAMBOO_HEIGHT = 20;
    static final int MAX_MUSHROOM_BLOCKS = 500;
    static final int MAX_VINE_HEIGHT = 50;
    static final int LEAF_DECAY_DISTANCE = 7;

    static void removeConnectedStructureIfApplicable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            removeTree(level, pos);
        } else if (BlockTypeUtil.isMushroom(state)) {
            removeMushroom(level, pos);
        } else if (BlockTypeUtil.isBamboo(state)) {
            removeBamboo(level, pos);
        } else if (BlockTypeUtil.isVine(state)) {
            removeVines(level, pos);
        }
    }

    /**
     * Checks if any logs in the group have adjacent leaf blocks.
     * Natural trees always have leaves near their logs; structural logs (e.g. village roofs) don't.
     */
    static boolean hasNearbyLeaves(ServerLevel level, Collection<BlockPos> connectedLogs) {
        for (BlockPos logPos : connectedLogs) {
            for (BlockPos offset : BlockFloodFill.CARDINAL_DIRECTIONS) {
                BlockPos neighbor = logPos.offset(offset);
                LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, neighbor);
                if (chunk == null) {
                    continue;
                }
                if (chunk.getBlockState(neighbor).is(BlockTags.LEAVES)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void removeTree(ServerLevel level, BlockPos startPos) {
        FloodFillResult result = BlockFloodFill.findConnectedBlocksConfigurable(
                level,
                startPos,
                state -> state.is(BlockTags.LOGS),
                state -> state.is(BlockTags.LEAVES) || state.is(BlockTags.BEEHIVES) || BlockTypeUtil.isVine(state) || BlockTypeUtil.isCocoa(state) || BlockTypeUtil.isDeadBranch(state),
                BlockFloodFill.ALL_ADJACENT_DIRECTIONS,
                MAX_TREE_BLOCKS
        );

        if (!hasNearbyLeaves(level, result.primaryBlocks())) {
            return;
        }

        List<BlockPos> allBlocks = new ArrayList<>(result.primaryBlocks());
        Set<BlockPos> allBlocksSet = new HashSet<>(result.primaryBlocks());
        Set<BlockPos> logsBeingRemoved = new HashSet<>(result.primaryBlocks());

        Set<BlockPos> leavesToRemove = findLeavesUnsupportedAfterLogRemoval(level, logsBeingRemoved);

        collectNonLeafSecondaryBlocks(level, result.secondaryBlocks(), allBlocks, allBlocksSet);
        addAllIfAbsent(leavesToRemove, allBlocks, allBlocksSet);

        TreeAttachments attachments = collectTreeAttachments(level, result.primaryBlocks(), leavesToRemove);
        addAttachmentsToRemovalList(level, attachments, allBlocks, allBlocksSet);

        collectSnowAbove(level, result.primaryBlocks(), allBlocks, allBlocksSet);
        collectSnowAbove(level, leavesToRemove, allBlocks, allBlocksSet);

        if (allBlocks.isEmpty()) {
            return;
        }

        BlockBatchRemover.batchRemoveBlocks(level, allBlocks);

        if (allBlocks.size() >= MAX_TREE_BLOCKS) {
            LOGGER.warn("[CLEARING] tree removal hit limit ({}) at {}. Structure may be partially removed.",
                    MAX_TREE_BLOCKS, startPos);
        }
    }

    static void removeMushroom(ServerLevel level, BlockPos startPos) {
        removeStructure(
                level,
                startPos,
                () -> BlockFloodFill.findConnectedBlocks(
                        level,
                        startPos,
                        BlockTypeUtil::isMushroom,
                        MAX_MUSHROOM_BLOCKS
                )
        );
    }

    static void removeBamboo(ServerLevel level, BlockPos startPos) {
        removeStructure(
                level,
                startPos,
                () -> BlockFloodFill.findVerticalConnectedBlocks(
                        level,
                        startPos,
                        BlockTypeUtil::isBamboo,
                        MAX_BAMBOO_HEIGHT
                )
        );
    }

    static void removeVines(ServerLevel level, BlockPos startPos) {
        removeStructure(
                level,
                startPos,
                () -> BlockFloodFill.findConnectedVines(level, startPos, MAX_VINE_HEIGHT)
        );
    }

    static void removeStructure(
            ServerLevel level,
            BlockPos startPos,
            java.util.function.Supplier<List<BlockPos>> blockFinder) {

        List<BlockPos> blocksToRemove = blockFinder.get();

        if (blocksToRemove.isEmpty()) {
            return;
        }

        BlockBatchRemover.batchRemoveBlocks(level, blocksToRemove);
    }

    /**
     * Finds all leaves that will become unsupported after the given logs are removed.
     *
     */
    static Set<BlockPos> findLeavesUnsupportedAfterLogRemoval(ServerLevel level, Set<BlockPos> logsBeingRemoved) {
        // Step 1: Collect all leaves within decay distance of any removed log
        Set<BlockPos> candidateLeaves = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        // Also collect remaining logs in the scan area to seed reverse-BFS
        Set<BlockPos> remainingLogsInArea = new HashSet<>();
        // Cache chunks to avoid repeated lookups in the same area
        Map<Long, LevelChunk> chunkCache = new HashMap<>();

        for (BlockPos logPos : logsBeingRemoved) {
            for (int dx = -LEAF_DECAY_DISTANCE; dx <= LEAF_DECAY_DISTANCE; dx++) {
                for (int dy = -LEAF_DECAY_DISTANCE; dy <= LEAF_DECAY_DISTANCE; dy++) {
                    for (int dz = -LEAF_DECAY_DISTANCE; dz <= LEAF_DECAY_DISTANCE; dz++) {
                        int taxicabDist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (taxicabDist > LEAF_DECAY_DISTANCE) {
                            continue;
                        }

                        BlockPos checkPos = logPos.offset(dx, dy, dz);
                        if (!visited.add(checkPos)) {
                            continue;
                        }

                        LevelChunk chunk = getChunkCached(level, checkPos, chunkCache);
                        if (chunk == null) {
                            continue;
                        }

                        BlockState blockState = chunk.getBlockState(checkPos);
                        if (blockState.is(BlockTags.LEAVES)) {
                            candidateLeaves.add(checkPos);
                        } else if (blockState.is(BlockTags.LOGS) && !logsBeingRemoved.contains(checkPos)) {
                            remainingLogsInArea.add(checkPos);
                        }
                    }
                }
            }
        }

        if (candidateLeaves.isEmpty()) {
            return Collections.emptySet();
        }

        // Step 2: Reverse-BFS from remaining logs outward through leaves to find supported leaves
        Set<BlockPos> supportedLeaves = new HashSet<>();
        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, Integer> distances = new HashMap<>();

        // Seed BFS from remaining logs at distance 0
        for (BlockPos logPos : remainingLogsInArea) {
            queue.add(logPos);
            distances.put(logPos, 0);
        }

        int maxDist = LEAF_DECAY_DISTANCE - 1;

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            int currentDist = distances.get(current);

            for (BlockPos offset : BlockFloodFill.CARDINAL_DIRECTIONS) {
                BlockPos neighbor = current.offset(offset);

                if (distances.containsKey(neighbor)) {
                    continue;
                }

                if (!candidateLeaves.contains(neighbor)) {
                    continue;
                }

                // This leaf is reachable from a remaining log
                supportedLeaves.add(neighbor);

                if (currentDist < maxDist) {
                    distances.put(neighbor, currentDist + 1);
                    queue.add(neighbor);
                }
            }
        }

        // Step 3: Unsupported = candidates - supported
        candidateLeaves.removeAll(supportedLeaves);

        // Step 4: Flood-fill expansion - check neighbors of unsupported leaves
        // that may be outside the initial scan radius but also unsupported
        expandLeavesViaFloodFill(level, candidateLeaves, logsBeingRemoved, chunkCache);

        return candidateLeaves;
    }

    private static LevelChunk getChunkCached(ServerLevel level, BlockPos pos, Map<Long, LevelChunk> cache) {
        long key = ChunkCoordinateUtil.getChunkKey(pos);
        return cache.computeIfAbsent(key, k -> ChunkCoordinateUtil.getLoadedChunk(level, pos));
    }

    private static void expandLeavesViaFloodFill(ServerLevel level, Set<BlockPos> leavesToRemove,
                                                   Set<BlockPos> logsBeingRemoved, Map<Long, LevelChunk> chunkCache) {
        Queue<BlockPos> toProcess = new LinkedList<>(leavesToRemove);

        while (!toProcess.isEmpty() && leavesToRemove.size() < MAX_TREE_BLOCKS) {
            BlockPos current = toProcess.poll();
            for (BlockPos offset : BlockFloodFill.ALL_ADJACENT_DIRECTIONS) {
                BlockPos neighbor = current.offset(offset);

                if (leavesToRemove.contains(neighbor)) {
                    continue;
                }

                LevelChunk chunk = getChunkCached(level, neighbor, chunkCache);
                if (chunk == null) {
                    continue;
                }

                BlockState blockState = chunk.getBlockState(neighbor);
                if (blockState.is(BlockTags.LEAVES)) {
                    if (!isLeafSupportedByRemainingLog(level, neighbor, logsBeingRemoved, chunkCache)) {
                        leavesToRemove.add(neighbor);
                        toProcess.add(neighbor);
                    }
                }
            }
        }
    }

    static boolean isLeafSupportedByRemainingLog(ServerLevel level, BlockPos leafPos, Set<BlockPos> logsBeingRemoved) {
        return findLeafSupportPath(level, leafPos, logsBeingRemoved, null);
    }

    private static boolean isLeafSupportedByRemainingLog(ServerLevel level, BlockPos leafPos,
                                                           Set<BlockPos> logsBeingRemoved, Map<Long, LevelChunk> chunkCache) {
        return findLeafSupportPath(level, leafPos, logsBeingRemoved, chunkCache);
    }

    /**
     * BFS to find support path from leaf to log using 6-directional search (matches vanilla decay).
     */
    static boolean findLeafSupportPath(ServerLevel level, BlockPos leafPos, Set<BlockPos> excludedLogs,
                                         Map<Long, LevelChunk> chunkCache) {
        int maxPathLength = LEAF_DECAY_DISTANCE - 1;

        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> toProcess = new LinkedList<>();
        Map<BlockPos, Integer> distances = new HashMap<>();

        toProcess.add(leafPos);
        visited.add(leafPos);
        distances.put(leafPos, 0);

        while (!toProcess.isEmpty()) {
            BlockPos current = toProcess.poll();
            int currentDist = distances.get(current);

            for (BlockPos offset : BlockFloodFill.CARDINAL_DIRECTIONS) {
                BlockPos neighbor = current.offset(offset);

                if (visited.contains(neighbor)) {
                    continue;
                }

                if (excludedLogs.contains(neighbor)) {
                    visited.add(neighbor);
                    continue;
                }

                LevelChunk chunk = chunkCache != null
                        ? getChunkCached(level, neighbor, chunkCache)
                        : ChunkCoordinateUtil.getLoadedChunk(level, neighbor);
                if (chunk == null) {
                    continue;
                }

                BlockState blockState = chunk.getBlockState(neighbor);

                if (blockState.is(BlockTags.LOGS)) {
                    return true;
                }

                if (blockState.is(BlockTags.LEAVES) && currentDist < maxPathLength) {
                    visited.add(neighbor);
                    toProcess.add(neighbor);
                    distances.put(neighbor, currentDist + 1);
                }
            }
        }

        return false;
    }

    static void removeOrphanedLeaves(ServerLevel level, List<BlockPos> clearedPositions) {
        Set<BlockPos> checkedPositions = new HashSet<>();
        List<BlockPos> leavesToRemove = new ArrayList<>();
        Map<Long, LevelChunk> chunkCache = new HashMap<>();

        collectInitialOrphanedLeaves(level, clearedPositions, leavesToRemove, checkedPositions, chunkCache);
        expandOrphanedLeavesViaFloodFill(level, leavesToRemove, checkedPositions, chunkCache);

        if (!leavesToRemove.isEmpty()) {
            BlockBatchRemover.batchRemoveBlocks(level, leavesToRemove);
        }
    }

    private static void collectInitialOrphanedLeaves(
            ServerLevel level, List<BlockPos> clearedPositions,
            List<BlockPos> leavesToRemove, Set<BlockPos> checkedPositions,
            Map<Long, LevelChunk> chunkCache) {

        for (BlockPos clearedPos : clearedPositions) {
            scanDecayRadiusForOrphanedLeaves(level, clearedPos, leavesToRemove, checkedPositions, chunkCache);
        }
    }

    private static void scanDecayRadiusForOrphanedLeaves(
            ServerLevel level, BlockPos centerPos,
            List<BlockPos> leavesToRemove, Set<BlockPos> checkedPositions,
            Map<Long, LevelChunk> chunkCache) {

        for (int dx = -LEAF_DECAY_DISTANCE; dx <= LEAF_DECAY_DISTANCE; dx++) {
            for (int dy = -LEAF_DECAY_DISTANCE; dy <= LEAF_DECAY_DISTANCE; dy++) {
                for (int dz = -LEAF_DECAY_DISTANCE; dz <= LEAF_DECAY_DISTANCE; dz++) {
                    int taxicabDist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (taxicabDist > LEAF_DECAY_DISTANCE) {
                        continue;
                    }

                    BlockPos checkPos = centerPos.offset(dx, dy, dz);
                    checkAndCollectOrphanedLeaf(level, checkPos, leavesToRemove, checkedPositions, chunkCache);
                }
            }
        }
    }

    private static void checkAndCollectOrphanedLeaf(
            ServerLevel level, BlockPos checkPos,
            List<BlockPos> leavesToRemove, Set<BlockPos> checkedPositions,
            Map<Long, LevelChunk> chunkCache) {

        if (!checkedPositions.add(checkPos)) {
            return;
        }

        LevelChunk chunk = getChunkCached(level, checkPos, chunkCache);
        if (chunk == null) {
            return;
        }

        BlockState state = chunk.getBlockState(checkPos);
        if (state.is(BlockTags.LEAVES) && !isLeafSupportedByLog(level, checkPos, chunkCache)) {
            leavesToRemove.add(checkPos);
        }
    }

    private static void expandOrphanedLeavesViaFloodFill(
            ServerLevel level, List<BlockPos> leavesToRemove, Set<BlockPos> checkedPositions,
            Map<Long, LevelChunk> chunkCache) {

        Queue<BlockPos> toProcess = new LinkedList<>(leavesToRemove);
        Set<BlockPos> leavesSet = new HashSet<>(leavesToRemove);

        while (!toProcess.isEmpty() && leavesSet.size() < MAX_TREE_BLOCKS) {
            BlockPos current = toProcess.poll();

            for (BlockPos offset : BlockFloodFill.ALL_ADJACENT_DIRECTIONS) {
                BlockPos neighbor = current.offset(offset);

                if (leavesSet.contains(neighbor) || checkedPositions.contains(neighbor)) {
                    continue;
                }
                checkedPositions.add(neighbor);

                LevelChunk chunk = getChunkCached(level, neighbor, chunkCache);
                if (chunk == null) {
                    continue;
                }

                BlockState state = chunk.getBlockState(neighbor);
                if (state.is(BlockTags.LEAVES) && !isLeafSupportedByLog(level, neighbor, chunkCache)) {
                    leavesToRemove.add(neighbor);
                    leavesSet.add(neighbor);
                    toProcess.add(neighbor);
                }
            }
        }
    }

    private static boolean isLeafSupportedByLog(ServerLevel level, BlockPos leafPos, Map<Long, LevelChunk> chunkCache) {
        return findLeafSupportPath(level, leafPos, Collections.emptySet(), chunkCache);
    }

    static class TreeAttachments {
        final Set<BlockPos> vines = new HashSet<>();
        final Set<BlockPos> cocoa = new HashSet<>();
        final Set<BlockPos> deadBranches = new HashSet<>();
        final Set<BlockPos> propagules = new HashSet<>();
        final Set<BlockPos> mossCarpet = new HashSet<>();
    }

    private static TreeAttachments collectTreeAttachments(ServerLevel level, List<BlockPos> logs, Set<BlockPos> leaves) {
        TreeAttachments attachments = new TreeAttachments();

        for (BlockPos logPos : logs) {
            collectAdjacentVines(level, logPos, attachments.vines);
            collectAdjacentCocoa(level, logPos, attachments.cocoa);
            collectAdjacentDeadBranches(level, logPos, attachments.deadBranches);
            collectAdjacentMossCarpet(level, logPos, attachments.mossCarpet);
        }

        for (BlockPos leafPos : leaves) {
            collectAdjacentVines(level, leafPos, attachments.vines);
            collectAdjacentPropagules(level, leafPos, attachments.propagules);
        }

        return attachments;
    }

    private static void collectNonLeafSecondaryBlocks(ServerLevel level, List<BlockPos> secondaryBlocks,
                                                       List<BlockPos> allBlocks, Set<BlockPos> allBlocksSet) {
        for (BlockPos secondaryPos : secondaryBlocks) {
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, secondaryPos);
            if (chunk != null && !chunk.getBlockState(secondaryPos).is(BlockTags.LEAVES)) {
                addIfAbsent(secondaryPos, allBlocks, allBlocksSet);
            }
        }
    }

    private static void addAttachmentsToRemovalList(ServerLevel level, TreeAttachments attachments,
                                                     List<BlockPos> allBlocks, Set<BlockPos> allBlocksSet) {
        for (BlockPos vinePos : attachments.vines) {
            List<BlockPos> vineChain = BlockFloodFill.findConnectedVines(level, vinePos, MAX_VINE_HEIGHT);
            for (BlockPos chainPos : vineChain) {
                addIfAbsent(chainPos, allBlocks, allBlocksSet);
            }
        }

        addAllIfAbsent(attachments.cocoa, allBlocks, allBlocksSet);
        addAllIfAbsent(attachments.deadBranches, allBlocks, allBlocksSet);
        addAllIfAbsent(attachments.propagules, allBlocks, allBlocksSet);
        addAllIfAbsent(attachments.mossCarpet, allBlocks, allBlocksSet);
    }

    static void collectAdjacentVines(ServerLevel level, BlockPos pos, Set<BlockPos> vinePositions) {
        collectAdjacentBlocks(level, pos, BlockTypeUtil::isVine, vinePositions);
    }

    static void collectAdjacentCocoa(ServerLevel level, BlockPos pos, Set<BlockPos> cocoaPositions) {
        collectAdjacentBlocks(level, pos, BlockTypeUtil::isCocoa, cocoaPositions);
    }

    static void collectAdjacentDeadBranches(ServerLevel level, BlockPos pos, Set<BlockPos> deadBranchPositions) {
        collectAdjacentBlocks(level, pos, BlockTypeUtil::isDeadBranch, deadBranchPositions);
    }

    static void collectAdjacentPropagules(ServerLevel level, BlockPos pos, Set<BlockPos> propagulePositions) {
        collectAdjacentBlocks(level, pos, BlockTypeUtil::isPropagule, propagulePositions);
    }

    static void collectAdjacentMossCarpet(ServerLevel level, BlockPos pos, Set<BlockPos> mossCarpetPositions) {
        collectAdjacentBlocks(level, pos, BlockTypeUtil::isMossCarpet, mossCarpetPositions);
    }

    private static void collectAdjacentBlocks(ServerLevel level, BlockPos pos, Predicate<BlockState> matcher, Set<BlockPos> targetPositions) {
        for (BlockPos offset : BlockFloodFill.ALL_ADJACENT_DIRECTIONS) {
            BlockPos neighbor = pos.offset(offset);
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, neighbor);
            if (chunk == null) {
                continue;
            }

            BlockState neighborState = chunk.getBlockState(neighbor);
            if (matcher.test(neighborState)) {
                targetPositions.add(neighbor);
            }
        }
    }

    static void collectSnowAbove(ServerLevel level, Collection<BlockPos> positions, List<BlockPos> targetList, Set<BlockPos> targetSet) {
        for (BlockPos pos : positions) {
            BlockPos abovePos = pos.above();

            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, abovePos);
            if (chunk == null) {
                continue;
            }

            BlockState aboveState = chunk.getBlockState(abovePos);
            if (aboveState.getBlock() instanceof SnowLayerBlock) {
                addIfAbsent(abovePos, targetList, targetSet);
            }
        }
    }

    static void addIfAbsent(BlockPos pos, List<BlockPos> list, Set<BlockPos> set) {
        if (set.add(pos)) {
            list.add(pos);
        }
    }

    static void addAllIfAbsent(Set<BlockPos> positions, List<BlockPos> allBlocks, Set<BlockPos> allBlocksSet) {
        for (BlockPos pos : positions) {
            addIfAbsent(pos, allBlocks, allBlocksSet);
        }
    }
}
