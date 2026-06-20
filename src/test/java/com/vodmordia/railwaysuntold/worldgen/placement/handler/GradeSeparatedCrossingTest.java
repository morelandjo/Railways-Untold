package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.vodmordia.railwaysuntold.worldgen.placement.analyzer.CrossingDetectionService.CrossingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link AbstractPlacementExecutor#isGradeSeparatedCrossing}: a grade-separated crossing - perpendicular
 * OR a 45-degree diagonal (no cardinal direction) - clears the collision check, so a head can go over/under
 * it instead of being forced to an at-grade junction. A parallel/collinear overlap (same cardinal axis) is
 * NOT exempted: it runs alongside for a whole span and keeps the stricter terrain envelope.
 */
class GradeSeparatedCrossingTest {

    private static final BlockPos PT = new BlockPos(0, 70, 0);
    private static final int CLEAR = 6;   // >= GRADE_SEPARATED_CROSSING_CLEARANCE (5)

    private static CrossingInfo info(int proposedY, int existingY, Direction existingDir, boolean perpendicular) {
        return new CrossingInfo(PT, existingY, proposedY, existingDir, perpendicular, null);
    }

    @Test
    void aClearedPerpendicularCrossingIsGradeSeparated() {
        assertTrue(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(76, 70, Direction.EAST, true)));
    }

    @Test
    void aClearedDiagonalCrossingIsGradeSeparated() {
        // A 45-degree line has no cardinal direction (null) and isPerpendicular=false, but a route that
        // clears it vertically still passes over/under - it must not read as a collision.
        assertTrue(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(76, 70, null, false)));
    }

    @Test
    void aParallelOverlapIsNotGradeSeparatedEvenWhenClear() {
        // Same cardinal axis, not perpendicular: runs alongside for a whole span, keeps the strict envelope.
        assertFalse(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(76, 70, Direction.NORTH, false)));
    }

    @Test
    void aCrossingWithoutEnoughClearanceIsNotGradeSeparated() {
        assertFalse(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(73, 70, Direction.EAST, true)), "perpendicular but too tight");
        assertFalse(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(73, 70, null, false)), "diagonal but too tight");
    }

    @Test
    void clearanceCountsBelowAsWellAsAbove() {
        assertTrue(AbstractPlacementExecutor.isGradeSeparatedCrossing(
                info(70 - CLEAR, 70, null, false)), "under-pass of a diagonal");
    }

    // isAtGradeCrossable is the crossing test WITHOUT the vertical-clearance requirement: it admits the
    // at-grade pass-through (a perpendicular/diagonal line the head crosses flat when it can neither merge
    // nor ramp), while still rejecting a parallel overlap that must never be placed through.

    @Test
    void aTightPerpendicularCrossingIsAtGradeCrossable() {
        // Head 16's geometry: a perpendicular line met TIGHT (3 blocks of clearance, < the 5 needed for
        // grade separation). Not grade-separated, but a legitimate at-grade crossing the head can pass.
        var tight = info(73, 70, Direction.EAST, true);
        assertFalse(AbstractPlacementExecutor.isGradeSeparatedCrossing(tight), "3 blocks is below clearance");
        assertTrue(AbstractPlacementExecutor.isAtGradeCrossable(tight), "but a perpendicular line is crossable");
    }

    @Test
    void aSameYPerpendicularCrossingIsAtGradeCrossable() {
        assertTrue(AbstractPlacementExecutor.isAtGradeCrossable(info(70, 70, Direction.EAST, true)));
    }

    @Test
    void aDiagonalCrossingIsAtGradeCrossable() {
        assertTrue(AbstractPlacementExecutor.isAtGradeCrossable(info(70, 70, null, false)));
    }

    @Test
    void aParallelOverlapIsNotAtGradeCrossable() {
        // Same cardinal axis, not perpendicular: it runs alongside, not across, so it must never be crossed
        // through at grade (that would lay collinear track on the existing line).
        assertFalse(AbstractPlacementExecutor.isAtGradeCrossable(info(70, 70, Direction.NORTH, false)));
    }
}
