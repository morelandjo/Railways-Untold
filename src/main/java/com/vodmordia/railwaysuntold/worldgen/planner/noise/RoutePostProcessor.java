package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-processing for coarse route waypoints: slope validation, self-intersection
 * removal, and lateral/elevation smoothing.
 */
final class RoutePostProcessor {

    private static final int MIN_SEGMENT_GAP = 6;
    private static final int SELF_INTERSECTION_RADIUS = 16;
    /** Max Y difference before two XZ-close waypoints are still considered a potential loop.
     *  Slope cascades produce XZ-close waypoints that descend monotonically in Y */
    private static final int SELF_INTERSECTION_MAX_Y_DELTA = 3;

    /**
     * Shapes waypoint elevation so the route can hold the configured slope. Movable waypoints are
     * banded between two bounds - raised toward a higher cliff or pin ahead (spreadElevationBackward)
     * and lowered toward a low pin so a descent spreads over real distance (lowerTowardPins) - then
     * any remaining over-steep gap is filled with ramp waypoints. Constrained waypoints stay fixed.
     */
    static List<CoarseWaypoint> enforceSlopeConstraints(List<CoarseWaypoint> waypoints) {
        if (waypoints.size() < 2) return waypoints;

        spreadElevationBackward(waypoints);
        lowerTowardPins(waypoints);

        List<CoarseWaypoint> result = new ArrayList<>();
        result.add(waypoints.get(0));

        for (int i = 1; i < waypoints.size(); i++) {
            CoarseWaypoint prev = result.get(result.size() - 1);
            CoarseWaypoint current = waypoints.get(i);

            int yDiff = Math.abs(current.advisedTrackY() - prev.advisedTrackY());
            int maxSlope = CoarseRoutePlanner.getMaxSlopePerSegment();
            if (yDiff > maxSlope) {
                int stepsNeeded = (yDiff + maxSlope - 1) / maxSlope;
                int direction = current.advisedTrackY() > prev.advisedTrackY() ? 1 : -1;
                int yStep = direction * maxSlope;
                // A ramp inside a continuous bridge or tunnel span inherits that span's type so the
                // run stays one bridge/tunnel; ramps between surface waypoints stay FLAT_MAINTAIN.
                WaypointType rampType = prev.type() == current.type()
                        && (prev.type() == WaypointType.TUNNEL || prev.type() == WaypointType.BRIDGE)
                        ? prev.type() : WaypointType.FLAT_MAINTAIN;

                for (int s = 1; s < stepsNeeded; s++) {
                    double t = (double) s / stepsNeeded;
                    int interpX = roundOnAxis(prev.position().getX(), t,
                            current.position().getX() - prev.position().getX());
                    int interpZ = roundOnAxis(prev.position().getZ(), t,
                            current.position().getZ() - prev.position().getZ());
                    int interpY = prev.advisedTrackY() + s * yStep;

                    result.add(new CoarseWaypoint(
                            new BlockPos(interpX, interpY, interpZ),
                            interpY, rampType));
                }
            }

            result.add(current);
        }

        return result;
    }

    private static void spreadElevationBackward(List<CoarseWaypoint> waypoints) {
        // Backward Lipschitz-from-below sweep: walking from the end, raise each movable waypoint so
        // it sits no more than one segment's slope budget below the next waypoint. This ramps the
        // approach up to a higher cliff or pin ahead, spread over the real-ratio distance. Waypoints
        // are only ever raised, so a moved waypoint stays at or above its terrain Y. Constrained
        // waypoints and the two route endpoints are fixed anchors the ramp builds toward.
        int budgetPerStep = (int) Math.ceil(
                CoarseRoutePlanner.SAMPLE_INTERVAL * RailwaysUntoldConfig.getMaxSlopeRatio());
        for (int i = waypoints.size() - 2; i >= 1; i--) {
            CoarseWaypoint wp = waypoints.get(i);
            if (wp.yBasis() == CoarseRoute.YBasis.CONSTRAINT) continue;
            int lowerBound = waypoints.get(i + 1).advisedTrackY() - budgetPerStep;
            if (lowerBound > wp.advisedTrackY()) {
                waypoints.set(i, new CoarseWaypoint(wp.position(), lowerBound, wp.type(), wp.yBasis()));
            }
        }
    }

