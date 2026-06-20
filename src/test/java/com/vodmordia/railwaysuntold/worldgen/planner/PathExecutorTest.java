package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gates the placement-time connection invariants in {@link PathExecutor#executeNextSegment} - the seam
 * where one planned segment hands off to the next as the head advances. Two derail risks live here:
 *
 * (c) {@link PathExecutor#isNearSegmentEnd} treats a segment as complete at XZ-distance <=1 <em>regardless
 * of Y</em>. A vertical gap at a segment boundary is therefore <em>masked</em> at this layer - the segment
 * is advanced past as if done.
 *
 * (b) But the next segment's decision stays <em>planner-anchored</em> (a bezier emits the planner's start,
 * not the live head), so that masked Y gap surfaces downstream as a drift-halt in
 * BezierTrackPlacementExecutor rather than being silently bridged into bad track. These gates pin both
 * halves: if a refactor either makes {@code isNearSegmentEnd} consider Y, or makes a segment start from
 * the live head, one of these fails - a conscious decision, not a silent derail.
 */
class PathExecutorTest {

    /** Minimal {@link PathExecutionState} over a fixed segment list. */
    private static final class StubState implements PathExecutionState {
        private final List<PathSegment> segments;
        int index = 0;

        StubState(PathSegment... segs) {
            this.segments = List.of(segs);
        }

        @Override
        public PathSegment getCurrentSegment() {
            return index < segments.size() ? segments.get(index) : null;
        }

        @Override
        public void advanceToNextSegment() {
            index++;
        }
    }

    // --- (c) isNearSegmentEnd: the deliberate Y-tolerance ---

    @Test
    void nearSegmentEndIgnoresYEntirely() {
        // Same XZ, 100-block vertical gap -> still "near": the masking PL2 flags. The head being far
        // off the planned Y does NOT keep the segment alive here; that's caught downstream instead.
        assertTrue(PathExecutor.isNearSegmentEnd(new BlockPos(10, 0, 10), new BlockPos(10, 100, 10)));
    }

    @Test
    void nearSegmentEndTrueWithinOneBlockXZ() {
        assertTrue(PathExecutor.isNearSegmentEnd(new BlockPos(10, 72, 10), new BlockPos(11, 72, 10)));
    }

    @Test
    void nearSegmentEndFalseBeyondOneBlockXZ() {
        assertFalse(PathExecutor.isNearSegmentEnd(new BlockPos(10, 72, 10), new BlockPos(12, 72, 10)));
        assertFalse(PathExecutor.isNearSegmentEnd(new BlockPos(10, 72, 10), new BlockPos(11, 72, 11)));
    }

    // --- isEndpointBehind: forward-progress projection along the travel direction ---

    @Test
    void endpointBehindWhenNoForwardProgress() {
        assertTrue(PathExecutor.isEndpointBehind(new BlockPos(10, 72, 0), new BlockPos(10, 72, 0), Direction.EAST));
        assertTrue(PathExecutor.isEndpointBehind(new BlockPos(10, 72, 0), new BlockPos(8, 72, 0), Direction.EAST));
    }

    @Test
    void endpointAheadWhenForwardProgress() {
        assertFalse(PathExecutor.isEndpointBehind(new BlockPos(10, 72, 0), new BlockPos(20, 72, 0), Direction.EAST));
    }

    // --- (b) the full chain: a masked Y gap, then a planner-anchored next segment ---

    @Test
    void yGapAtBoundaryAdvancesYetNextSegmentStaysPlannerAnchored() {
        PathSegment seg1 = new BezierSegment(
                new BlockPos(0, 80, 0), Direction.EAST, new BlockPos(32, 80, 0), Direction.EAST);
        PathSegment seg2 = new BezierSegment(
                new BlockPos(32, 80, 0), Direction.EAST, new BlockPos(96, 80, 0), Direction.EAST);
        StubState state = new StubState(seg1, seg2);

        // Head one block short of seg1's endpoint in XZ (so isNearSegmentEnd fires, not isEndpointBehind)
        // but 8 blocks below in Y. The segment is treated as complete despite the vertical gap...
        PlacementDecision d = PathExecutor.executeNextSegment(
                state, new BlockPos(31, 72, 0), Direction.EAST, null, null);

        assertEquals(1, state.index, "the Y gap must not stop the head advancing past seg1");
        assertNotNull(d, "seg2 should produce a decision");
        // ...and seg2's decision keeps the PLANNER start (32,80,0), NOT the head's (31,72,0). The masked
        // gap therefore reaches the bezier executor's drift guard rather than becoming silent bad track.
        assertEquals(new BlockPos(32, 80, 0), d.getStart(),
                "next segment must stay planner-anchored so the drift guard can catch the gap");
    }
}
