package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.PrecisionRoute;

import javax.annotation.Nullable;

/**
 * State management for terrain-aware path planning and execution.
 */
public class TerrainPlanState extends ApproachState {

    private boolean planningActive;
    private int totalPlansExecuted;
    private int totalSegmentsExecuted;

    /**
     * TRUE while an async coarse/precision route rebuild is in flight. While set, the
     * orchestrator must not place any blocks
     *
     */
    private volatile boolean routeRebuilding;

    @Nullable
    private CoarseRoute coarseRoute;

    @Nullable
    private PrecisionRoute precisionRoute;

    public TerrainPlanState() {
        super();
        this.planningActive = false;
        this.totalPlansExecuted = 0;
        this.totalSegmentsExecuted = 0;
        this.routeRebuilding = false;
    }

    /**
     * Returns TRUE while an async coarse/precision route rebuild is in flight.
     * See routeRebuilding field for details.
     */
    public boolean isRouteRebuilding() {
        return routeRebuilding;
    }

    /**
     * Sets the route-rebuilding flag. Must only be called from CoarseRouteFactory.
     */
    public void setRouteRebuilding(boolean value) {
        this.routeRebuilding = value;
    }

    public void onSegmentCompleted() {
        this.totalSegmentsExecuted++;
        if (!hasRemainingPath()) {
            this.planningActive = false;
        }
    }

    @Override
    public void advanceToNextSegment() {
        super.advanceToNextSegment();
        onSegmentCompleted();
    }

    @Override
    public void clearApproachPath() {
        super.clearApproachPath();
        this.planningActive = false;
    }

    public void setCoarseRoute(@Nullable CoarseRoute route) {
        this.coarseRoute = route;
    }

    public void setPrecisionRoute(@Nullable PrecisionRoute route) {
        this.precisionRoute = route;
    }

    @Nullable
    public PrecisionRoute getPrecisionRoute() {
        return precisionRoute;
    }

    @Nullable
    public CoarseRoute getCoarseRoute() {
        return coarseRoute;
    }

    @Override
    public String toString() {
        return String.format("TerrainPlanState[active=%s, plans=%d, segments=%d, hasCoarseRoute=%s]",
                planningActive, totalPlansExecuted, totalSegmentsExecuted,
                coarseRoute != null);
    }
}
