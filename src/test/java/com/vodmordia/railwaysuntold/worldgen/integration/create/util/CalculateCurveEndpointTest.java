package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Characterizes {@link CreateTrackUtil#calculateCurveEndpoint} - the pure curve geometry the decider, siding
 * and branch passes all assumed correct. A curve advances {@code forward} along the track direction and
 * {@code lateral} along the turn direction (left/right of travel), with the elevation change applied straight
 * to Y. A 90° quarter-turn moves {@code radius} on each axis (exact integer math); a 45° turn uses the
 * truncated magnitudes {@code (int)(radius*1.4)} forward / {@code (int)(radius*0.7)} lateral.
 *
 * Convention (from DirectionUtil): facing EAST, left is NORTH (−Z) and right is SOUTH (+Z); facing SOUTH,
 * left is EAST (+X).
 */
class CalculateCurveEndpointTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);

    @Test
    void quarterTurnMovesRadiusForwardAndRadiusLaterally() {
        // EAST + left (NORTH): forward +X by R, lateral −Z by R.
        assertEquals(new BlockPos(20, 64, -20),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 90, 20, 0));
        // EAST + right (SOUTH): forward +X by R, lateral +Z by R.
        assertEquals(new BlockPos(20, 64, 20),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, false, 90, 20, 0));
        // SOUTH + left (EAST): forward +Z by R, lateral +X by R.
        assertEquals(new BlockPos(20, 64, 20),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.SOUTH, true, 90, 20, 0));
    }

    @Test
    void elevationChangeIsAppliedStraightToY() {
        assertEquals(new BlockPos(20, 69, -20),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 90, 20, 5));
        assertEquals(new BlockPos(20, 61, -20),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 90, 20, -3));
    }

    @Test
    void fortyFiveDegreeTurnUsesTheTruncatedForwardAndLateralMagnitudes() {
        // R=20: forward (int)(20*1.4)=28, lateral (int)(20*0.7)=14. EAST+left -> +28 X, −14 Z.
        assertEquals(new BlockPos(28, 64, -14),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 45, 20, 0));
    }

    @Test
    void fortyFiveDegreeLateralTruncatesTheHalfBlockOnOddRadii() {
        // R=15: forward (int)(15*1.4)=21, lateral (int)(15*0.7)=(int)10.5=10 - the half block is dropped.
        assertEquals(new BlockPos(21, 64, -10),
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 45, 15, 0));
    }

    @Test
    void anUnsupportedAngleIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                CreateTrackUtil.calculateCurveEndpoint(START, Direction.EAST, true, 30, 20, 0));
    }
}
