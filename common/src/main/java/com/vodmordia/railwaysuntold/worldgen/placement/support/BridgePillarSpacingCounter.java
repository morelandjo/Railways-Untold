package com.vodmordia.railwaysuntold.worldgen.placement.support;

import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.GirderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;

/**
 * Tracks bridge-pillar spacing along a track.
 */
public class BridgePillarSpacingCounter {

    private static final int SOLID_THRESHOLD = 3;

    private int consecutiveAirBlocks = 0;
    private int consecutiveSolidBlocks = 0;
    private boolean previousWasSolid = true;
    private int totalBlocksSinceLastPillar = 0;
    private final HashSet<BlockPos> countedPositions = new HashSet<>();

    /**
     * Monotonic longitudinal index advanced once per placed bridge deck row. Drives the repeating
     * railing pattern (index % patternLength) and the pier boundary (index % patternLength == 0),
     * so the railing and piers share one continuous coordinate along the whole span.
     */
    private int longitudinalIndex = 0;

    /** Returns the current longitudinal index, then advances it by one. */
    public int nextLongitudinal() {
        return longitudinalIndex++;
    }

    public boolean checkAndUpdate(ServerLevel level, BlockPos trackPos, Vec3 tangent) {
        boolean hasAir = hasAirOrWaterBelowGirders(level, trackPos, tangent);
        return processPosition(hasAir, trackPos);
    }

    public boolean checkAndUpdate(ServerLevel level, Vec3 worldPosition, Vec3 tangent) {
        BlockPos trackPos = BlockPos.containing(worldPosition);
        boolean hasAir = hasAirOrWaterBelowGirdersVec3(level, worldPosition, tangent);
        return processPosition(hasAir, trackPos);
    }

    private boolean processPosition(boolean hasAir, BlockPos trackPos) {
        totalBlocksSinceLastPillar++;
        // Vestigial: pier spacing now comes from the railing pattern length (see nextLongitudinal()).
        // This method's result is no longer consumed; the constant keeps the old logic self-contained.
        int interval = 16;

        if (hasAir) {
            consecutiveSolidBlocks = 0;

            if (previousWasSolid) {
                consecutiveAirBlocks = 0;
                countedPositions.clear();
                previousWasSolid = false;
                if (totalBlocksSinceLastPillar >= interval) {
                    totalBlocksSinceLastPillar = 0;
                    return true;
                }
            }

            if (countedPositions.add(trackPos)) {
                consecutiveAirBlocks++;
                if (consecutiveAirBlocks >= interval) {
                    consecutiveAirBlocks = 0;
                    countedPositions.clear();
                    totalBlocksSinceLastPillar = 0;
                    return true;
                }
            }
        } else {
            consecutiveSolidBlocks++;
            if (consecutiveSolidBlocks >= SOLID_THRESHOLD) {
                consecutiveAirBlocks = 0;
                countedPositions.clear();
                previousWasSolid = true;
            }
        }

        return false;
    }

    private static boolean hasAirOrWaterBelowGirders(ServerLevel level, BlockPos trackPos, Vec3 tangent) {
        BlockPos[] girderPositions = GirderUtil.getGirderPositions(trackPos, tangent);
        for (BlockPos girderPos : girderPositions) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, girderPos.below());
            if (state == null) return false;
            if (!BlockTypeUtil.isAirOrLiquid(state)) return false;
        }
        return true;
    }

    private static boolean hasAirOrWaterBelowGirdersVec3(ServerLevel level, Vec3 worldPosition, Vec3 tangent) {
        BlockPos[] girderPositions = GirderUtil.getGirderPositions(worldPosition, tangent);
        for (BlockPos girderPos : girderPositions) {
            BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, girderPos.below());
            if (state == null) return false;
            if (!BlockTypeUtil.isAirOrLiquid(state)) return false;
        }
        return true;
    }
}
