package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link JunctionMergeGeometry#canConnectLegally}: a merge only connects where the joining curve
 * holds the minimum radius, so a train can drive it.
 *
 * PERPENDICULAR (a single &le;90-degree branch): legal when forward &ge; minRadius and lateral &le; forward.
 * PARALLEL (an S-curve into a same-heading line): legal when the S radius (F^2 + L^2)/(4L) &ge; minRadius,
 * which needs MORE forward than the single-turn rule - the head-6 illegal merge that motivated the
 * parallel case (forward 27, lateral 27 -> R 13.5, the old {@code lateral <= forward} check passed it).
 */
class JunctionMergeGeometryTest {

    private static final int MIN_RADIUS = 15;
    private static final int MAX_VERTICAL = 4;

    // ---- PARALLEL (S-curve) merges -------------------------------------------------------------

    @Test
    void parallelMergeTooCloseIsRejected() {
        // Seed run 2026-06-05: head 6 (EAST, z=2316) into a parallel EAST line at z=2289. forward 27,
        // lateral 27 -> S radius (27^2+27^2)/(4*27) = 13.5 < 15. Illegal; the old single-turn check passed it.
        BlockPos tip = new BlockPos(4637, 66, 2316);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(4664, 70, 2289), tip, Direction.EAST, true, MIN_RADIUS, 8));
    }

    @Test
    void parallelMergeFurtherDownIsAccepted() {
        // Same line, three blocks further along: forward 30, lateral 27 -> R = (900+729)/108 = 15.08 >= 15.
        BlockPos tip = new BlockPos(4637, 66, 2316);
        assertTrue(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(4667, 70, 2289), tip, Direction.EAST, true, MIN_RADIUS, 8));
    }

    @Test
    void parallelGentleShiftIsAccepted() {
        BlockPos tip = new BlockPos(0, 70, 0);
        // lateral 5 over forward 40 -> R ~ 81, a gentle S.
        assertTrue(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-40, 70, 5), tip, Direction.WEST, true, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void parallelAlreadyAlignedIsAccepted() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertTrue(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-20, 70, 0), tip, Direction.WEST, true, MIN_RADIUS, MAX_VERTICAL));
    }

    // ---- PERPENDICULAR (single-turn) branches --------------------------------------------------

    @Test
    void perpendicularHairpinIsRejected() {
        // Seed 4716444102807706045: head 9 (WEST) into head 11's track at -2342,115,-124 from tip
        // -2343,115,-129 - one block BEHIND and five to the side, a radius-~3 hairpin.
        BlockPos tip = new BlockPos(-2343, 115, -129);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-2342, 115, -124), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void perpendicularPointDownTheTrackIsAccepted() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertTrue(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-16, 70, 5), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void perpendicularPointBehindIsRejected() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(20, 70, 5), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void perpendicularCloserThanMinRadiusIsRejected() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-10, 70, 3), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void perpendicularSharperThanNinetyIsRejected() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-16, 70, 20), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void verticalWindowIsEnforced() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-16, 80, 5), tip, Direction.WEST, false, MIN_RADIUS, MAX_VERTICAL));
    }

    @Test
    void headingDeterminesForwardAndLateral() {
        BlockPos tip = new BlockPos(0, 70, 0);
        assertTrue(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(5, 70, -16), tip, Direction.NORTH, false, MIN_RADIUS, MAX_VERTICAL));
        assertFalse(JunctionMergeGeometry.canConnectLegally(
                new BlockPos(-16, 70, 5), tip, Direction.NORTH, false, MIN_RADIUS, MAX_VERTICAL));
    }
}
