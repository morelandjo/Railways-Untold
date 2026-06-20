package com.vodmordia.railwaysuntold.worldgen.placement;

import com.vodmordia.railwaysuntold.worldgen.placement.TrackExpansionOrchestrator.StallAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the stall-watchdog escalation ({@link TrackExpansionOrchestrator#stallAction}): below the
 * no-progress threshold nothing happens; at/over it the head escalates KICK -> ABANDON_AND_KICK ->
 * RETIRE by how many times it's already been kicked. This is the safety net for a head gone silent
 * (the head-2 village-skirt idle: 22 min with no progress, no failure, never chunk-deferred).
 */
class StallWatchdogTest {

    private static final long T = 120_000;

    @Test
    void belowThresholdDoesNothing() {
        assertEquals(StallAction.NONE, TrackExpansionOrchestrator.stallAction(T - 1, 0, T));
        assertEquals(StallAction.NONE, TrackExpansionOrchestrator.stallAction(0, 9, T));
    }

    @Test
    void firstStallKicks() {
        assertEquals(StallAction.KICK, TrackExpansionOrchestrator.stallAction(T, 0, T));
    }

    @Test
    void secondStallAbandonsAndKicks() {
        assertEquals(StallAction.ABANDON_AND_KICK, TrackExpansionOrchestrator.stallAction(T + 5_000, 1, T));
    }

    @Test
    void thirdAndBeyondRetire() {
        assertEquals(StallAction.RETIRE, TrackExpansionOrchestrator.stallAction(T, 2, T));
        assertEquals(StallAction.RETIRE, TrackExpansionOrchestrator.stallAction(10 * T, 7, T));
    }
}
