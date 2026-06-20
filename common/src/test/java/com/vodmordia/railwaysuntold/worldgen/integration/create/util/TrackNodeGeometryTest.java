package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link TrackNodeGeometry}: the block-face node position for a cardinal direction (block
 * center ± half a block toward the face) and the dominant-horizontal-axis mapping of a bezier axis vector
 * to a cardinal direction (with X winning ties).
 */
class TrackNodeGeometryTest {

    @Test
    void nodePositionSitsHalfABlockTowardEachHorizontalFace() {
        BlockPos pos = new BlockPos(10, 64, 20);
        assertEquals(new Vec3(11.0, 64.0, 20.5),
                TrackNodeGeometry.computeNodePositionFromDirection(pos, Direction.EAST));
        assertEquals(new Vec3(10.0, 64.0, 20.5),
                TrackNodeGeometry.computeNodePositionFromDirection(pos, Direction.WEST));
        assertEquals(new Vec3(10.5, 64.0, 21.0),
                TrackNodeGeometry.computeNodePositionFromDirection(pos, Direction.SOUTH));
        assertEquals(new Vec3(10.5, 64.0, 20.0),
                TrackNodeGeometry.computeNodePositionFromDirection(pos, Direction.NORTH));
    }

    @Test
    void nodePositionShiftsHalfABlockVerticallyForUp() {
        assertEquals(new Vec3(10.5, 64.5, 20.5),
                TrackNodeGeometry.computeNodePositionFromDirection(new BlockPos(10, 64, 20), Direction.UP));
    }

    @Test
    void axisMapsToTheDominantHorizontalCardinal() {
        assertEquals(Direction.EAST, TrackNodeGeometry.axisToCardinalDirection(new Vec3(1, 0, 0.2)));
        assertEquals(Direction.WEST, TrackNodeGeometry.axisToCardinalDirection(new Vec3(-1, 0, 0.2)));
        assertEquals(Direction.SOUTH, TrackNodeGeometry.axisToCardinalDirection(new Vec3(0.2, 0, 1)));
        assertEquals(Direction.NORTH, TrackNodeGeometry.axisToCardinalDirection(new Vec3(0.2, 0, -1)));
    }

    @Test
    void axisTieBreaksTowardTheXAxis() {
        // |x| == |z| -> X wins (absX >= absZ).
        assertEquals(Direction.EAST, TrackNodeGeometry.axisToCardinalDirection(new Vec3(1, 0, 1)));
        assertEquals(Direction.WEST, TrackNodeGeometry.axisToCardinalDirection(new Vec3(-1, 0, 1)));
    }
}
