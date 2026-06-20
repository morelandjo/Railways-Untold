package com.vodmordia.railwaysuntold.worldgen.planner;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Base state class for tracking path execution progress.
 */
public class ApproachState implements PathExecutionState {

    private static final Logger LOGGER = LogUtils.getLogger();

    protected PlannedPath approachPath;
    protected int currentSegmentIndex;

    public ApproachState() {
        this.approachPath = null;
        this.currentSegmentIndex = 0;
    }

    public boolean hasApproachPath() {
        return approachPath != null && approachPath.valid && !approachPath.isEmpty();
    }

    public void clearApproachPath() {
        this.approachPath = null;
        this.currentSegmentIndex = 0;
    }

    @Override
    @Nullable
    public PathSegment getCurrentSegment() {
        if (approachPath == null || !approachPath.valid) {
            return null;
        }
        if (currentSegmentIndex >= approachPath.segments.size()) {
            return null;
        }
        return approachPath.segments.get(currentSegmentIndex);
    }

    @Override
    public void advanceToNextSegment() {
        this.currentSegmentIndex++;
    }

    public boolean hasRemainingPath() {
        if (approachPath == null || !approachPath.valid) {
            LOGGER.trace("[APPROACH-STATE] hasRemainingPath: no valid path");
            return false;
        }
        boolean hasMore = currentSegmentIndex < approachPath.segments.size();
        LOGGER.trace("[APPROACH-STATE] hasRemainingPath: index={}, total={}, hasMore={}",
                currentSegmentIndex, approachPath.segments.size(), hasMore);
        return hasMore;
    }
}
