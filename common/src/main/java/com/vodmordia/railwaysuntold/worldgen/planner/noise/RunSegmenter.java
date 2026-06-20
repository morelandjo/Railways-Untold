package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups coarse waypoints into typed runs and splits those runs at significant direction
 * changes, before the precision compiler emits segments per run.
 */
final class RunSegmenter {

    private RunSegmenter() {}

    /**
     * Merges consecutive runs that trace one continuous 45° diagonal in the same direction. The type
     * grouping and direction-split passes fragment a sustained diagonal drift wherever the terrain changes
     * its run type (e.g. a tunnel span mid-drift) - leaving a too-short remainder that fails the diagonal
     * classifier's length minimum and is then compiled as a cardinal run with its lateral dropped, so the
     * route lands short of its target laterally. Coalescing the collinear diagonal fragments back into one
     * run lets the diagonal chain realize the full lateral shift. Only clean 45° runs (per the same
     * tightness the diagonal classifier uses) heading the same way are merged, so genuine maneuvers
     * separated by a cardinal leg are untouched. The diagonal chain compiler emits the same segment types
     * regardless of run type, so the merged run's type is immaterial - it keeps the first fragment's.
     */
    static List<WaypointRun> coalesceCollinearDiagonalRuns(List<WaypointRun> runs) {
        List<WaypointRun> out = new ArrayList<>();
        for (WaypointRun run : runs) {
            if (!out.isEmpty()) {
                WaypointRun prev = out.get(out.size() - 1);
                String prevDir = diagonalDirection(prev);
                if (prevDir != null && prevDir.equals(diagonalDirection(run))) {
                    List<CoarseWaypoint> merged = new ArrayList<>(prev.waypoints());
                    merged.addAll(run.waypoints());
                    out.set(out.size() - 1, new WaypointRun(prev.type(), List.copyOf(merged)));
                    continue;
                }
            }
            out.add(run);
        }
        return out;
    }

    /**
     * The signed 45° direction of a run as "sx,sz", or null if the run is not a clean diagonal (one axis
     * zero, or the off-axis component is more than 15% shy of the dominant one). Length-independent, unlike
     * {@link DirectionAnalyzer#isDiagonalRun}, so it recognizes a short diagonal fragment.
     */
    private static String diagonalDirection(WaypointRun run) {
        BlockPos f = run.firstWaypoint().position();
        BlockPos l = run.lastWaypoint().position();
        int dx = l.getX() - f.getX();
        int dz = l.getZ() - f.getZ();
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        if (adx == 0 || adz == 0) return null;
        int max = Math.max(adx, adz);
        int min = Math.min(adx, adz);
        if ((max - min) >= max * 0.15) return null;
        return Integer.signum(dx) + "," + Integer.signum(dz);
    }

    static List<WaypointRun> groupIntoRuns(List<CoarseWaypoint> waypoints) {
        List<WaypointRun> runs = new ArrayList<>();
        if (waypoints.isEmpty()) return runs;

        WaypointType currentType = normalizeType(waypoints.get(0).type());
        List<CoarseWaypoint> currentRun = new ArrayList<>();
        currentRun.add(waypoints.get(0));

        for (int i = 1; i < waypoints.size(); i++) {
            WaypointType type = normalizeType(waypoints.get(i).type());
            if (type != currentType) {
                runs.add(new WaypointRun(currentType, List.copyOf(currentRun)));
                currentType = type;
                currentRun.clear();
            }
            currentRun.add(waypoints.get(i));
        }
        if (!currentRun.isEmpty()) {
            runs.add(new WaypointRun(currentType, List.copyOf(currentRun)));
        }
        return runs;
    }

