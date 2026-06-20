package com.vodmordia.railwaysuntold.worldgen.api;

import net.minecraft.core.BlockPos;

/**
 * Public API: an axis-aligned exclusion volume the track planner must route around.
 * Provided by external code via {@link AvoidanceZoneProvider}.
 */
public record AvoidanceZone(BlockPos minCorner, BlockPos maxCorner) {

    public boolean contains(BlockPos pos) {
        return pos.getX() >= minCorner.getX() && pos.getX() <= maxCorner.getX()
            && pos.getY() >= minCorner.getY() && pos.getY() <= maxCorner.getY()
            && pos.getZ() >= minCorner.getZ() && pos.getZ() <= maxCorner.getZ();
    }
}
