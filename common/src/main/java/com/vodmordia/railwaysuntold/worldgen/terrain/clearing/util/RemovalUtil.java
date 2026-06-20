package com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.VegetationRemover.TreeAttachments;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.*;

/**
 * Utility class for removing blocks
 */
public class RemovalUtil {

    private static final int INITIAL_FLOATING_CHECK_HEIGHT = 20;
    private static final int FLOATING_CHECK_EXTENSION = 10;
    private static final int HORIZONTAL_LEAF_CHECK_RADIUS = 3;

    /**
     * Two-phase clearing: non-vegetation is cleared immediately, logs and
     * leaves are deferred until finish() to ensure all trees are identified
     * before calculating leaf support.
     */
    public static class ClearingContext {
        private final ServerLevel level;
        private final List<BlockPos> clearedPositions = new ArrayList<>();
        private final List<BlockPos> floatingVegetation = new ArrayList<>();
        private final Set<BlockPos> deferredLogPositions = new HashSet<>();
        // Leaves directly in the clearing path - always remove regardless of support
        private final Set<BlockPos> forcedLeafRemovals = new HashSet<>();
        // Leaves found above clearing path - only remove if unsupported
        private final Set<BlockPos> deferredLeafPositions = new HashSet<>();
        private boolean skipFloatingVegetation = false;
        private boolean underground = false;
        private boolean preserveWaterBelowTrack = true;
        private List<BoundingBox> protectedVillageBounds = Collections.emptyList();
        // Tracks which column XZ positions have been scanned for floating vegetation
        // to avoid redundant overlapping scans from adjacent cleared blocks
        private final Set<Long> scannedVegetationColumns = new HashSet<>();

        // Bounding box of all cleared positions for tighter lighting updates
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private boolean hasClearedPositions = false;

        public ClearingContext(ServerLevel level) {
            this.level = level;
        }

