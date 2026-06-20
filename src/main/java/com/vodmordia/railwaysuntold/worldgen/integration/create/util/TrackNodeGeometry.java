package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Pure track-node geometry: where a track graph node sits on a block face for a given cardinal
 * direction, and which cardinal a bezier axis vector points along. World/Create-free coordinate math.
 */
public final class TrackNodeGeometry {

    private TrackNodeGeometry() {
    }

    /**
     * Computes the track node position at the block edge for a cardinal direction.
     */
    public static Vec3 computeNodePositionFromDirection(BlockPos blockPos, Direction dir) {
        return new Vec3(
                blockPos.getX() + 0.5 + dir.getStepX() * 0.5,
                blockPos.getY() + dir.getStepY() * 0.5,
                blockPos.getZ() + 0.5 + dir.getStepZ() * 0.5
        );
    }

    /**
     * Converts a bezier axis vector to the corresponding cardinal Direction,
     * using the dominant horizontal component (handles sloped beziers).
     */
    public static Direction axisToCardinalDirection(Vec3 axis) {
        double absX = Math.abs(axis.x);
        double absZ = Math.abs(axis.z);
        if (absX >= absZ) {
            return axis.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return axis.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