    private static void lowerTowardPins(List<CoarseWaypoint> waypoints) {
        // Pin-anchored upper bound: a constrained waypoint's Y propagates a ceiling outward at one
        // segment's slope budget per waypoint, so a low pin (e.g. a below-terrain station target)
        // pulls the preceding movable waypoints down early, spreading the descent over real distance
        // instead of cramming it into the final segment. A waypoint pulled below its terrain dips
        // underground, so it becomes a TUNNEL; one that stays at or above terrain keeps its type.
        int n = waypoints.size();
        int budgetPerStep = (int) Math.ceil(
                CoarseRoutePlanner.SAMPLE_INTERVAL * RailwaysUntoldConfig.getMaxSlopeRatio());

        final long UNBOUNDED = Long.MAX_VALUE / 4;
        long[] ceiling = new long[n];
        for (int i = 0; i < n; i++) {
            ceiling[i] = waypoints.get(i).yBasis() == CoarseRoute.YBasis.CONSTRAINT
                    ? waypoints.get(i).advisedTrackY() : UNBOUNDED;
        }
        for (int i = 1; i < n; i++) {
            ceiling[i] = Math.min(ceiling[i], ceiling[i - 1] + budgetPerStep);
        }
        for (int i = n - 2; i >= 0; i--) {
            ceiling[i] = Math.min(ceiling[i], ceiling[i + 1] + budgetPerStep);
        }

        for (int i = 0; i < n; i++) {
            CoarseWaypoint wp = waypoints.get(i);
            if (wp.yBasis() == CoarseRoute.YBasis.CONSTRAINT) continue;
            if (ceiling[i] >= wp.advisedTrackY()) continue;
            int newY = (int) ceiling[i];
            WaypointType type = newY < wp.position().getY() ? WaypointType.TUNNEL : wp.type();
            waypoints.set(i, new CoarseWaypoint(wp.position(), newY, type, wp.yBasis()));
        }
    }