        private void expandBounds(BlockPos pos) {
            int x = pos.getX(), y = pos.getY(), z = pos.getZ();
            if (!hasClearedPositions) {
                minX = maxX = x;
                minY = maxY = y;
                minZ = maxZ = z;
                hasClearedPositions = true;
            } else {
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }

        /** Returns the bounding box of all cleared positions, or null if nothing was cleared. */
        public BlockPos getClearedBoundsMin() {
            return hasClearedPositions ? new BlockPos(minX, minY, minZ) : null;
        }

        /** Returns the bounding box of all cleared positions, or null if nothing was cleared. */
        public BlockPos getClearedBoundsMax() {
            return hasClearedPositions ? new BlockPos(maxX, maxY, maxZ) : null;
        }

        public void setProtectedVillageBounds(List<BoundingBox> bounds) {
            this.protectedVillageBounds = bounds != null ? bounds : Collections.emptyList();
        }

        /** Underground segments have vegetation rooted in real terrain above, not floating. */
        public void setSkipFloatingVegetation(boolean skip) {
            this.skipFloatingVegetation = skip;
        }

        /** Marks this segment as underground - bypasses village protection since tunnels can't damage surface structures. */
        public void setUnderground(boolean underground) {
            this.underground = underground;
        }

        public boolean shouldPreserveWaterBelowTrack() {
            return preserveWaterBelowTrack;
        }

        /** Leaves in the clearing path are always removed; logs and leaves above are deferred. */
        public boolean clearBlock(BlockPos pos) {
            if (VillageTargetingSavedData.isBlockProtectedByStation(level, pos)) {
                return false;
            }

            // Village structure protection - don't clear blocks inside village bounds
            // Underground tunnels bypass this since they can't damage surface structures
            if (!underground && !protectedVillageBounds.isEmpty()) {
                for (BoundingBox box : protectedVillageBounds) {
                    if (box.isInside(pos)) {
                        return false;
                    }
                }
            }

            // Resolve chunk once and reuse for both state read and clearing
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
            if (chunk == null) {
                return false;
            }

            BlockState state = chunk.getBlockState(pos);
            if (state.isAir()) {
                return false;
            }

            if (state.is(BlockTags.LOGS)) {
                deferredLogPositions.add(pos.immutable());
                expandBounds(pos);
                return true;
            }
            if (state.is(BlockTags.LEAVES)) {
                forcedLeafRemovals.add(pos.immutable());
                expandBounds(pos);
                return true;
            }

            // Station protection already checked above - use internal method to skip redundant check
            if (clearSingleBlockInternal(level, pos, state)) {
                clearedPositions.add(pos);
                expandBounds(pos);
                if (!skipFloatingVegetation) {
                    collectFloatingVegetation(level, pos);
                }
                return true;
            }
            return false;
        }

        private void collectFloatingVegetation(ServerLevel level, BlockPos clearedPos) {
            long columnKey = ((long) clearedPos.getX() << 32) | (clearedPos.getZ() & 0xFFFFFFFFL);

            // if the block directly above is solid, no floating vegetation possible.

            LevelChunk aboveChunk = ChunkCoordinateUtil.getLoadedChunk(level, clearedPos.above());
            if (aboveChunk != null) {
                BlockState aboveState = aboveChunk.getBlockState(clearedPos.above());
                if (!aboveState.isAir() && aboveState.isSolid()
                        && !aboveState.is(BlockTags.LOGS) && !aboveState.is(BlockTags.LEAVES)) {
                    return;
                }
            }

            // Skip if this column has already been fully scanned by another cleared block
            if (!scannedVegetationColumns.add(columnKey)) {
                return;
            }

            int maxHeight = INITIAL_FLOATING_CHECK_HEIGHT;
            int consecutiveEmpty = 0;

            for (int yOffset = 1; yOffset <= maxHeight; yOffset++) {
                BlockPos centerAbove = clearedPos.above(yOffset);
                boolean foundAtThisLevel = scanLevelForFloatingVegetation(level, centerAbove);

                if (foundAtThisLevel) {
                    maxHeight = Math.max(maxHeight, yOffset + FLOATING_CHECK_EXTENSION);
                    consecutiveEmpty = 0;
                } else {
                    consecutiveEmpty++;
                    if (consecutiveEmpty >= 3) {
                        break;
                    }
                }
            }
        }

        private boolean scanLevelForFloatingVegetation(ServerLevel level, BlockPos centerAbove) {
            boolean foundAtThisLevel = false;

            for (int dx = -HORIZONTAL_LEAF_CHECK_RADIUS; dx <= HORIZONTAL_LEAF_CHECK_RADIUS; dx++) {
                for (int dz = -HORIZONTAL_LEAF_CHECK_RADIUS; dz <= HORIZONTAL_LEAF_CHECK_RADIUS; dz++) {
                    if (checkPositionForVegetation(level, centerAbove.offset(dx, 0, dz), dx, dz)) {
                        foundAtThisLevel = true;
                    }
                }
            }

            return foundAtThisLevel;
        }

        private boolean checkPositionForVegetation(ServerLevel level, BlockPos checkPos, int dx, int dz) {
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, checkPos);
            if (chunk == null) {
                return false;
            }

            BlockState state = chunk.getBlockState(checkPos);
            if (state.isAir()) {
                return false;
            }

            // Our own protected structure (e.g. bridge portal posts built from logs) must not be
            // mistaken for a floating tree and queued for removal. clearBlock() honours station
            // protection; this floating-vegetation scan must too.
            if (VillageTargetingSavedData.isBlockProtectedByStation(level, checkPos)) {
                return false;
            }

            if (state.is(BlockTags.LOGS)) {
                deferredLogPositions.add(checkPos.immutable());
                return true;
            }

            if (state.is(BlockTags.LEAVES)) {
                deferredLeafPositions.add(checkPos.immutable());
                return true;
            }

            if (isAdjacentToCenter(dx, dz) && isFloatingPlantBlock(state)) {
                // Preserve snow layers on intact snowy ground (grass/podzol/mycelium) to avoid
                // exposing grayscale snowy=true grass or out-of-place green grass in snowy biomes
                if (state.getBlock() instanceof SnowLayerBlock && isOnSnowyGround(level, checkPos)) {
                    return false;
                }
                floatingVegetation.add(checkPos);
                return true;
            }

            return false;
        }

        private static boolean isAdjacentToCenter(int dx, int dz) {
            return Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
        }

        private static boolean isFloatingPlantBlock(BlockState state) {
            return state.getBlock() instanceof SnowLayerBlock
                    || state.getBlock() instanceof FallingBlock
                    || BlockTypeUtil.isPlant(state, BlockTypeUtil.PlantCheckMode.GROUND_SUPPORTED);
        }

        private static boolean isOnSnowyGround(ServerLevel level, BlockPos snowPos) {
            BlockPos belowPos = snowPos.below();
            LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, belowPos);
            if (chunk == null) {
                return false;
            }
            return chunk.getBlockState(belowPos).hasProperty(SnowyDirtBlock.SNOWY);
        }

