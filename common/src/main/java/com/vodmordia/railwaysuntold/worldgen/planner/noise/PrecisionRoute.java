package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.PathExecutionState;
import com.vodmordia.railwaysuntold.worldgen.planner.PathSegment;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;

/**
 * A fully pre-computed route that can be executed directly by PathExecutor.
 */
public class PrecisionRoute implements PathExecutionState {

    private final CoarseRoute coarseRoute;
    private final PlannedPath precisionPath;
    private int currentSegmentIndex;

    public PrecisionRoute(CoarseRoute coarseRoute, PlannedPath precisionPath) {
        this.coarseRoute = coarseRoute;
        this.precisionPath = precisionPath;
        this.currentSegmentIndex = 0;
    }

    public CoarseRoute getCoarseRoute() {
        return coarseRoute;
    }

    public PlannedPath getPrecisionPath() {
        return precisionPath;
    }

    public boolean isValid() {
        return precisionPath != null && precisionPath.valid && !precisionPath.isEmpty();
    }

    public boolean hasRemainingPath() {
        return isValid() && currentSegmentIndex < precisionPath.segments.size();
    }

    public int getCurrentSegmentIndex() {
        return currentSegmentIndex;
    }

    public int getTotalSegments() {
        return precisionPath != null ? precisionPath.segments.size() : 0;
    }

    @Override
    public PathSegment getCurrentSegment() {
        if (!hasRemainingPath()) {
            return null;
        }
        return precisionPath.segments.get(currentSegmentIndex);
    }

    @Override
    public void advanceToNextSegment() {
        this.currentSegmentIndex++;
    }

    @Override
    public String toString() {
        return String.format("PrecisionRoute[%d/%d segments, valid=%s]",
                currentSegmentIndex, getTotalSegments(), isValid());
    }
}
