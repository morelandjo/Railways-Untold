package com.vodmordia.railwaysuntold.worldgen.survey;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cost guard for surveys: caps how many new survey loads start per server tick so a burst of village
 * assignments can't ticket-load dozens of regions at once. Dedupe of in-flight regions is handled by
 * {@link SurveyManager} (keyed by region id); this only throttles new LOADING transitions.
 */
public final class SurveyGate {

    /** Max surveys allowed to enter LOADING per tick. */
    private static final int MAX_NEW_LOADS_PER_TICK = 2;

    private final AtomicInteger startedThisTick = new AtomicInteger(0);

    /** Call once per processing tick to reset the budget. */
    public void resetTick() {
        startedThisTick.set(0);
    }

    /** Returns true (and consumes budget) if another survey may start loading this tick. */
    public boolean tryStartLoad() {
        return startedThisTick.incrementAndGet() <= MAX_NEW_LOADS_PER_TICK;
    }
}
