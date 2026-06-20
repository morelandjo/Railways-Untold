package com.vodmordia.railwaysuntold.util.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link SystemStateManager} - the lifecycle clear/shutdown coordinator. It holds global static
 * state (the shutting-down flag is read by deferred processing), so each test resets via clearAll, which also
 * lowers the flag.
 */
class SystemStateManagerTest {

    @AfterEach
    void reset() {
        // Always end with the flag lowered so other tests (e.g. the deferred throttle) see a running server.
        SystemStateManager.clearAll();
    }

    @Test
    void clearAllInvokesEveryRegisteredComponent() {
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        SystemStateManager.register("a", a::incrementAndGet);
        SystemStateManager.register("b", b::incrementAndGet);

        SystemStateManager.clearAll();

        assertEquals(1, a.get(), "component a was cleared");
        assertEquals(1, b.get(), "component b was cleared");
    }

    @Test
    void clearAllContinuesPastAComponentThatThrows() {
        AtomicInteger after = new AtomicInteger();
        SystemStateManager.register("boom", () -> { throw new RuntimeException("clear failed"); });
        SystemStateManager.register("after-boom", after::incrementAndGet);

        SystemStateManager.clearAll(); // must not propagate the exception

        assertEquals(1, after.get(), "a later component is still cleared after one throws");
    }

    @Test
    void markShuttingDownRaisesTheFlagAndClearAllLowersIt() {
        assertFalse(SystemStateManager.isShuttingDown(), "starts running");
        SystemStateManager.markShuttingDown();
        assertTrue(SystemStateManager.isShuttingDown());

        SystemStateManager.clearAll();
        assertFalse(SystemStateManager.isShuttingDown(), "clearAll finishes the shutdown and resets the flag");
    }
}
