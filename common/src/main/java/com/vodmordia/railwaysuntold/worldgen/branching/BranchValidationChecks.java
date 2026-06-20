package com.vodmordia.railwaysuntold.worldgen.branching;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkVerificationUtil;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.SeenChunkRegistry;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Runtime validation checks for branch spawning.
 */
public class BranchValidationChecks {

    // Track conflict detection parameters
    private static final int CONFLICT_CHECK_START = 8;   // Skip past parent track and curve area
    private static final int CONFLICT_CHECK_END = 16;    // End of conflict check range

    // Chunk loading check radius
    private static final int CHUNK_CHECK_RADIUS = 16;

    /**
     * Performs all runtime safety checks before branch spawn.
     *
     * @param head The head attempting to branch
     * @param manager The expansion head manager
     * @param branchPos Position where branch would start
     * @param branchDir Direction of the branch
     * @param level The server level
     * @param config The configuration
     * @return true if all checks pass
     */
    public static boolean passesRuntimeChecks(
            TrackExpansionHead head,
            ExpansionHeadManager manager,
            BlockPos branchPos,
            Direction branchDir,
            ServerLevel level
    ) {
        // Check 1: Maximum active heads limit
        if (!hasCapacityForNewHead(manager)) {
            return false;
        }

        // Check 2: Minimum spacing from last branch
        int minBranchSpacing = RailwaysUntoldConfig.getMinBranchSpacing();
        if (head.getBlocksSinceLastBranch() < minBranchSpacing) {
            return false;
        }

        // Check 3: Track conflict check
        if (hasTrackConflict(level, branchPos, branchDir)) {
            return false;
        }

        // Check 4: Chunks loaded
        if (!areChunksLoaded(level, branchPos)) {
            return false;
        }

        return true;
    }

    /**
     * Checks if the head manager has capacity for another head.
     */
    public static boolean hasCapacityForNewHead(ExpansionHeadManager manager) {
        int maxHeads = RailwaysUntoldConfig.getMaxActiveHeads();
        // 0 means unlimited
        if (maxHeads == 0) {
            return true;
        }
        return manager.getActiveHeadCount() < maxHeads;
    }

    /**
     * Checks for track conflicts at the branch position.
     */
    public static boolean hasTrackConflict(ServerLevel level, BlockPos pos, Direction dir) {
        int horizontalExpansion = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalExpansion = RailwaysUntoldConfig.getVerticalExpansion();

        BlockPos checkStart = pos.relative(dir, CONFLICT_CHECK_START);
        BlockPos checkEnd = pos.relative(dir, CONFLICT_CHECK_END);

        var affectedChunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(checkStart, checkEnd);

        for (var chunkPos : affectedChunks) {
            var segments = ConnectedBoundaryTracker.getSegmentsInChunk(level, chunkPos);
            for (var segment : segments) {
                int minX, maxX, minZ, maxZ;
                if (dir == Direction.EAST || dir == Direction.WEST) {
                    minX = Math.min(checkStart.getX(), checkEnd.getX()) - horizontalExpansion;
                    maxX = Math.max(checkStart.getX(), checkEnd.getX()) + horizontalExpansion;
                    int centerZ = (checkStart.getZ() + checkEnd.getZ()) / 2;
                    minZ = centerZ - horizontalExpansion;
                    maxZ = centerZ + horizontalExpansion;
                } else {
                    int centerX = (checkStart.getX() + checkEnd.getX()) / 2;
                    minX = centerX - horizontalExpansion;
                    maxX = centerX + horizontalExpansion;
                    minZ = Math.min(checkStart.getZ(), checkEnd.getZ()) - horizontalExpansion;
                    maxZ = Math.max(checkStart.getZ(), checkEnd.getZ()) + horizontalExpansion;
                }
                int minY = pos.getY() - verticalExpansion;
                int maxY = pos.getY() + verticalExpansion;

                if (isInBounds(segment.start, minX, maxX, minY, maxY, minZ, maxZ) ||
                    isInBounds(segment.end, minX, maxX, minY, maxY, minZ, maxZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a position is within bounds.
     */
    private static boolean isInBounds(BlockPos pos, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return pos.getX() >= minX && pos.getX() <= maxX &&
               pos.getY() >= minY && pos.getY() <= maxY &&
               pos.getZ() >= minZ && pos.getZ() <= maxZ;
    }

    /**
     * Checks if chunks are loaded around the branch position.
     */
    public static boolean areChunksLoaded(ServerLevel level, BlockPos pos) {
        BlockPos min = pos.offset(-CHUNK_CHECK_RADIUS, 0, -CHUNK_CHECK_RADIUS);
        BlockPos max = pos.offset(CHUNK_CHECK_RADIUS, 0, CHUNK_CHECK_RADIUS);
        if (ChunkVerificationUtil.areBoundingBoxChunksLoaded(level, min, max)) {
            return true;
        }
        // Branch chunks aren't currently loaded. Ask the chunk-load expander
        // to ticket-load any that have been seen this session (chunky touched
        // them) so the next branch attempt at this head finds them resident.
        // Returning false here just skips this attempt; subsequent placement
        // decisions will re-evaluate.
        SeenChunkRegistry.requestSeenChunkLoad(level, ChunkCoordinateUtil.getChunksInBoundingBox(min, max));
        return false;
    }

    /**
     * Validates branch spacing across all active heads to prevent clustering.
     *
     * @param branchPos Proposed branch position
     * @param manager The expansion head manager
     * @param minSpacing Minimum spacing required (blocks)
     * @param parentHead The head that is creating the branch (excluded from check)
     * @return true if adequate spacing exists from all other active heads
     */
    public static boolean hasAdequateSpacing(BlockPos branchPos, ExpansionHeadManager manager, int minSpacing, TrackExpansionHead parentHead) {
        int minSpacingSqr = minSpacing * minSpacing;
        for (TrackExpansionHead otherHead : manager.getActiveHeads()) {
            // Skip the parent head that's creating this branch
            if (otherHead.getHeadId().equals(parentHead.getHeadId())) {
                continue;
            }
            double distanceSqr = otherHead.getPosition().distSqr(branchPos);
            if (distanceSqr < minSpacingSqr) {
                return false;
            }
        }
        return true;
    }
}
