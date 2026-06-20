package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes the pure rotation math in {@link RotationHelper}: direction rotation, schematic local->world
 * position transforms, and rotated-size swapping. (The block-state rotation path is world-bound and not
 * covered here.) World/config-free.
 */
class RotationHelperTest {

    @Test
    void rotateDirectionFollowsTheRotation() {
        assertEquals(Direction.NORTH, RotationHelper.rotateDirection(Direction.NORTH, Rotation.NONE));
        assertEquals(Direction.EAST, RotationHelper.rotateDirection(Direction.NORTH, Rotation.CLOCKWISE_90));
        assertEquals(Direction.SOUTH, RotationHelper.rotateDirection(Direction.NORTH, Rotation.CLOCKWISE_180));
        assertEquals(Direction.WEST, RotationHelper.rotateDirection(Direction.NORTH, Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    void getRotatedSizeSwapsWidthAndLengthForQuarterTurns() {
        Vec3i size = new Vec3i(3, 7, 5); // width=3, height=7, length=5
        assertEquals(size, RotationHelper.getRotatedSize(size, Rotation.NONE));
        assertEquals(size, RotationHelper.getRotatedSize(size, Rotation.CLOCKWISE_180), "180° keeps the footprint");
        assertEquals(new Vec3i(5, 7, 3), RotationHelper.getRotatedSize(size, Rotation.CLOCKWISE_90));
        assertEquals(new Vec3i(5, 7, 3), RotationHelper.getRotatedSize(size, Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    void transformPositionWithNoRotationIsAPlainOffsetFromOrigin() {
        BlockPos origin = new BlockPos(1000, 64, 2000);
        Vec3i size = new Vec3i(3, 1, 5);
        assertEquals(new BlockPos(1002, 67, 2004),
                RotationHelper.transformPosition(new BlockPos(2, 3, 4), size, origin, Rotation.NONE));
    }

    @Test
    void transformPositionRotatesWithinTheSchematicFootprint() {
        BlockPos origin = new BlockPos(0, 0, 0);
        Vec3i size = new Vec3i(3, 1, 5); // width=3, length=5

        // CW90: (x,z) -> (length-1-z, x). Corner (0,0) -> (4,0).
        assertEquals(new BlockPos(4, 0, 0),
                RotationHelper.transformPosition(new BlockPos(0, 0, 0), size, origin, Rotation.CLOCKWISE_90));
        // CW180: (x,z) -> (width-1-x, length-1-z). (0,0) -> (2,4).
        assertEquals(new BlockPos(2, 0, 4),
                RotationHelper.transformPosition(new BlockPos(0, 0, 0), size, origin, Rotation.CLOCKWISE_180));
        // CCW90: (x,z) -> (z, width-1-x). (0,0) -> (0,2).
        assertEquals(new BlockPos(0, 0, 2),
                RotationHelper.transformPosition(new BlockPos(0, 0, 0), size, origin, Rotation.COUNTERCLOCKWISE_90));
        // Y always passes straight through.
        assertEquals(9, RotationHelper.transformPosition(new BlockPos(1, 9, 2), size, origin, Rotation.CLOCKWISE_90).getY());
    }
}
