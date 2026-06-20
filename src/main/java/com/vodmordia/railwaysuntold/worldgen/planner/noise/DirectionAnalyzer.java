package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.List;

/**
 * Direction classification for the precision compiler: per-step and windowed travel
 * direction codes, run direction, and diagonal detection.
 *
 * Note getStepDirection and getLocalDirection use deliberately different minimum-movement
 * thresholds (4 vs 2 blocks per axis) and are NOT interchangeable - getLocalDirection runs
 * at any waypoint density (slope validation can insert waypoints 1-2 blocks apart), while
 * getStepDirection classifies a single coarse step.
 */
final class DirectionAnalyzer {

    private DirectionAnalyzer() {}

    /**
     * Returns a direction code for a single step between two waypoints. Returns -1
     * if the step is too small to classify (noise). Uses the same 0-7 coding as
     * {@link #getLocalDirection}: 0=N, 1=E, 2=S, 3=W, 4=NE, 5=SE, 6=SW, 7=NW.
     */
    static int getStepDirection(CoarseWaypoint from, CoarseWaypoint to) {
        int dx = to.position().getX() - from.position().getX();
        int dz = to.position().getZ() - from.position().getZ();
        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);
        if (absDx < 4 && absDz < 4) return -1; // step too short to classify reliably

        // Diagonal if both axes carry significant movement and roughly equal magnitudes
        if (absDx >= 4 && absDz >= 4) {
            int max = Math.max(absDx, absDz);
            int min = Math.min(absDx, absDz);
            if ((max - min) < max * 0.4) {
                if (dx >= 0 && dz < 0) return 4;  // NE
                if (dx >= 0 && dz >= 0) return 5; // SE
                if (dx < 0 && dz >= 0) return 6;  // SW
                return 7;                          // NW
            }
        }
        // Cardinal
        if (absDx >= absDz) {
            return dx >= 0 ? 1 : 3; // EAST or WEST
        } else {
            return dz >= 0 ? 2 : 0; // SOUTH or NORTH
        }
    }

    /**
     * Returns true if two direction codes differ by >=90°. Used to detect sharp L-turns
     * that warrant splitting a run. Cardinal ↔ adjacent diagonal (e.g. N ↔ NE, 45°)
     * is NOT a significant change; cardinal ↔ perpendicular cardinal (e.g. N ↔ E, 90°)
     * IS.
     */
    static boolean isSignificantDirectionChange(int dirA, int dirB) {
        if (dirA == dirB) return false;
        // Map direction codes to angles in degrees.
        int[] angles = {0, 90, 180, 270, 45, 135, 225, 315};
        int a = angles[dirA];
        int b = angles[dirB];
        int diff = Math.abs(a - b);
        if (diff > 180) diff = 360 - diff;
        return diff >= 90;
    }

    /**
     * Returns a direction code for the local travel direction at a waypoint index.
     * Uses a window of waypoints for stability. Returns -1 if too small to determine.
     * Codes: 0=NORTH, 1=EAST, 2=SOUTH, 3=WEST, 4=NE, 5=SE, 6=SW, 7=NW
     */
    static int getLocalDirection(List<CoarseWaypoint> waypoints, int idx, int window) {
        int endIdx = Math.min(idx + window, waypoints.size() - 1);
        if (endIdx <= idx) return -1;

        BlockPos start = waypoints.get(idx).position();
        BlockPos end = waypoints.get(endIdx).position();
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);

        if (absDx < 2 && absDz < 2) return -1; // Too small to determine

        // Check if diagonal (~45°). Uses a ratio-based test rather than an absolute
        // minimum per axis so this works at any waypoint density - the slope validator
        // can insert waypoints at 1-2 block spacing, in which case a 3-window look-ahead
        // only yields absDx = absDz ≈ 2-4 even for obvious diagonals.
        if (absDx >= 2 && absDz >= 2) {
            int max = Math.max(absDx, absDz);
            int min = Math.min(absDx, absDz);
            if ((max - min) < max * 0.4) {
                // Diagonal - determine which quadrant
                if (dx >= 0 && dz < 0) return 4;  // NE
                if (dx >= 0 && dz >= 0) return 5;  // SE
                if (dx < 0 && dz >= 0) return 6;   // SW
                return 7;                            // NW
            }
        }

        // Cardinal
        if (absDx >= absDz) {
            return dx >= 0 ? 1 : 3; // EAST or WEST
        } else {
            return dz >= 0 ? 2 : 0; // SOUTH or NORTH
        }
    }

    static Direction getRunDirection(WaypointRun run, Direction fallback) {
        if (run.waypoints().size() < 2) return fallback;
        BlockPos first = run.firstWaypoint().position();
        BlockPos last = run.lastWaypoint().position();
        int dx = last.getX() - first.getX();
        int dz = last.getZ() - first.getZ();
        if (Math.abs(dx) < 4 && Math.abs(dz) < 4) return fallback;
        return getCardinalDirection(dx, dz);
    }

    /**
     * Checks if a run's waypoints follow a ~45° diagonal path.
     *
     */
    static boolean isDiagonalRun(WaypointRun run) {
        if (run.waypoints().size() < 3) return false;
        BlockPos first = run.firstWaypoint().position();
        BlockPos last = run.lastWaypoint().position();
        int absDx = Math.abs(last.getX() - first.getX());
        int absDz = Math.abs(last.getZ() - first.getZ());
        if (absDx < 32 || absDz < 32) return false;
        int max = Math.max(absDx, absDz);
        int min = Math.min(absDx, absDz);
        return (max - min) < max * 0.15;
    }

    /**
     * Determines which cardinal direction the head should be on to enter a diagonal.
     * Prefers the current direction if compatible, otherwise picks the dominant axis.
     */
    static Direction getCardinalForDiagonalEntry(DiagonalDirection diagonal, Direction currentDir) {
        // A diagonal can be entered from two cardinals. E.g., NE from EAST (turn left) or NORTH (turn right).
        // Prefer the current direction to avoid unnecessary curves.
        switch (diagonal) {
            case NORTHEAST: if (currentDir == Direction.EAST || currentDir == Direction.NORTH) return currentDir; return Direction.EAST;
            case SOUTHEAST: if (currentDir == Direction.EAST || currentDir == Direction.SOUTH) return currentDir; return Direction.EAST;
            case SOUTHWEST: if (currentDir == Direction.WEST || currentDir == Direction.SOUTH) return currentDir; return Direction.WEST;
            case NORTHWEST: if (currentDir == Direction.WEST || currentDir == Direction.NORTH) return currentDir; return Direction.WEST;
            default: return currentDir;
        }
    }

    static DiagonalDirection getDiagonalRunDirection(WaypointRun run) {
        BlockPos first = run.firstWaypoint().position();
        BlockPos last = run.lastWaypoint().position();
        int dx = last.getX() - first.getX();
        int dz = last.getZ() - first.getZ();
        if (dx >= 0 && dz < 0) return DiagonalDirection.NORTHEAST;
        if (dx >= 0 && dz >= 0) return DiagonalDirection.SOUTHEAST;
        if (dx < 0 && dz >= 0) return DiagonalDirection.SOUTHWEST;
        return DiagonalDirection.NORTHWEST;
    }

    static Direction getCardinalDirection(int dx, int dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
