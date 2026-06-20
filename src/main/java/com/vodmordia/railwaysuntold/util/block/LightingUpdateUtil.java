package com.vodmordia.railwaysuntold.util.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;


/**
 * Utility for updating lighting after block placement/removal operations.
 */
public class LightingUpdateUtil {

    /**
     * Updates lighting for all chunks affected by operations in the given bounding box.
     * Uses checkBlock() to queue lighting recalculation for each position.
     *
     * Note: For spawn chunks, proper lighting is handled at the source in
     * ChunkSafeBlockAccess which uses flag 3 for block updates there.
     *
     * @param level Server level
     * @param start Start position of affected area
     * @param end   End position of affected area
     */
    public static void updateLightingForArea(ServerLevel level, BlockPos start, BlockPos end) {
        int minX = Math.min(start.getX(), end.getX());
        int maxX = Math.max(start.getX(), end.getX());
        int minY = Math.min(start.getY(), end.getY());
        int maxY = Math.max(start.getY(), end.getY());
        int minZ = Math.min(start.getZ(), end.getZ());
        int maxZ = Math.max(start.getZ(), end.getZ());

        var lightEngine = level.getChunkSource().getLightEngine();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    lightEngine.checkBlock(mutablePos.set(x, y, z));
                }
            }
        }
    }

}
