package com.vodmordia.railwaysuntold.util.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link SCurve45Geometry} - the pure 45° S-curve geometry the siding / diagonal / scurve45
 * placement assumed correct without pinning (cf. CreateTrackUtil.calculateCurveEndpoint). An S-curve is a
 * 45° curve out to a diagonal, a diagonal straight, then a 45° curve back to the original heading. All
 * lengths are ceil'd against cos45/sin45 (~0.707); the sequential calculation avoids combined-formula
 * rounding drift. World/config-free.
 */
class SCurve45GeometryTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);

    @Test
    void scalarLengthHelpers() {
        assertEquals(16, SCurve45Geometry.calculate45CurveArcLength(20), "ceil(20·π/4)");
        assertEquals(42, SCurve45Geometry.calculateTotalPathLength(20, 10), "two arcs + diagonal");
        assertEquals(36, SCurve45Geometry.calculateForwardDistance(20, 10), "ceil(2·20·sin45 + 10·cos45)");
    }

    @Test
    void diagonalLengthForLateralShiftInvertsTheLateralFormula() {
        // A large shift needs a diagonal run beyond what the two curves already provide.
        assertEquals(55, SCurve45Geometry.calculateDiagonalLengthForLateralShift(50, 20));
        // A small shift is already covered by the two curves' lateral footprint -> no diagonal needed.
        assertEquals(0, SCurve45Geometry.calculateDiagonalLengthForLateralShift(10, 20),
                "the curves alone over-provide a 10-block shift");
    }

    @Test
    void elevationIsDistributedAcrossTheThreeSegmentsWithoutLoss() {
        // Proportional to segment lengths, and the three parts always sum back to the total (no rounding loss).
        assertEquals(4, SCurve45Geometry.distributeElevationForSegment(10, 20, 10, 0));
        assertEquals(2, SCurve45Geometry.distributeElevationForSegment(10, 20, 10, 1));
        assertEquals(4, SCurve45Geometry.distributeElevationForSegment(10, 20, 10, 2));

        for (int total : new int[]{7, 10, 13, -9}) {
            int sum = SCurve45Geometry.distributeElevationForSegment(total, 20, 10, 0)
                    + SCurve45Geometry.distributeElevationForSegment(total, 20, 10, 1)
                    + SCurve45Geometry.distributeElevationForSegment(total, 20, 10, 2);
            assertEquals(total, sum, "segments must sum to the total elevation (" + total + ")");
        }

        assertEquals(0, SCurve45Geometry.distributeElevationForSegment(0, 20, 10, 1), "no elevation -> 0");
    }

    @Test
    void firstCurveEndAdvancesForwardAndLaterallyByTheCeiledCurveFootprint() {
        // EAST + left (NORTH): forward ceil(20·sin45)=15 in +X, lateral ceil(20·(1−cos45))=6 in −Z.
        assertEquals(new BlockPos(15, 64, -6),
                SCurve45Geometry.calculateFirstCurveEnd(START, Direction.EAST, true, 20, 0));
        // The elevation argument is a delta added to the start Y (64 + 7).
        assertEquals(71, SCurve45Geometry.calculateFirstCurveEnd(START, Direction.EAST, true, 20, 7).getY());
    }

    @Test
    void diagonalEndAddsTheDiagonalRunToTheFirstCurve() {
        // diagonal of 10: ceil(10·cos45)=8 forward, ceil(10·sin45)=8 lateral, added to the first-curve end.
        assertEquals(new BlockPos(23, 64, -14),
                SCurve45Geometry.calculateDiagonalEndFromFinal(START, Direction.EAST, true, 20, 10, 0));
    }

    @Test
    void endpointComposesBothCurvesAndTheDiagonal() {
        // EAST/left, R=20, diag=10, flat: (15,−6) + (8,−8) + (15,−6) = (38, −20).
        assertEquals(new BlockPos(38, 64, -20),
                SCurve45Geometry.calculateEndpoint(START, Direction.EAST, true, 20, 10, 0));
    }

    @Test
    void shiftRightMirrorsTheLateralComponent() {
        // EAST + right (SOUTH): same forward advance, lateral flips to +Z.
        BlockPos left = SCurve45Geometry.calculateEndpoint(START, Direction.EAST, true, 20, 10, 0);
        BlockPos right = SCurve45Geometry.calculateEndpoint(START, Direction.EAST, false, 20, 10, 0);
        assertEquals(left.getX(), right.getX(), "forward advance is the same either way");
        assertEquals(-left.getZ(), right.getZ(), "lateral is mirrored");
        assertTrue(right.getZ() > 0, "a right shift from EAST goes south (+Z)");
    }
}
