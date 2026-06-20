package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.api.TrackAvoidanceApi;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Executes planned path segments by converting them to PlacementDecisions.
 */
public class PathExecutor {

    /**
     * Executes the next segment in the approach path.
     * Validates that the planned segment endpoint is still ahead of the current position.
     *
     * @param state      The path execution state with the planned path
     * @param currentPos Current track head position
     * @param currentDir Current track direction
     * @param scan       Terrain scan (for decisions that need it)
     * @param level      Server level (for curve parameter creation)
     * @return PlacementDecision for the next track segment, or null if path complete or conflicts detected
     */
    @Nullable
    public static PlacementDecision executeNextSegment(
            PathExecutionState state,
            BlockPos currentPos,
            Direction currentDir,
            TerrainScanner.TerrainScan scan,
            ServerLevel level) {

        PathSegment currentSegment = state.getCurrentSegment();
        if (currentSegment == null) {
            return null;
        }

        BlockPos segmentEnd = getSegmentEndpoint(currentSegment);
        if (segmentEnd != null) {
            // The head sitting on a segment's start means that segment is the one to place now, not one
            // already behind us. Skipping it here would emit the NEXT segment, whose start is ahead of
            // the head - the BEZIER drift guard then halts and the head replans forever. This matters for
            // short (1-block) segments, whose endpoint is within isNearSegmentEnd's XZ tolerance of the
            // start. A genuinely stale segment (endpoint behind, or zero-length) is still skipped.
            boolean headAtSegmentStart = currentPos.equals(currentSegment.getStart());
            if (isEndpointBehind(currentPos, segmentEnd, currentDir)
                    || segmentEnd.equals(currentPos)
                    || (!headAtSegmentStart && isNearSegmentEnd(currentPos, segmentEnd))) {
                // Segment was already placed (head moved to or past endpoint) - advance.
                state.advanceToNextSegment();
                return executeNextSegment(state, currentPos, currentDir, scan, level);
            }
        }

        // Reject segments whose endpoints fall inside a registered avoidance zone.
        // The coarse route avoids these at ~16-block waypoint resolution, but
        // terrain-following interpolation between waypoints can drift into the zone.
        if (segmentEnd != null && TrackAvoidanceApi.isInsideAnyZone(segmentEnd, level)) {
            state.clearRemainingPath();
            return null;
        }

        PathExecutionContext ctx = new PathExecutionContext(
                state, currentPos, currentDir, scan, level);
        return currentSegment.execute(ctx);
    }

    /** Checks if the endpoint has no forward progress relative to the travel direction. */
    // Package-private for PathExecutorTest, which gates this boundary directly.
    static boolean isEndpointBehind(BlockPos current, BlockPos end, Direction dir) {
        int forward = (end.getX() - current.getX()) * dir.getStepX()
                    + (end.getZ() - current.getZ()) * dir.getStepZ();
        return forward <= 0;
    }

    /**
     * Checks whether the head is close enough to a segment's endpoint in XZ that the
     * segment should be considered complete regardless of Y mismatch.
     */
    // Package-private for PathExecutorTest, which gates the deliberate Y-tolerance here.
    static boolean isNearSegmentEnd(BlockPos current, BlockPos end) {
        int horizDist = Math.abs(end.getX() - current.getX())
                      + Math.abs(end.getZ() - current.getZ());
        return horizDist <= 1;
    }

    /**
     * Gets the endpoint of a segment for conflict checking.
     * Most segments use their getEndPosition(), but CURVE needs special calculation.
     */
    private static BlockPos getSegmentEndpoint(PathSegment segment) {
        // Always use the segment's pre-computed endpoint.
        return segment.getEndPosition();
    }
}
