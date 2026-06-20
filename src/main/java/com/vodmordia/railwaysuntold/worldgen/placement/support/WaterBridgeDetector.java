package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Detects whether track positions are over water or elevated terrain,
 * determining if bridge decking should be used instead of ground supports.
 */
public final class WaterBridgeDetector {

    private static final int FLUID_SCAN_DEPTH = 20;
    private static final int FLUID_SCAN_RADIUS = 4;
    private static final double FLUID_COLUMN_THRESHOLD = 0.15;

    // consecutive track blocks along a line usually share the same result.
    // This avoids re-scanning 81 columns * 20 deep for each adjacent block.
    private static long cachedPosKey = Long.MIN_VALUE;
    private static boolean cachedResult;

    private WaterBridgeDetector() {
    }

    /** Clears the single-position result cache so an isolated test starts from a known state. */
    public static void resetCache() {
        cachedPosKey = Long.MIN_VALUE;
        cachedResult = false;
    }

    /**
     * Checks if bridge decking should be used at this track position.
     * Returns true if track is over water or significantly elevated above ground.
     */
    public static boolean shouldUseBridgeDecking(ServerLevel level, BlockPos trackPos) {
        long key = trackPos.asLong();
        if (key == cachedPosKey) {
            return cachedResult;
        }
        boolean result = computeShouldUseBridgeDecking(level, trackPos);
        cachedPosKey = key;
        cachedResult = result;
        return result;
    }

    private static boolean computeShouldUseBridgeDecking(ServerLevel level, BlockPos trackPos) {
        if (isOverFluid(level, trackPos)) {
            return true;
        }
        int threshold = SupportConstants.getBridgeElevationThreshold();
        if (threshold <= 0) {
            return false;
        }
        return isElevatedAboveGround(level, trackPos, threshold);
    }

    /**
     * True if the gap below the deck is deeper than the bridge threshold - i.e. terrain fill would bail
     * ({@code DEEP_GAP}) and leave a hole, so such a position must be bridged even in a sub-minimum run.
     */
    public static boolean isDeepGap(ServerLevel level, BlockPos trackPos) {
        int threshold = SupportConstants.getBridgeElevationThreshold();
        return threshold > 0 && isElevatedAboveGround(level, trackPos, threshold);
    }

    /**
     * Checks if there are at least {@code minGap} blocks of air/liquid below the decking
     * level (trackY - 2) before hitting solid ground.
     */
    private static boolean isElevatedAboveGround(ServerLevel level, BlockPos trackPos, int minGap) {
        BlockPos.MutableBlockPos check = new BlockPos.MutableBlockPos(
                trackPos.getX(), trackPos.getY() - 2, trackPos.getZ());
        for (int i = 0; i < minGap; i++) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, check);
            if (state == null) return false;
            if (!state.isAir() && !state.canBeReplaced()
                    && !BlockTypeUtil.isAirOrLiquid(state)
                    && !BlockTypeUtil.isTreeOrWood(state)
                    && !CreateTrackUtil.isTrackBlock(state)
                    && !CreateTrackUtil.isGirderBlock(state)
                    && !VillageTargetingSavedData.isBlockProtectedByStation(level, check)) {
                return false;
            }
            check.setY(check.getY() - 1);
        }
        return true;
    }

    /**
     * Checks if there is a substantial body of fluid below or near the track position.
     */
    private static boolean isOverFluid(ServerLevel level, BlockPos trackPos) {
        int fluidColumns = 0;
        int totalColumns = 0;

        for (int dx = -FLUID_SCAN_RADIUS; dx <= FLUID_SCAN_RADIUS; dx++) {
            for (int dz = -FLUID_SCAN_RADIUS; dz <= FLUID_SCAN_RADIUS; dz++) {
                totalColumns++;
                if (hasFluidBelow(level, trackPos.getX() + dx, trackPos.getZ() + dz, trackPos.getY())) {
                    fluidColumns++;
                }
            }
        }
        return fluidColumns >= totalColumns * FLUID_COLUMN_THRESHOLD;
    }

    private static boolean hasFluidBelow(ServerLevel level, int x, int z, int trackY) {
        BlockPos.MutableBlockPos checkPos = new BlockPos.MutableBlockPos(x, trackY - 1, z);
        int minY = Math.max(level.getMinBuildHeight(), trackY - FLUID_SCAN_DEPTH);

        for (int y = checkPos.getY(); y >= minY; y--) {
            checkPos.setY(y);
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, checkPos);
            if (state == null) return false;
            if (BlockTypeUtil.isAnyFluid(state)) return true;
            if (!state.isAir() && !state.canBeReplaced()
                    && !BlockTypeUtil.isTreeOrWood(state)
                    && !CreateTrackUtil.isTrackBlock(state)
                    && !CreateTrackUtil.isGirderBlock(state)) {
                return false;
            }
        }
        return false;
    }
}
