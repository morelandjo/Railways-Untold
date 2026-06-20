package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link TrajectoryIntersection#lineIntersectsBox} - the 2D (XZ) segment-vs-box slab test that
 * underpins station/obstacle avoidance (e.g. PlacedStationTracker). Note the test is on the *segment*
 * (t ∈ [0,1]): a line whose infinite extension would hit the box but whose endpoints stop short does NOT
 * intersect. Pure.
 */
class TrajectoryIntersectionTest {

    // Box footprint X[10,20] Z[10,20] (Y unused by this 2D test).
    private static final BoundingBox BOX = new BoundingBox(10, 0, 10, 20, 0, 20);

    @Test
    void aSegmentCrossingTheFootprintIntersects() {
        assertTrue(TrajectoryIntersection.lineIntersectsBox(0, 15, 30, 15, BOX), "horizontal sweep through z=15");
        assertTrue(TrajectoryIntersection.lineIntersectsBox(15, 0, 15, 30, BOX), "vertical sweep through x=15");
        assertTrue(TrajectoryIntersection.lineIntersectsBox(0, 0, 30, 30, BOX), "diagonal through the box");
    }

    @Test
    void aSegmentRunningOutsideTheFootprintMisses() {
        assertFalse(TrajectoryIntersection.lineIntersectsBox(0, 5, 30, 5, BOX), "z=5 line never enters Z[10,20]");
        assertFalse(TrajectoryIntersection.lineIntersectsBox(5, 0, 5, 30, BOX), "x=5 line never enters X[10,20]");
    }

    @Test
    void aSegmentThatStopsShortOfTheBoxDoesNotIntersectEvenIfItsLineWould() {
        // The ray from (0,0) toward (5,5) points at the box but the segment ends at (5,5), before X=10.
        assertFalse(TrajectoryIntersection.lineIntersectsBox(0, 0, 5, 5, BOX),
                "segment ends before reaching the box");
        // Extending the same segment to (30,30) does reach it.
        assertTrue(TrajectoryIntersection.lineIntersectsBox(0, 0, 30, 30, BOX));
    }

    @Test
    void aSegmentStartingInsideTheBoxIntersects() {
        assertTrue(TrajectoryIntersection.lineIntersectsBox(15, 15, 100, 100, BOX), "starts inside -> intersects");
    }
}
