package com.vodmordia.railwaysuntold.worldgen.placement.analyzer;

import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.SegmentAABB;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the pure geometry primitives behind {@link CrossingDetectionService}: linear Y
 * interpolation, segment sampling, point-to-infinite-line distance, and the buffered AABB overlap test.
 * The composing entry point {@code checkSegmentIntersection} is config/world-bound (covered by the
 * placement gametests); these primitives are world-free, so they are pinned directly.
 */
class CrossingDetectionServiceTest {

    @Test
    void interpolateYIsLinearAndPinsTheEndpoints() {
        BlockPos start = new BlockPos(0, 0, 0);
        BlockPos end = new BlockPos(0, 10, 0);
        assertEquals(0, CrossingDetectionService.interpolateY(start, end, 0.0), "t=0 is the start Y");
        assertEquals(10, CrossingDetectionService.interpolateY(start, end, 1.0), "t=1 is the end Y");
        assertEquals(5, CrossingDetectionService.interpolateY(start, end, 0.5), "t=0.5 is the midpoint");
        assertEquals(3, CrossingDetectionService.interpolateY(start, end, 0.3), "rounds to nearest block");
    }

    @Test
    void interpolateSegmentPositionsReturnsSampleCountPlusOnePointsSpanningTheEndpoints() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(10, 64, 0);
        List<BlockPos> pts = CrossingDetectionService.interpolateSegmentPositions(start, end, 10);

