package com.vodmordia.railwaysuntold.util.core;

/**
 * Interface for components that maintain state which needs to be cleared during server start/stop cycles.
 */
@FunctionalInterface
public interface Clearable {

    /**
     * Clears all cached/tracked state.
     * Called during server start and stop to ensure clean state.
     */
    void clear();
}
