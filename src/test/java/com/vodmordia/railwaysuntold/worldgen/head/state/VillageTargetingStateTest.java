package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.worldgen.village.VillageLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link VillageTargetingState} - the most-mocked head state across the sweep (`getVillageState()`
 * .hasTargetVillage / isExploring / getStationTarget / ...). Pure (no config, no world). Pins the target
 * lifecycle, the village-vs-exploration precedence, the route-target priority chain, circling detection, and
 * the load-bearing quirk that the defer count survives a target clear.
 */
class VillageTargetingStateTest {

    private static final java.util.UUID VID = new java.util.UUID(0L, 1L);

    @Test
    void aFreshStateHasNoTargetAndIsNotExploring() {
        VillageTargetingState state = new VillageTargetingState();
        assertFalse(state.hasTargetVillage());
        assertFalse(state.isExploring());
    }

    @Test
    void setTargetFlattensTheCenterToTheTrackYAndRecordsTheManhattanDistance() {
        VillageTargetingState state = new VillageTargetingState();
        state.setTarget(VID, new BlockPos(100, 200, 100), new BlockPos(0, 64, 0));

        assertTrue(state.hasTargetVillage());
        // The center's Y is replaced with the track Y so distance is horizontal-only.
        assertEquals(new BlockPos(100, 64, 100), state.getTargetVillageCenter());
        assertEquals(200, state.getInitialDistanceToVillage(), "|100|+|0|+|100| at the shared Y");
    }

    @Test
    void aVillageTargetSuppressesExploring() {
        VillageTargetingState state = new VillageTargetingState();
        state.setExplorationTarget(new BlockPos(500, 64, 500));
        assertTrue(state.isExploring(), "an exploration target with no village -> exploring");

        state.setTarget(VID, new BlockPos(100, 64, 100), new BlockPos(0, 64, 0));
        assertFalse(state.isExploring(), "a village target wins over the exploration target");
    }

    @Test
    void routeTargetFollowsTheApproachWaypointThenTheApproachOffsetThenTheCenter() {
        VillageTargetingState state = new VillageTargetingState();
        // No village: the route target is the exploration target.
        state.setExplorationTarget(new BlockPos(7, 64, 9));
        assertEquals(new BlockPos(7, 64, 9), state.getRouteTarget());

        state.setTarget(VID, new BlockPos(100, 64, 100), new BlockPos(0, 64, 0));
        // Village, no waypoint, no approach direction -> the center itself.
        assertEquals(new BlockPos(100, 64, 100), state.getRouteTarget());

        // Village + approach direction, still no waypoint -> 140 blocks out along the approach direction.
        state.setPlannedApproachDirection(Direction.NORTH);
        assertEquals(new BlockPos(100, 64, 100).relative(Direction.NORTH, 140), state.getRouteTarget());

        // An approach waypoint takes top priority.
        state.setApproachWaypoint(new BlockPos(42, 64, 42));
        assertEquals(new BlockPos(42, 64, 42), state.getRouteTarget());
    }

    @Test
    void stationTargetFallsBackToAClockwiseOffsetFromTheCenter() {
        VillageTargetingState state = new VillageTargetingState();
        state.setTarget(VID, new BlockPos(100, 64, 100), new BlockPos(0, 64, 0));
        // No approach direction yet -> the station target is just the center.
        assertEquals(new BlockPos(100, 64, 100), state.getStationTarget());

        // With an approach direction and no precomputed layout, fall back to the clockwise offset:
        // side = NORTH.clockwise = EAST; offsetX = 1*48 − 0*24 = 48; offsetZ = 0*48 − (−1)*24 = 24.
        state.setPlannedApproachDirection(Direction.NORTH);
        assertEquals(new BlockPos(148, 64, 124), state.getStationTarget());
    }

    @Test
    void circlingIsDetectedAfterAFullThreeHundredSixtyDegreesOfTurning() {
        VillageTargetingState state = new VillageTargetingState();
        // Four 90° turns around the compass = 360°.
        state.recordDirectionChange(Direction.NORTH); // first call just seeds the last direction
        assertFalse(state.isCircling());
        state.recordDirectionChange(Direction.EAST);
        state.recordDirectionChange(Direction.SOUTH);
        state.recordDirectionChange(Direction.WEST);
        assertFalse(state.isCircling(), "270° is not yet circling");
        state.recordDirectionChange(Direction.NORTH);
        assertTrue(state.isCircling(), "360° -> circling");

        state.resetTurnTracking();
        assertFalse(state.isCircling(), "reset clears the accumulated turn");
    }

    @Test
    void anAboutFaceCountsAsAHundredAndEightyDegrees() {
        VillageTargetingState state = new VillageTargetingState();
        state.recordDirectionChange(Direction.NORTH);
        state.recordDirectionChange(Direction.SOUTH); // opposite = 180
        assertFalse(state.isCircling(), "one about-face is only 180°");
        state.recordDirectionChange(Direction.NORTH); // another 180 -> 360
        assertTrue(state.isCircling());
    }

    @Test
    void clearingTheTargetDeliberatelyPreservesTheDeferCount() {
        VillageTargetingState state = new VillageTargetingState();
        state.setTarget(VID, new BlockPos(100, 64, 100), new BlockPos(0, 64, 0));
        assertEquals(1, state.incrementDeferCount());
        assertEquals(2, state.incrementDeferCount());

        state.clearTarget();
        assertFalse(state.hasTargetVillage(), "the target is gone");
        assertEquals(2, state.getVillageDeferCount(),
                "but the defer count survives - periodic retargeting calls clear() every tick");

        state.resetDeferCount();
        assertEquals(0, state.getVillageDeferCount());
    }

    @Test
    void aCommittedVillageTargetSurvivesAVillageCacheEviction() {
        // Root cause of the re-targeting thrash: the per-tick validator abandoned a target whenever
        // VillageLocator's LRU cache evicted it (a transient "cache miss" misread as "village gone"). The
        // binding lives in the head's own state, independent of that cache - evicting the cache must not
        // touch a committed target. With the per-tick re-validation removed, this invariant is what keeps
        // a head locked to its village through the cache churn of chunk generation.
        VillageTargetingState state = new VillageTargetingState();
        state.setTarget(VID, new BlockPos(2534, 64, -1032), new BlockPos(2171, 64, -891));
        assertTrue(state.hasTargetVillage());

        VillageLocator.clearCache(); // simulate the LRU evicting the assigned village

        assertTrue(state.hasTargetVillage(), "a committed village must survive a cache eviction");
        assertEquals(VID, state.getTargetVillageId());
        assertEquals(new BlockPos(2534, 64, -1032), state.getTargetVillageCenter());
    }
}
