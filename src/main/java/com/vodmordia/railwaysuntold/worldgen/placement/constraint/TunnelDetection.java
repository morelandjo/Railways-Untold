package com.vodmordia.railwaysuntold.worldgen.placement.constraint;

import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Utility for detecting tunnel conditions during track placement.
 */
public final class TunnelDetection {

    private TunnelDetection() {
    }

    /**
     * Buffer below surface for tunnel escape mode.
     * The top of the vertical clearing area (track Y + vertical expansion) must be
     * at least this many blocks below the surface to be considered "in tunnel" mode.
     */
    private static final int TUNNEL_ESCAPE_SURFACE_BUFFER = 10;

    /**
     * Checks if the current position is already in a tunnel (deep underground).
     *
     * @param level      Server level
     * @param currentPos Current track position
     * @return true if the position is considered to be in tunnel mode
     */
    public static boolean isAlreadyInTunnel(ServerLevel level, BlockPos currentPos) {
        int surfaceY = TerrainHeightUtil.getRawGroundLevel(level, currentPos.getX(), currentPos.getZ());
        return currentPos.getY() <= surfaceY - TUNNEL_ESCAPE_SURFACE_BUFFER;
    }
}
