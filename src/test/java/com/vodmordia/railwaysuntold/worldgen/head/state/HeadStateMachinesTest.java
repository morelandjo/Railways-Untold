package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.worldgen.head.state.CurveTrackingState.CurveDirection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes the small pure head state machines: {@link CurveTrackingState}, {@link PauseState},
 * {@link EventState}, {@link ExplorationState}. All world/config-free.
 */
class HeadStateMachinesTest {

    @Nested
    class CurveDirectionEnum {
        @Test
        void valueRoundTripsAndUnknownCodesCollapseToNone() {
            assertEquals(0, CurveDirection.NONE.getValue());
            assertEquals(1, CurveDirection.LEFT.getValue());
            assertEquals(2, CurveDirection.RIGHT.getValue());

            assertSame(CurveDirection.NONE, CurveDirection.fromValue(0));
            assertSame(CurveDirection.LEFT, CurveDirection.fromValue(1));
            assertSame(CurveDirection.RIGHT, CurveDirection.fromValue(2));
            assertSame(CurveDirection.NONE, CurveDirection.fromValue(99), "unknown code -> NONE");
            assertSame(CurveDirection.NONE, CurveDirection.fromValue(-1), "negative code -> NONE");

            for (CurveDirection d : CurveDirection.values()) {
                assertSame(d, CurveDirection.fromValue(d.getValue()), "round trip " + d);
            }
        }

        @Test
        void fromBooleanMapsLeftAndRight() {
            assertSame(CurveDirection.LEFT, CurveDirection.fromBoolean(true));
            assertSame(CurveDirection.RIGHT, CurveDirection.fromBoolean(false));
        }
    }

    @Nested
    class CurveTracking {
        @Test
        void recordingATurnShiftsPreviousIntoPrior() {
            CurveTrackingState state = new CurveTrackingState();
            assertSame(CurveDirection.NONE, state.getPreviousCurveDirection());
            assertSame(CurveDirection.NONE, state.getPriorCurveDirection());

            state.recordCurveTurn(true); // left
            assertSame(CurveDirection.LEFT, state.getPreviousCurveDirection());
            assertSame(CurveDirection.NONE, state.getPriorCurveDirection(), "no earlier turn yet");

            state.recordCurveTurn(false); // right; the left becomes the prior
            assertSame(CurveDirection.RIGHT, state.getPreviousCurveDirection());
            assertSame(CurveDirection.LEFT, state.getPriorCurveDirection());
        }

        @Test
        void straightCountersAccumulateAndResetIndependently() {
            CurveTrackingState state = new CurveTrackingState();
            state.incrementBlocksSinceLastCurve(10);
            state.incrementBlocksSinceLastCurve(5);
            assertEquals(15, state.getBlocksSinceLastCurve());
            state.resetBlocksSinceLastCurve();
            assertEquals(0, state.getBlocksSinceLastCurve());

            assertFalse(state.wasLastPlacementEmergencyCurve());
            state.setLastPlacementWasEmergencyCurve(true);
            assertTrue(state.wasLastPlacementEmergencyCurve());
        }
    }

    @Nested
    class Pause {
        @Test
        void pauseAndResumeFlipTheFlagAndStamp() {
            PauseState state = new PauseState();
            assertFalse(state.isPaused(), "a head starts running");
            assertEquals(0, state.getPausedAtTime());

            state.pause();
            assertTrue(state.isPaused());
            assertTrue(state.getPausedAtTime() > 0, "pausing stamps the wall-clock time");

            state.resume();
            assertFalse(state.isPaused());
            assertEquals(0, state.getPausedAtTime());
        }

        @Test
        void restoreReinstatesTheStampedState() {
            PauseState state = new PauseState();
            state.restore(true, 123456L);
            assertTrue(state.isPaused());
            assertEquals(123456L, state.getPausedAtTime());
        }
    }

    @Nested
    class Event {
        @Test
        void eventDistanceAccumulatesResetsAndRestores() {
            EventState state = new EventState();
            assertEquals(0, state.getBlocksSinceLastEvent());
            state.incrementEventDistance(10);
            state.incrementEventDistance(5);
            assertEquals(15, state.getBlocksSinceLastEvent());
            state.resetEventCounter();
            assertEquals(0, state.getBlocksSinceLastEvent());
            state.restore(42);
            assertEquals(42, state.getBlocksSinceLastEvent());
        }
    }

    @Nested
    class Exploration {
        @Test
        void targetLifecycle() {
            ExplorationState state = new ExplorationState();
            assertFalse(state.hasExplorationTarget());
            assertNull(state.getExplorationTarget());

            BlockPos target = new BlockPos(7, 64, 9);
            state.setExplorationTarget(target);
            assertTrue(state.hasExplorationTarget());
            assertEquals(target, state.getExplorationTarget());

            state.clear();
            assertFalse(state.hasExplorationTarget());
            assertNull(state.getExplorationTarget());
        }
    }
}
