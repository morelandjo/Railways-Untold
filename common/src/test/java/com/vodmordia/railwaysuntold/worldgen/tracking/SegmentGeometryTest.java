package com.vodmordia.railwaysuntold.worldgen.tracking;

import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectionType;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.SegmentAABB;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the pure segment geometry in {@code worldgen.tracking}: the endpoint-proximity test used to
 * reject pseudo-crossings ({@link SegmentGeometryUtil}) and the pre-computed segment bounding box
 * ({@link SegmentAABB}). These underpin the crossing/conflict detection already characterized in the
 * analyzer pass; both are world-free.
 */
class SegmentGeometryTest {

    @Test
    void isNear3DAppliesSeparateHorizontalAndVerticalTolerances() {
        BlockPos origin = new BlockPos(0, 0, 0);
        // On every axis boundary (h=5, v=3) - inclusive, so still "near".
        assertTrue(SegmentGeometryUtil.isNear3D(origin, new BlockPos(5, 3, 5), 5, 3), "axis boundaries are inclusive");
        // One block past the horizontal tolerance in X.
        assertFalse(SegmentGeometryUtil.isNear3D(origin, new BlockPos(6, 0, 0), 5, 3), "|dx|=6 exceeds h=5");
        // One block past the vertical tolerance in Y (horizontal still fine).
        assertFalse(SegmentGeometryUtil.isNear3D(origin, new BlockPos(0, 4, 0), 5, 3), "|dy|=4 exceeds v=3");
    }

    @Test
    void sharesEndpointMatchesAnyOfTheFourEndpointPairingsWithinTolerance() {
        BlockPos a1 = new BlockPos(0, 0, 0);
        BlockPos a2 = new BlockPos(100, 0, 0);

        // a1 is within the 5h/3v tolerance of b2 - they share an endpoint.
        assertTrue(SegmentGeometryUtil.sharesEndpoint(
                a1, a2, new BlockPos(50, 0, 50), new BlockPos(3, 2, 4)), "a1 ~ b2 counts as shared");

        // An exact coincident endpoint (a2 == b1) is shared.
        assertTrue(SegmentGeometryUtil.sharesEndpoint(
                a1, a2, new BlockPos(100, 0, 0), new BlockPos(200, 0, 0)), "exact coincidence is shared");

        // All four pairings far apart - no shared endpoint.
        assertFalse(SegmentGeometryUtil.sharesEndpoint(
                a1, a2, new BlockPos(50, 0, 50), new BlockPos(60, 0, 60)), "all endpoints far -> not shared");
    }

    @Test
    void segmentAabbFromStraightSpansTheTwoEndpoints() {
        SegmentAABB aabb = SegmentAABB.compute(new BlockPos(0, 64, 0), new BlockPos(10, 60, 20), null);
        assertEquals(0, aabb.minX());
        assertEquals(10, aabb.maxX());
        assertEquals(60, aabb.minY());
        assertEquals(64, aabb.maxY());
        assertEquals(0, aabb.minZ());
        assertEquals(20, aabb.maxZ());
    }

    @Test
    void segmentAabbFromCurvePositionsBoundsThemAndIgnoresStartAndEnd() {
        List<BlockPos> curve = List.of(new BlockPos(5, 70, 5), new BlockPos(2, 60, 8), new BlockPos(9, 65, 1));
        // Deliberately absurd start/end: if compute used them, the box would blow up - it must not.
        SegmentAABB aabb = SegmentAABB.compute(
                new BlockPos(1000, 1000, 1000), new BlockPos(-1000, -1000, -1000), curve);
        assertEquals(2, aabb.minX());
        assertEquals(9, aabb.maxX());
        assertEquals(60, aabb.minY());
        assertEquals(70, aabb.maxY());
        assertEquals(1, aabb.minZ());
        assertEquals(8, aabb.maxZ());
    }

    @Test
    void emptyCurvePositionsFallBackToTheStraightEndpoints() {
        SegmentAABB aabb = SegmentAABB.compute(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), List.of());
        assertEquals(0, aabb.minX());
        assertEquals(4, aabb.maxX());
    }

    @Test
    void connectedSegmentReportsCurveGeometryAndBoundsItsCurve() {
        UUID head = new UUID(0L, 1L);
        List<BlockPos> curve = List.of(new BlockPos(5, 70, 5), new BlockPos(2, 60, 8));

        ConnectedSegment straight = new ConnectedSegment(
                new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), ConnectionType.STRAIGHT, null, head);
        assertFalse(straight.hasCurveGeometry(), "null curve positions -> not a curve");

        ConnectedSegment empty = new ConnectedSegment(
                new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), ConnectionType.STRAIGHT, List.of(), head);
        assertFalse(empty.hasCurveGeometry(), "empty curve positions -> not a curve");

        ConnectedSegment curved = new ConnectedSegment(
                new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), ConnectionType.BEZIER, curve, head);
        assertTrue(curved.hasCurveGeometry(), "non-empty curve positions -> a curve");
        // Its precomputed AABB bounds the curve, not the straight endpoints.
        assertEquals(2, curved.aabb.minX());
        assertEquals(60, curved.aabb.minY());
    }
}