        assertEquals(11, pts.size(), "N samples produce N+1 points");
        assertEquals(start, pts.get(0), "the first sample is the start");
        assertEquals(end, pts.get(10), "the last sample is the end");
        assertEquals(new BlockPos(5, 64, 0), pts.get(5), "the midpoint sample interpolates evenly");
    }

    @Test
    void isPointNearInfiniteLineMeasuresAcrossTheLineAxisOnly() {
        BlockPos line = new BlockPos(100, 64, 0);

        // A N/S line runs along Z, so nearness is the X gap (Z is ignored).
        assertTrue(CrossingDetectionService.isPointNearInfiniteLine(
                new BlockPos(102, 64, 9999), line, Direction.NORTH, 3), "|dx|=2 <= 3");
        assertFalse(CrossingDetectionService.isPointNearInfiniteLine(
                new BlockPos(105, 64, 0), line, Direction.NORTH, 3), "|dx|=5 > 3");

        // An E/W line runs along X, so nearness is the Z gap (X is ignored).
        BlockPos ewLine = new BlockPos(0, 64, 100);
        assertTrue(CrossingDetectionService.isPointNearInfiniteLine(
                new BlockPos(9999, 64, 102), ewLine, Direction.EAST, 3), "|dz|=2 <= 3");

        // A vertical direction is not a horizontal line - never "near".
        assertFalse(CrossingDetectionService.isPointNearInfiniteLine(
                new BlockPos(100, 64, 0), line, Direction.UP, 100), "vertical direction is never near");
    }

    @Test
    void boundingBoxesOverlapDetectsIntersectionAndSeparation() {
        BlockPos start1 = new BlockPos(0, 64, 0);
        BlockPos end1 = new BlockPos(10, 64, 0); // box1 (no buffer): X[0,10] Y[64,64] Z[0,0]

        SegmentAABB overlapping = new SegmentAABB(5, 5, 64, 64, -5, 5);
        assertTrue(CrossingDetectionService.boundingBoxesOverlap(start1, end1, overlapping, 0, 0),
                "an AABB inside box1's extent overlaps");

        SegmentAABB faraway = new SegmentAABB(100, 110, 64, 64, 0, 0);
        assertFalse(CrossingDetectionService.boundingBoxesOverlap(start1, end1, faraway, 0, 0),
                "an AABB beyond box1's X extent does not overlap");
    }

    @Test
    void boundingBoxesOverlapAppliesTheBufferExpansion() {
        BlockPos start1 = new BlockPos(0, 64, 0);
        BlockPos end1 = new BlockPos(10, 64, 0);
        // An AABB two blocks past the X edge: out of reach with no buffer, in reach once the buffer grows.
        SegmentAABB justOutside = new SegmentAABB(12, 12, 64, 64, 0, 0);

        assertFalse(CrossingDetectionService.boundingBoxesOverlap(start1, end1, justOutside, 0, 0),
                "no buffer: the gap of 2 keeps them apart");
        assertTrue(CrossingDetectionService.boundingBoxesOverlap(start1, end1, justOutside, 3, 0),
                "a 3-block horizontal buffer bridges the gap of 2");
    }

    // --- checkSegmentIntersection: the composing detector (pure given a ConnectedSegment; config is a
    //     static defaults singleton, no bootstrap) ---

    /** A straight (no-curve) existing segment between two points. */
    private static ConnectedSegment straight(BlockPos start, BlockPos end) {
        return new ConnectedSegment(start, end, ConnectionType.STRAIGHT, List.of(), UUID.randomUUID());
    }

    @Test
    void aProposedSegmentSharingAnEndpointIsNotACrossing() {
        // Existing runs N-S through x=0; the proposed segment starts exactly at the existing end.
        ConnectedSegment existing = straight(new BlockPos(0, 64, -50), new BlockPos(0, 64, 50));
        var info = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(0, 64, 50), new BlockPos(50, 64, 50), Direction.EAST, existing);
        assertNull(info, "a shared endpoint is a connection, not a crossing");
    }

    @Test
    void aDistantSegmentIsRejectedByTheBoundingBoxPrecheck() {
        ConnectedSegment existing = straight(new BlockPos(0, 64, -50), new BlockPos(0, 64, 50));
        var info = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(1000, 64, 1000), new BlockPos(1100, 64, 1000), Direction.EAST, existing);
        assertNull(info, "a far-away segment never reaches the intersection scan");
    }

    @Test
    void aPerpendicularCrossingIsDetectedAtTheIntersectionPoint() {
        // Existing N-S at x=0; proposed E-W at z=0 -> they cross at the origin.
        ConnectedSegment existing = straight(new BlockPos(0, 64, -50), new BlockPos(0, 64, 50));
        var info = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(-50, 64, 0), new BlockPos(50, 64, 0), Direction.EAST, existing);
        assertNotNull(info, "two perpendicular segments crossing should be detected");
        assertTrue(info.isPerpendicular(), "E-W vs N-S is perpendicular");
        assertEquals(0, info.crossingPoint().getX(), "crossing is at x=0");
        assertEquals(0, info.crossingPoint().getZ(), "crossing is at z=0");
    }

    @Test
    void aSegmentBranchingNearTheExistingStartIsNotTreatedAsACrossing() {
        // Proposed runs N-S parallel to the existing line and begins right next to its start -
        // that is a branch off the existing track, deliberately excluded from crossing resolution.
        ConnectedSegment existing = straight(new BlockPos(0, 64, -50), new BlockPos(0, 64, 50));
        var info = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(0, 64, -48), new BlockPos(0, 64, -100), Direction.NORTH, existing);
        assertNull(info, "a nearby parallel branch off the existing start is not a crossing");
    }

    @Test
    void theBranchExemptionCanBeDisabledForCrossingDetection() {
        // A genuine perpendicular crossing whose proposed start sits within the branch-proximity window of
        // the existing track's start (|dx|=2, ~30 blocks away). With the exemption applied it is read as a
        // junction and ignored; with it disabled the real crossing at (2,0) is reported. The placed-track
        // crossing detector disables it for mid-route segments - the gap that left the resolver inert
        // (0 of 66 crossings resolved) so routes collided at placement instead of being lifted over.
        ConnectedSegment existing = straight(new BlockPos(0, 64, 0), new BlockPos(60, 64, 0));

        var exempt = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(2, 64, -30), new BlockPos(2, 64, 30), Direction.SOUTH, existing, true);
        assertNull(exempt, "with the exemption applied, the near-origin crossing is read as a junction");

        var detected = CrossingDetectionService.checkSegmentIntersection(
                new BlockPos(2, 64, -30), new BlockPos(2, 64, 30), Direction.SOUTH, existing, false);
        assertNotNull(detected, "with the exemption disabled, the perpendicular crossing is detected");
        assertEquals(0, detected.crossingPoint().getZ(), "crossing is at the existing line z=0");
    }
}