    /**
     * Smooths lateral oscillations where consecutive waypoints zigzag perpendicular
     * to the travel direction.
     */
    static List<CoarseWaypoint> smoothLateralOscillations(List<CoarseWaypoint> waypoints) {
        if (waypoints.size() < 3) return waypoints;

        // Maximum lateral deviation (in blocks) below which a waypoint is considered
        // a zigzag artifact rather than an intentional curve. Keep this conservative
        // to avoid erasing mountain-avoidance curves (CURVE_AROUND offset = 20).
        int maxDeviationThreshold = 10;

        // Pass 1: Detect zigzag over a 4-point window [i-1, i, i+1, i+2]: if waypoint
        // i deviates to one side and i+1 deviates to the opposite side of the line
        // connecting i-1 to i+2, both waypoints form a zigzag.
        if (waypoints.size() >= 4) {
            for (int i = 1; i < waypoints.size() - 2; i++) {
                CoarseWaypoint a = waypoints.get(i - 1);
                CoarseWaypoint b = waypoints.get(i);
                CoarseWaypoint c = waypoints.get(i + 1);
                CoarseWaypoint d = waypoints.get(i + 2);

                if (!isSmoothableType(b) || !isSmoothableType(c)) continue;
                // A constrained position is fixed laterally as well as vertically.
                if (b.yBasis() == CoarseRoute.YBasis.CONSTRAINT
                        || c.yBasis() == CoarseRoute.YBasis.CONSTRAINT) continue;

                double ax = d.position().getX() - a.position().getX();
                double az = d.position().getZ() - a.position().getZ();
                double lenSq = ax * ax + az * az;
                if (lenSq < 1) continue;

                double crossB = signedCross(a.position(), b.position(), ax, az);
                double crossC = signedCross(a.position(), c.position(), ax, az);

                if (crossB * crossC >= 0) continue;

                double devB = Math.abs(crossB) / Math.sqrt(lenSq);
                double devC = Math.abs(crossC) / Math.sqrt(lenSq);
                if (devB >= maxDeviationThreshold || devC >= maxDeviationThreshold) continue;

                waypoints.set(i, projectOntoLine(a, b, ax, az, lenSq));
                waypoints.set(i + 1, projectOntoLine(a, c, ax, az, lenSq));

                i++;
            }
        }

        // Pass 2: Detect single-point spikes over a 3-point window [A, B, C]:
        // if B deviates laterally from line A->C, it's a spike that creates a
        // tight curve even though the neighbours are aligned. This catches the
        // pattern z=20,24,20 that pass 1 misses (one interior point on the line,
        // the other spiking out).
        for (int i = 1; i < waypoints.size() - 1; i++) {
            CoarseWaypoint a = waypoints.get(i - 1);
            CoarseWaypoint b = waypoints.get(i);
            CoarseWaypoint c = waypoints.get(i + 1);

            if (!isSmoothableType(b)) continue;
            if (b.yBasis() == CoarseRoute.YBasis.CONSTRAINT) continue;

            double ax = c.position().getX() - a.position().getX();
            double az = c.position().getZ() - a.position().getZ();
            double lenSq = ax * ax + az * az;
            if (lenSq < 1) continue;

            double crossB = signedCross(a.position(), b.position(), ax, az);

            double devB = Math.abs(crossB) / Math.sqrt(lenSq);
            if (devB < 1 || devB >= maxDeviationThreshold) continue;

            waypoints.set(i, projectOntoLine(a, b, ax, az, lenSq));
        }

        return waypoints;
    }

    private static boolean isSmoothableType(CoarseWaypoint wp) {
        // Only TERRAIN_FOLLOW is smoothable. FLAT_MAINTAIN waypoints are inserted by
        // slope validation as ramp points - smoothing them collapses the gradual
        // descent into a single steep drop.
        return wp.type() == WaypointType.TERRAIN_FOLLOW;
    }

    private static CoarseWaypoint projectOntoLine(CoarseWaypoint a, CoarseWaypoint wp,
                                                   double ax, double az, double lenSq) {
        double bx = wp.position().getX() - a.position().getX();
        double bz = wp.position().getZ() - a.position().getZ();
        double t = Math.max(0, Math.min(1, RouteGeometry.dot(bx, bz, ax, az) / lenSq));
        int newX = roundOnAxis(a.position().getX(), t, ax);
        int newZ = roundOnAxis(a.position().getZ(), t, az);
        return new CoarseWaypoint(
                new BlockPos(newX, wp.advisedTrackY(), newZ),
                wp.advisedTrackY(), wp.type(), wp.yBasis());
    }

    /** Rounds an axis coordinate interpolated a fraction t of delta from base. */
    private static int roundOnAxis(double base, double t, double delta) {
        return (int) Math.round(base + t * delta);
    }

    /**
     * Signed 2D cross product of the vector (p - a) with the line direction (ax, az):
     * its sign tells which side of the line p is on; its magnitude over the line length
     * is the perpendicular deviation.
     */
    private static double signedCross(BlockPos a, BlockPos p, double ax, double az) {
        return (p.getX() - a.getX()) * az - (p.getZ() - a.getZ()) * ax;
    }

