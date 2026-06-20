package com.vodmordia.railwaysuntold.worldgen.planner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Container for a sequence of path segments that form a complete approach path.
 */
public class PlannedPath {

    /** Why a path is invalid. Lets callers distinguish failure modes. */
    public enum InvalidReason {
        /** Generic / catch-all failure. */
        GENERIC,
        /** Compile produced no segments at all. */
        EMPTY,
        /** Could not stitch a final tail that ends in the required direction near a village. */
        TAIL_ALIGN_FAILED,
        /** A junction between consecutive segments turns >=90° with no curve to absorb it - untraversable. */
        ILLEGAL_SEAM,
        /** The route's approach drives through the target village body instead of around it to the station. */
        SKIRT_OVERDRIVE
    }

    public final List<PathSegment> segments;
    public final boolean valid;
    public final BlockPos finalPosition;
    public final Direction finalDirection;
    public final String invalidReason;
    public final InvalidReason invalidReasonCode;

    private PlannedPath(List<PathSegment> segments, boolean valid, BlockPos finalPosition,
                        Direction finalDirection, String invalidReason,
                        InvalidReason invalidReasonCode) {
        // Always use mutable list to support segment splitting during execution
        this.segments = new ArrayList<>(segments);
        this.valid = valid;
        this.finalPosition = finalPosition;
        this.finalDirection = finalDirection;
        this.invalidReason = invalidReason;
        this.invalidReasonCode = invalidReasonCode;
    }

    /**
     * Creates a successful planned path.
     * If finalDir is null but segments exist, computes final direction from the last segment.
     */
    public static PlannedPath success(List<PathSegment> segments, BlockPos finalPos, Direction finalDir) {
        Direction actualFinalDir = finalDir;
        if (actualFinalDir == null && !segments.isEmpty()) {
            actualFinalDir = segments.get(segments.size() - 1).getEndDirection();
        }
        return new PlannedPath(segments, true, finalPos, actualFinalDir, null, null);
    }

    /**
     * Creates an invalid planned path with a free-form reason. Defaults to GENERIC code.
     */
    public static PlannedPath invalid(String reason) {
        return invalid(InvalidReason.GENERIC, reason);
    }

    /**
     * Creates an invalid planned path with a typed reason code and free-form message.
     */
    public static PlannedPath invalid(InvalidReason code, String reason) {
        return new PlannedPath(new ArrayList<>(), false, null, null, reason, code);
    }

    /**
     * Invalid path that retains its segments. Used when a route is rejected for a geometry defect (an
     * illegal seam) but the segments must stay inspectable - so the rejected route never executes
     * (valid=false), yet a replayed capture can still examine the offending geometry for a fix.
     */
    public static PlannedPath invalidWithSegments(InvalidReason code, String reason, List<PathSegment> segments) {
        return new PlannedPath(segments, false, null, null, reason, code);
    }

    /**
     * Returns true if the path has no segments.
     */
    public boolean isEmpty() {
        return segments.isEmpty();
    }

    @Override
    public String toString() {
        if (!valid) {
            return "PlannedPath[INVALID: " + invalidReason + "]";
        }
        return String.format("PlannedPath[%d segments, -> %s facing %s]",
                segments.size(), finalPosition, finalDirection);
    }
}