    /**
     * Splits runs where the travel direction changes significantly within a single run.
     *
     * Two passes:
     * 1. {@link #splitAtStepDirectionChanges} - step-by-step, catches sharp 90° turns
     *    in runs of ANY size. Handles sparse L-shape tails where a run has e.g. 3
     *    east waypoints followed by 1 north waypoint - the windowed pass below
     *    misses this (size < 8) and the compiler would pick NORTH as the whole
     *    run's direction, then lay track through wherever the east leg was supposed
     *    to wrap around.
     * 2. Sliding-window pass for gradual curve detection on dense runs.
     */
    static List<WaypointRun> splitRunsAtDirectionChanges(List<WaypointRun> runs) {
        // First pass: step-to-step direction changes (handles sparse L-shapes)
        List<WaypointRun> preSplit = new ArrayList<>();
        for (WaypointRun run : runs) {
            preSplit.addAll(splitAtStepDirectionChanges(run));
        }

        // Second pass: original windowed logic for dense smooth-curve detection
        List<WaypointRun> result = new ArrayList<>();
        int WINDOW = 3; // look-ahead window for stable direction detection
        int MIN_RUN_SIZE = 4; // don't split runs smaller than this

        for (WaypointRun run : preSplit) {
            if (run.waypoints().size() < MIN_RUN_SIZE * 2) {
                // Too small to split meaningfully
                result.add(run);
                continue;
            }

            // Determine direction using a window of waypoints for stability
            int runStartIdx = 0;
            int establishedDir = DirectionAnalyzer.getLocalDirection(run.waypoints(), 0, WINDOW);

            for (int i = WINDOW; i < run.waypoints().size() - WINDOW; i++) {
                int localDir = DirectionAnalyzer.getLocalDirection(run.waypoints(), i, WINDOW);
                if (localDir != establishedDir && localDir != -1) {
                    // Direction changed - split here if the sub-run is big enough
                    if (i - runStartIdx >= MIN_RUN_SIZE) {
                        result.add(new WaypointRun(run.type(),
                                List.copyOf(run.waypoints().subList(runStartIdx, i))));
                        runStartIdx = i;
                        establishedDir = localDir;
                    }
                }
            }

            // Add the remaining portion
            if (runStartIdx == 0) {
                result.add(run); // No splits made
            } else {
                result.add(new WaypointRun(run.type(),
                        List.copyOf(run.waypoints().subList(runStartIdx, run.waypoints().size()))));
            }
        }
        return result;
    }

    /**
     * Splits a run at consecutive-pair direction changes of 90° or more. Runs with a
     * sharp turn get broken into pre-turn and post-turn sub-runs. Sub-runs overlap at
     * the turn waypoint so both sides have at least 2 waypoints (the compiler needs
     * >=2 to determine a run's travel direction via {@code getRunDirection}).
     *
     */
    private static List<WaypointRun> splitAtStepDirectionChanges(WaypointRun run) {
        List<WaypointRun> result = new ArrayList<>();
        List<CoarseWaypoint> wp = run.waypoints();
        if (wp.size() < 3) {
            result.add(run);
            return result;
        }

        int subRunStart = 0;
        int prevStepDir = -1;

        for (int i = 1; i < wp.size(); i++) {
            int stepDir = DirectionAnalyzer.getStepDirection(wp.get(i - 1), wp.get(i));
            if (stepDir == -1) continue; // step too short to classify

            if (prevStepDir == -1) {
                prevStepDir = stepDir;
                continue;
            }

            if (DirectionAnalyzer.isSignificantDirectionChange(prevStepDir, stepDir)) {
                // Split at the turn: the pre-turn sub-run includes [subRunStart..i-1],
                // the post-turn sub-run starts at i-1 and continues to the end. The
                // shared waypoint (i-1) gives the post-turn sub-run at least 2
                // waypoints so its direction can be resolved downstream.
                if (i - 1 - subRunStart >= 1) { // pre-turn has >=2 waypoints
                    result.add(new WaypointRun(run.type(),
                            List.copyOf(wp.subList(subRunStart, i))));
                    subRunStart = i - 1;
                }
            }
            prevStepDir = stepDir;
        }

        if (subRunStart < wp.size()) {
            result.add(new WaypointRun(run.type(),
                    List.copyOf(wp.subList(subRunStart, wp.size()))));
        }
        return result;
    }

    /**
     * Normalize waypoint types for grouping - FLAT_MAINTAIN groups with TERRAIN_FOLLOW
     * since both produce bezier segments.
     */
    private static WaypointType normalizeType(WaypointType type) {
        return switch (type) {
            case FLAT_MAINTAIN -> WaypointType.TERRAIN_FOLLOW;
            case CROSSING_OVER -> WaypointType.BRIDGE;
            case CROSSING_UNDER -> WaypointType.TUNNEL;
            default -> type;
        };
    }
}
