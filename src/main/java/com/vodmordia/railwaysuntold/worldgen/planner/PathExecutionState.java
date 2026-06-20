package com.vodmordia.railwaysuntold.worldgen.planner;

/**
 * Interface for state objects that can be used with PathExecutor.
 * Abstracts the path segment tracking needed for path execution.
 */
public interface PathExecutionState {

    PathSegment getCurrentSegment();

    void advanceToNextSegment();

    default void clearRemainingPath() {
        while (getCurrentSegment() != null) {
            advanceToNextSegment();
        }
    }
}