    /**
     * Smooths elevation oscillations in the coarse waypoint Y sequence.
     * Terrain noise causes advisedTrackY to bounce by 2-4 blocks between consecutive
     * waypoints even on gently rolling terrain, producing visible stair-step artifacts
     * at segment boundaries.
     */
    static List<CoarseWaypoint> smoothElevationOscillations(List<CoarseWaypoint> waypoints) {
        if (waypoints.size() < 5) return waypoints;

        // Half-width of the smoothing window (in waypoints). At SAMPLE_INTERVAL=8,
        // a radius of 3 covers ~48 blocks - enough to absorb 2-4 block noise without
        // flattening real terrain features.
        int radius = 3;
        // Maximum single-step correction (blocks) to avoid over-flattening cliff edges
        int maxCorrection = 2;

        int[] smoothedY = new int[waypoints.size()];
        boolean[] smoothable = new boolean[waypoints.size()];

        for (int i = 0; i < waypoints.size(); i++) {
            smoothable[i] = isSmoothableType(waypoints.get(i));
            smoothedY[i] = waypoints.get(i).advisedTrackY();
        }

        // Weighted moving average - closer waypoints get more weight
        for (int i = 1; i < waypoints.size() - 1; i++) {
            if (!smoothable[i]) continue;
            // A constrained Y is fixed; leave it but let it still anchor neighbours' average.
            if (waypoints.get(i).yBasis() == CoarseRoute.YBasis.CONSTRAINT) continue;

            double weightedSum = 0;
            double totalWeight = 0;

            for (int j = Math.max(0, i - radius); j <= Math.min(waypoints.size() - 1, i + radius); j++) {
                // Skip non-smoothable neighbours so bridges/tunnels don't pull the average
                if (!smoothable[j]) continue;
                double distance = Math.abs(j - i);
                double weight = 1.0 / (1.0 + distance);
                weightedSum += weight * waypoints.get(j).advisedTrackY();
                totalWeight += weight;
            }

            if (totalWeight > 0) {
                int avgY = (int) Math.round(weightedSum / totalWeight);
                int originalY = waypoints.get(i).advisedTrackY();
                int delta = avgY - originalY;
                // Clamp correction to avoid yanking elevation too far in one pass
                if (Math.abs(delta) > maxCorrection) {
                    delta = delta > 0 ? maxCorrection : -maxCorrection;
                }
                smoothedY[i] = originalY + delta;
            }
        }

        // Apply smoothed values
        for (int i = 1; i < waypoints.size() - 1; i++) {
            if (!smoothable[i]) continue;
            if (smoothedY[i] != waypoints.get(i).advisedTrackY()) {
                CoarseWaypoint old = waypoints.get(i);
                waypoints.set(i, new CoarseWaypoint(
                        new BlockPos(old.position().getX(), smoothedY[i], old.position().getZ()),
                        smoothedY[i], old.type(), old.yBasis()));
            }
        }

        return waypoints;
    }

    /**
     * Removes self-intersections from the waypoint list.
     */
    static List<CoarseWaypoint> removeSelfIntersections(List<CoarseWaypoint> waypoints) {
        List<CoarseWaypoint> result = new ArrayList<>(waypoints);

        int i = 0;
        while (i < result.size()) {
            boolean found = false;
            for (int j = i + MIN_SEGMENT_GAP; j < result.size(); j++) {
                BlockPos posI = result.get(i).position();
                BlockPos posJ = result.get(j).position();
                double dx = posI.getX() - posJ.getX();
                double dz = posI.getZ() - posJ.getZ();
                double distSq = dx * dx + dz * dz;

                if (distSq > (double) SELF_INTERSECTION_RADIUS * SELF_INTERSECTION_RADIUS) {
                    continue;
                }

                // Reject candidates that descend/ascend significantly in Y - a real loop
                // stays in the same elevation plane, whereas a slope cascade stays close
                // in XZ while dropping Y. Without this guard, the cascade gets deleted and
                // leaves a cliff.
                int absDy = Math.abs(posI.getY() - posJ.getY());
                if (absDy > SELF_INTERSECTION_MAX_Y_DELTA) {
                    continue;
                }

                result.subList(i + 1, j).clear();
                found = true;
                break;
            }
            if (!found) {
                i++;
            }
        }

        return result;
    }
}
