package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.worldgen.village.AttemptedVillageTracker.AttemptReason;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link AttemptedVillageTracker} - the village->reason map that keeps the network planner from
 * re-targeting a village that has already been served or ruled out. Pure (no world, no config).
 */
class AttemptedVillageTrackerTest {

    private static final UUID VILLAGE = new UUID(0L, 0xA);

    @Test
    void aMarkedVillageReadsBackAsAttempted() {
        AttemptedVillageTracker tracker = new AttemptedVillageTracker();
        assertFalse(tracker.isVillageAttempted(VILLAGE), "unknown village -> not attempted");

        tracker.markVillageAttempted(VILLAGE, AttemptReason.STATION_PLACED);
        assertTrue(tracker.isVillageAttempted(VILLAGE));
    }

    @Test
    void nullVillageOrReasonIsIgnored() {
        AttemptedVillageTracker tracker = new AttemptedVillageTracker();
        tracker.markVillageAttempted(null, AttemptReason.DOES_NOT_EXIST);
        tracker.markVillageAttempted(VILLAGE, null);
        assertFalse(tracker.isVillageAttempted(VILLAGE), "neither malformed mark records anything");
    }

    @Test
    void clearAllForgetsEveryAttempt() {
        AttemptedVillageTracker tracker = new AttemptedVillageTracker();
        tracker.markVillageAttempted(VILLAGE, AttemptReason.NO_VALID_APPROACH);
        tracker.clearAll();
        assertFalse(tracker.isVillageAttempted(VILLAGE));
    }
}
