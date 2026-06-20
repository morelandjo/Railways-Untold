package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.worldgen.village.PlacedStationTracker.StationBounds;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link PlacedStationTracker} - registration (with corner normalization) and
 * {@code getStationIntersectingTrajectory}, the station-vs-track-line geometry the constraint pass's
 * StationAvoidanceStrategy relied on. Pure (no world). The buffered box expands the station by 5 blocks
 * horizontally and by 10 below / 20 above vertically (PlacementConstants).
 */
class PlacedStationTrackerTest {

    private static PlacedStationTracker withStation(BlockPos min, BlockPos max) {
        PlacedStationTracker tracker = new PlacedStationTracker();
        tracker.registerStation(min, max);
        return tracker;
    }

    @Test
    void anEmptyTrackerNeverIntersects() {
        assertNull(new PlacedStationTracker().getStationIntersectingTrajectory(0, 0, 100, 0, 64));
    }

    @Test
    void aTrajectoryCrossingTheStationBoxAtTrackHeightHitsIt() {
        // Station box X[100,110] Y[60,70] Z[100,110]; buffered to X[95,115] Y[50,90] Z[95,115].
        PlacedStationTracker tracker = withStation(new BlockPos(100, 60, 100), new BlockPos(110, 70, 110));

        // A west->east line at z=105, y=65 runs straight through the box.
        StationBounds hit = tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 65);
        assertNotNull(hit, "the line crosses the buffered box at track height");
        assertEquals(new BlockPos(100, 60, 100), hit.minCorner);
    }

    @Test
    void aTrajectoryMissesWhenItIsOutsideTheBoxLaterallyOrVertically() {
        PlacedStationTracker tracker = withStation(new BlockPos(100, 60, 100), new BlockPos(110, 70, 110));

        assertNull(tracker.getStationIntersectingTrajectory(0, 0, 200, 0, 65),
                "z=0 line is well outside the box's Z[95,115] footprint");
        assertNull(tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 200),
                "y=200 is above the buffered top (90)");
        assertNull(tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 40),
                "y=40 is below the buffered bottom (50)");
    }

    @Test
    void registrationNormalizesSwappedCorners() {
        // Pass the corners reversed; the tracker should still bound the same box.
        PlacedStationTracker tracker = withStation(new BlockPos(110, 70, 110), new BlockPos(100, 60, 100));

        StationBounds hit = tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 65);
        assertNotNull(hit, "swapped corners still describe the same box");
        assertEquals(new BlockPos(100, 60, 100), hit.minCorner, "min corner is the true minimum");
        assertEquals(new BlockPos(110, 70, 110), hit.maxCorner, "max corner is the true maximum");
    }

    @Test
    void clearAllRemovesEveryStation() {
        PlacedStationTracker tracker = withStation(new BlockPos(100, 60, 100), new BlockPos(110, 70, 110));
        assertNotNull(tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 65));
        tracker.clearAll();
        assertNull(tracker.getStationIntersectingTrajectory(0, 105, 200, 105, 65));
    }

    @Test
    void theBufferLetsANearMissWithinFiveBlocksStillCount() {
        PlacedStationTracker tracker = withStation(new BlockPos(100, 60, 100), new BlockPos(110, 70, 110));
        // z=114 is outside the raw box (max 110) but inside the +5 buffer (115).
        assertNotNull(tracker.getStationIntersectingTrajectory(0, 114, 200, 114, 65),
                "a line 4 blocks past the edge is caught by the 5-block buffer");
        // z=120 is beyond the buffer.
        assertNull(tracker.getStationIntersectingTrajectory(0, 120, 200, 120, 65),
                "10 blocks past the edge is beyond the buffer");
    }
}