        /**
         * Scans upward from pos and clears contiguous gravity blocks (sand, gravel, etc.)
         * to prevent cave-ins after ceiling clearing.
         */
        public void clearGravityBlocksAbove(BlockPos pos) {
            for (BlockPos scanPos = pos.above(); ; scanPos = scanPos.above()) {
                LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, scanPos);
                if (chunk == null) break;

                BlockState state = chunk.getBlockState(scanPos);
                if (!(state.getBlock() instanceof FallingBlock)) break;

                if (!clearBlock(scanPos)) break;
            }
        }

        /** Preserves water/fluid blocks for girder waterlogging. */
        public boolean clearBlockPreserveWater(BlockPos pos) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
            if (state == null) {
                return false;
            }
            if (!state.getFluidState().isEmpty()) {
                return false;
            }
            return clearBlock(pos);
        }

        public void finish() {
            processDeferredVegetation();

            if (!floatingVegetation.isEmpty()) {
                removeFloatingVegetation(level, floatingVegetation);
            }

            if (!clearedPositions.isEmpty()) {
                VegetationRemover.removeOrphanedLeaves(level, clearedPositions);
                BlockBatchRemover.convertExposedDirtToGrass(level, clearedPositions);
            }
        }

        private void processDeferredVegetation() {
            if (deferredLogPositions.isEmpty() && deferredLeafPositions.isEmpty() && forcedLeafRemovals.isEmpty()) {
                return;
            }

            Set<BlockPos> logsToRemove = identifyConnectedLogsToRemove();
            Set<BlockPos> leavesToRemove = identifyLeavesToRemove(logsToRemove);
            List<BlockPos> allToRemove = buildRemovalList(logsToRemove, leavesToRemove);

            if (!allToRemove.isEmpty()) {
                BlockBatchRemover.batchRemoveBlocks(level, allToRemove);
                clearedPositions.addAll(allToRemove);
                for (BlockPos removed : allToRemove) {
                    expandBounds(removed);
                }
            }

            deferredLogPositions.clear();
            forcedLeafRemovals.clear();
            deferredLeafPositions.clear();
        }

        private Set<BlockPos> identifyConnectedLogsToRemove() {
            Set<BlockPos> allLogsToRemove = new HashSet<>();
            Set<BlockPos> processedLogStarts = new HashSet<>();

            for (BlockPos logPos : deferredLogPositions) {
                if (processedLogStarts.contains(logPos)) {
                    continue;
                }

                LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, logPos);
                if (chunk == null) {
                    continue;
                }
                BlockState state = chunk.getBlockState(logPos);
                if (!state.is(BlockTags.LOGS)) {
                    continue;
                }

                List<BlockPos> connected = BlockFloodFill.findConnectedBlocks(level, logPos,
                        s -> s.is(BlockTags.LOGS), VegetationRemover.MAX_TREE_BLOCKS);
                processedLogStarts.addAll(connected);

                if (VegetationRemover.hasNearbyLeaves(level, connected)) {
                    // A real tree - but the log flood-fill can spill from the tree into adjacent PROTECTED
                    // bridge logs (the deck edges, under-deck fascia and pillar legs are all logs, one
                    // connected mass). Never remove our own protected structure, or an incline-side tree
                    // takes the whole bridge with it.
                    for (BlockPos connectedLog : connected) {
                        if (!VillageTargetingSavedData.isBlockProtectedByStation(level, connectedLog)) {
                            allLogsToRemove.add(connectedLog);
                        }
                    }
                } else {
                    // Structural logs (no leaves) - only remove the ones directly in the clearing path
                    for (BlockPos connectedLog : connected) {
                        if (deferredLogPositions.contains(connectedLog)
                                && !VillageTargetingSavedData.isBlockProtectedByStation(level, connectedLog)) {
                            allLogsToRemove.add(connectedLog);
                        }
                    }
                }
            }

            return allLogsToRemove;
        }

        private Set<BlockPos> identifyLeavesToRemove(Set<BlockPos> logsToRemove) {
            Set<BlockPos> leavesToRemove = new HashSet<>(forcedLeafRemovals);
            Set<BlockPos> unsupportedLeaves = VegetationRemover.findLeavesUnsupportedAfterLogRemoval(level, logsToRemove);
            leavesToRemove.addAll(unsupportedLeaves);

            for (BlockPos leafPos : deferredLeafPositions) {
                if (leavesToRemove.contains(leafPos)) {
                    continue;
                }

                LevelChunk chunk = ChunkCoordinateUtil.getLoadedChunk(level, leafPos);
                if (chunk == null) {
                    continue;
                }
                BlockState state = chunk.getBlockState(leafPos);
                if (!state.is(BlockTags.LEAVES)) {
                    continue;
                }

                if (!VegetationRemover.isLeafSupportedByRemainingLog(level, leafPos, logsToRemove)) {
                    leavesToRemove.add(leafPos);
                }
            }

            return leavesToRemove;
        }

        private List<BlockPos> buildRemovalList(Set<BlockPos> logsToRemove, Set<BlockPos> leavesToRemove) {
            TreeAttachments attachments = collectAttachments(logsToRemove, leavesToRemove);

            List<BlockPos> allToRemove = new ArrayList<>();
            Set<BlockPos> allToRemoveSet = new HashSet<>();

            VegetationRemover.addAllIfAbsent(logsToRemove, allToRemove, allToRemoveSet);
            VegetationRemover.addAllIfAbsent(leavesToRemove, allToRemove, allToRemoveSet);
            mergeAttachmentsToRemovalList(attachments, allToRemove, allToRemoveSet);

            VegetationRemover.collectSnowAbove(level, logsToRemove, allToRemove, allToRemoveSet);
            VegetationRemover.collectSnowAbove(level, leavesToRemove, allToRemove, allToRemoveSet);

            return allToRemove;
        }

        private TreeAttachments collectAttachments(Set<BlockPos> logsToRemove, Set<BlockPos> leavesToRemove) {
            TreeAttachments attachments = new TreeAttachments();

            for (BlockPos logPos : logsToRemove) {
                VegetationRemover.collectAdjacentVines(level, logPos, attachments.vines);
                VegetationRemover.collectAdjacentCocoa(level, logPos, attachments.cocoa);
                VegetationRemover.collectAdjacentDeadBranches(level, logPos, attachments.deadBranches);
                VegetationRemover.collectAdjacentMossCarpet(level, logPos, attachments.mossCarpet);
            }

            for (BlockPos leafPos : leavesToRemove) {
                VegetationRemover.collectAdjacentVines(level, leafPos, attachments.vines);
                VegetationRemover.collectAdjacentPropagules(level, leafPos, attachments.propagules);
            }

            return attachments;
        }

        private void mergeAttachmentsToRemovalList(TreeAttachments attachments, List<BlockPos> allToRemove, Set<BlockPos> allToRemoveSet) {
            for (BlockPos vinePos : attachments.vines) {
                List<BlockPos> vineChain = BlockFloodFill.findConnectedVines(level, vinePos, VegetationRemover.MAX_VINE_HEIGHT);
                for (BlockPos chainPos : vineChain) {
                    VegetationRemover.addIfAbsent(chainPos, allToRemove, allToRemoveSet);
                }
            }

            VegetationRemover.addAllIfAbsent(attachments.cocoa, allToRemove, allToRemoveSet);
            VegetationRemover.addAllIfAbsent(attachments.deadBranches, allToRemove, allToRemoveSet);
            VegetationRemover.addAllIfAbsent(attachments.propagules, allToRemove, allToRemoveSet);
            VegetationRemover.addAllIfAbsent(attachments.mossCarpet, allToRemove, allToRemoveSet);
        }
    }

    public static void removeFloatingVegetation(ServerLevel level, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        positions.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
        BlockBatchRemover.batchRemoveBlocks(level, positions);
    }

    /**
     * Internal clearing path with pre-resolved state, skipping station protection
     * (caller must have already verified). Avoids redundant chunk lookups and protection checks.
     */
    private static boolean clearSingleBlockInternal(ServerLevel level, BlockPos pos, BlockState state) {
        if (isBlockProtectedFromClearing(state)) {
            return false;
        }

        return clearSingleBlockCore(level, pos, state);
    }

    private static boolean clearSingleBlockCore(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof SnowLayerBlock) {
            BlockBatchRemover.updateSnowyDirtBelow(level, pos);
        }

        VegetationRemover.removeConnectedStructureIfApplicable(level, pos, state);

        ChunkSafeBlockAccess.clearContainerContents(level, pos);
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, Blocks.AIR.defaultBlockState(), true);
        return true;
    }

    private static boolean isBlockProtectedFromClearing(BlockState state) {
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER) ||
                state.is(Blocks.COMMAND_BLOCK) || state.is(Blocks.CHAIN_COMMAND_BLOCK) ||
                state.is(Blocks.REPEATING_COMMAND_BLOCK)) {
            return true;
        }

        if (CreateTrackUtil.isTrackBlock(state) || CreateTrackUtil.isGirderBlock(state)) {
            return true;
        }

        return BlockTypeUtil.isChest(state);
    }

    public static int clearBox(ServerLevel level, BlockPos minPos, BlockPos maxPos) {
        int minX = Math.min(minPos.getX(), maxPos.getX());
        int minY = Math.min(minPos.getY(), maxPos.getY());
        int minZ = Math.min(minPos.getZ(), maxPos.getZ());
        int maxX = Math.max(minPos.getX(), maxPos.getX());
        int maxY = Math.max(minPos.getY(), maxPos.getY());
        int maxZ = Math.max(minPos.getZ(), maxPos.getZ());

        ClearingContext ctx = new ClearingContext(level);
        int clearedCount = 0;

        for (int y = maxY; y >= minY; y--) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (ctx.clearBlock(pos)) {
                        clearedCount++;
                    }
                }
            }
        }

        ctx.finish();
        return clearedCount;
    }

}
