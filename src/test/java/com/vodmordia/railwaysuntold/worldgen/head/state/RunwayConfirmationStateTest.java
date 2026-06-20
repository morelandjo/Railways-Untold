package com.vodmordia.railwaysuntold.worldgen.head.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the observable states of {@link RunwayConfirmationState}: a fresh state confirms nothing, a
 * null placement result is never "placed", restore reinstates the flags, and clear wipes everything. (The
 * success-derivation `stationPlaced = result.success` for a real result is exercised by the placement
 * gametests, which actually place a station; here we pin the defensive null path and the snapshot states.)
 */
class RunwayConfirmationStateTest {

    @Test
    void aFreshStateHasConfirmedNothing() {
        RunwayConfirmationState state = new RunwayConfirmationState();
        assertFalse(state.isVillageConfirmed());
        assertFalse(state.hasConfirmedRunway());
        assertFalse(state.isStationPlaced());
        assertNull(state.getConfirmedRunway());
        assertNull(state.getPlacedStation());
        assertNull(state.getSelectedStation());
    }

    @Test
    void aNullPlacementResultIsNeverConsideredPlaced() {
        RunwayConfirmationState state = new RunwayConfirmationState();
        state.setPlacedStation(null);
        assertFalse(state.isStationPlaced(), "no result -> not placed");
        assertNull(state.getPlacedStation());
    }

    @Test
    void restoreReinstatesTheConfirmationFlags() {
        RunwayConfirmationState state = new RunwayConfirmationState();
        state.restore(true, true, true, null);
        assertTrue(state.isVillageConfirmed());
        assertTrue(state.hasConfirmedRunway());
        assertTrue(state.isStationPlaced());
    }

    @Test
    void clearWipesEveryConfirmation() {
        RunwayConfirmationState state = new RunwayConfirmationState();
        state.restore(true, true, true, null);
        state.clear();
        assertFalse(state.isVillageConfirmed());
        assertFalse(state.hasConfirmedRunway());
        assertFalse(state.isStationPlaced());
        assertNull(state.getConfirmedRunway());
    }
}
