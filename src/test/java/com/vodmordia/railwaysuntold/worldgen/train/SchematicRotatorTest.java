package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterizes {@link SchematicRotator}'s pure rotation math: the clockwise-steps ↔ {@link Rotation}
 * mapping, the schematic->track alignment, and the BlockPos rotation (clockwise about Y, Y preserved,
 * four steps returning to the start).
 */
class SchematicRotatorTest {

    @Test
    void blockPosRotatesClockwiseAboutYPreservingHeight() {
        // Clockwise viewed from above: +X -> +Z -> -X -> -Z.
        assertEquals(new BlockPos(0, 5, 1), SchematicRotator.rotateBlockPos(new BlockPos(1, 5, 0), 1));
        assertEquals(new BlockPos(-1, 5, 0), SchematicRotator.rotateBlockPos(new BlockPos(0, 5, 1), 1));
    }

    @Test
    void fourClockwiseStepsAreTheIdentity() {
        BlockPos p = new BlockPos(3, 9, -2);
        assertEquals(p, SchematicRotator.rotateBlockPos(p, 4));
    }

    @Test
    void clockwiseStepsMapToRotation() {
        // toClockwiseSteps is the inverse of the (private) fromClockwiseSteps used by calculateRotation.
        assertEquals(0, SchematicRotator.toClockwiseSteps(Rotation.NONE));
        assertEquals(1, SchematicRotator.toClockwiseSteps(Rotation.CLOCKWISE_90));
        assertEquals(2, SchematicRotator.toClockwiseSteps(Rotation.CLOCKWISE_180));
        assertEquals(3, SchematicRotator.toClockwiseSteps(Rotation.COUNTERCLOCKWISE_90));
    }

    @Test
    void rotateByRotationMatchesRotateBySteps() {
        BlockPos p = new BlockPos(4, 1, 7);
        for (Rotation r : Rotation.values()) {
            assertEquals(SchematicRotator.rotateBlockPos(p, SchematicRotator.toClockwiseSteps(r)),
                    SchematicRotator.rotateBlockPos(p, r), "rotation " + r);
        }
    }

    @Test
    void clockwiseStepsBetweenDirections() {
        // Minecraft yaw: SOUTH=0, WEST=90, NORTH=180, EAST=270.
        assertEquals(0, SchematicRotator.getClockwiseSteps(Direction.NORTH, Direction.NORTH));
        assertEquals(1, SchematicRotator.getClockwiseSteps(Direction.NORTH, Direction.EAST));
        assertEquals(2, SchematicRotator.getClockwiseSteps(Direction.SOUTH, Direction.NORTH));
        assertEquals(3, SchematicRotator.getClockwiseSteps(Direction.EAST, Direction.NORTH));
    }

    @Test
    void calculateRotationAlignsSchematicToTrack() {
        assertEquals(Rotation.NONE, SchematicRotator.calculateRotation(Direction.NORTH, Direction.NORTH, false));
        assertEquals(Rotation.CLOCKWISE_90, SchematicRotator.calculateRotation(Direction.NORTH, Direction.EAST, false));
    }

    @Test
    void flipForFrontAddsAHalfTurn() {
        // Same schematic/track direction: no rotation, or a 180° flip when flipForFront is set.
        assertEquals(Rotation.NONE, SchematicRotator.calculateRotation(Direction.EAST, Direction.EAST, false));
        assertEquals(Rotation.CLOCKWISE_180, SchematicRotator.calculateRotation(Direction.EAST, Direction.EAST, true));
    }
}
