package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes the pure per-tick throttle of {@link DeferredProcessingManager}, the abstract engine shared
 * by all five deferred managers (block entities, connections, tunnels, schematics, terrain clearing). A
 * regression here would mis-fire or starve every deferred queue. Driven through a test subclass with a fixed
 * interval; no world or mocks needed. (The world-skip predicates are pinned separately in
 * DeferredProcessingWorldFilterTest, which is 1.21.1-only - see deferred-refactor.md.)
 */
class DeferredProcessingThrottleTest {

    /** Concrete probe exposing the protected throttle with a fixed processing interval. */
    private static final class Probe extends DeferredProcessingManager {
        private final int interval;

        Probe(int interval) {
            this.interval = interval;
        }

        @Override
        protected int getProcessIntervalTicks() {
            return interval;
        }

        boolean process(long tick) {
            return shouldProcess(tick);
        }

        void reset() {
            resetProcessTick();
        }
    }

    @Test
    void processesAtMostOncePerIntervalSinceTheLastProcess() {
        Probe probe = new Probe(10);
        // lastProcessTick starts at 0, so the first process needs currentTick >= interval.
        assertFalse(probe.process(5), "tick 5 is < interval(10) since tick 0 -> skip");
        assertTrue(probe.process(10), "tick 10 reaches the interval -> process");
        assertFalse(probe.process(15), "tick 15 is only 5 since the last process -> skip");
        assertTrue(probe.process(20), "tick 20 is 10 since the last process -> process");
    }

    @Test
    void processesAtMostOncePerTick() {
        Probe probe = new Probe(10);
        assertTrue(probe.process(100), "first eligible tick processes");
        assertFalse(probe.process(100), "a second call on the same tick does not process again");
    }

    @Test
    void resetProcessTickForcesTheNextCallToProcess() {
        Probe probe = new Probe(10);
        assertTrue(probe.process(100));
        assertFalse(probe.process(105), "only 5 ticks elapsed -> would normally skip");
        probe.reset();
        assertTrue(probe.process(105), "after reset the next call processes regardless of elapsed ticks");
    }
}
