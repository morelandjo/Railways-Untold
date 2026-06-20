package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainZoneAnalyzer;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Terrain sampling and waypoint classification for coarse route planning.
 * Classifies terrain at waypoints using noise data and inserts curve waypoints
 * around mountain zones.
 */
final class RouteTerrainClassifier {

    private static final int PERP_SAMPLE_COUNT = 5;
    private static final int PERP_SAMPLE_INTERVAL = 2;
    private static final int MOUNTAIN_HEIGHT_THRESHOLD = 8;
    private static final int RAVINE_DEPTH_THRESHOLD = 10;
    private static final int CLIFF_DELTA_THRESHOLD = 8;
    private static final int CURVE_OFFSET = 20;
    private static final int MIN_MOUNTAIN_ZONE_LENGTH = 2;

    /** Snap threshold in degrees - angles within this of a cardinal/diagonal snap to it. */
    private static final double SNAP_THRESHOLD_DEGREES = 10.0;

    /**
     * Samples terrain waypoints along a multi-segment 2D path.
     * Decomposes each path segment into octagonal sub-segments (cardinal + 45° diagonal)
     * to produce clean waypoint lines that the precision compiler can follow without
     * creating snake-like SCurve correction patterns.
     */
    static List<CoarseWaypoint> sampleWaypointsAlongPath(
            NoiseTerrainSampler sampler, List<BlockPos> pathWaypoints) {

        List<CoarseWaypoint> waypoints = new ArrayList<>();

        for (int i = 0; i < pathWaypoints.size() - 1; i++) {
            BlockPos from = pathWaypoints.get(i);
            BlockPos to = pathWaypoints.get(i + 1);

            List<BlockPos> subSegments = decomposeToOctagonal(from, to);
            for (int s = 0; s < subSegments.size() - 1; s++) {
                sampleAlongLine(sampler, subSegments.get(s), subSegments.get(s + 1), waypoints, false);
            }
        }

        if (!pathWaypoints.isEmpty()) {
            BlockPos last = pathWaypoints.get(pathWaypoints.size() - 1);
            int h = sampler.getBaseHeight(last.getX(), last.getZ());
            waypoints.add(new CoarseWaypoint(
                    new BlockPos(last.getX(), h, last.getZ()), h, WaypointType.TERRAIN_FOLLOW));
        }

        return waypoints;
    }

    /**
     * Decomposes a path segment into octagonal sub-segments (cardinal + 45° diagonal).
     * Returns a list of points: [from, midpoint, to] for two-part decomposition,
     * or [from, to] if the segment is already aligned.
     */
    private static List<BlockPos> decomposeToOctagonal(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);

        // Already cardinal (dx or dz is ~0) or already 45° diagonal (|dx| ≈ |dz|)
        double angle = Math.toDegrees(Math.atan2(absDz, absDx));
        if (angle < SNAP_THRESHOLD_DEGREES || angle > 90 - SNAP_THRESHOLD_DEGREES
                || Math.abs(angle - 45) < SNAP_THRESHOLD_DEGREES) {
            if (angle < SNAP_THRESHOLD_DEGREES) {
                // Near pure X axis (east/west)
                if (absDz == 0) {
                    // Already perfectly cardinal
                    return List.of(from, to);
                }
                // Cardinal with minor Z drift - run cardinal most of the way,
                // then a short diagonal at the end to reach the target Z.
                // This gives the precision compiler enough forward distance for the correction.
                int cardinalEnd = to.getX() - (dx >= 0 ? 1 : -1) * absDz;
                BlockPos mid = new BlockPos(cardinalEnd, from.getY(), from.getZ());
                return List.of(from, mid, to);
            } else if (angle > 90 - SNAP_THRESHOLD_DEGREES) {
                // Near pure Z axis (north/south)
                if (absDx == 0) {
                    return List.of(from, to);
                }
                int cardinalEnd = to.getZ() - (dz >= 0 ? 1 : -1) * absDx;
                BlockPos mid = new BlockPos(from.getX(), from.getY(), cardinalEnd);
                return List.of(from, mid, to);
            } else {
                // Near 45° - equalize to clean diagonal
                int diag = Math.min(absDx, absDz);
                int signX = dx >= 0 ? 1 : -1;
                int signZ = dz >= 0 ? 1 : -1;
                return List.of(from, new BlockPos(
                        from.getX() + signX * diag, from.getY(), from.getZ() + signZ * diag));
            }
        }

        // Decompose into cardinal + diagonal sub-segments
        int signX = dx >= 0 ? 1 : -1;
        int signZ = dz >= 0 ? 1 : -1;

        // The cardinal remainder covers the difference in the dominant axis
        int cardinalComponent = Math.abs(absDx - absDz);

        BlockPos midpoint;
        if (absDx > absDz) {
            // Dominant X: cardinal-X segment first, then diagonal
            midpoint = new BlockPos(
                    from.getX() + signX * cardinalComponent, from.getY(), from.getZ());
        } else {
            // Dominant Z: cardinal-Z segment first, then diagonal
            midpoint = new BlockPos(
                    from.getX(), from.getY(), from.getZ() + signZ * cardinalComponent);
        }

        return List.of(from, midpoint, to);
    }

    /**
     * Samples waypoints along a straight line at SAMPLE_INTERVAL spacing.
     */
    private static void sampleAlongLine(NoiseTerrainSampler sampler, BlockPos from, BlockPos to,
                                         List<CoarseWaypoint> waypoints, boolean includeLast) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double segDist = Math.sqrt(dx * dx + dz * dz);
        int sampleCount = Math.max(1, (int)(segDist / CoarseRoutePlanner.SAMPLE_INTERVAL));

        int limit = includeLast ? sampleCount : sampleCount;
        for (int s = 0; s < limit; s++) {
            double t = (double) s / sampleCount;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            int height = sampler.getBaseHeight(x, z);
            waypoints.add(new CoarseWaypoint(
                    new BlockPos(x, height, z), height, WaypointType.TERRAIN_FOLLOW));
        }
    }

    private record NoiseTerrainClassification(
            TerrainZoneAnalyzer.TerrainType type,
            boolean leftOpen,
            boolean rightOpen
    ) {}

    private static NoiseTerrainClassification classifyTerrainAtWaypoint(
            NoiseTerrainSampler sampler, CoarseWaypoint wp, CoarseWaypoint nextWp) {

        int trackY = wp.advisedTrackY();
        double dx = nextWp.position().getX() - wp.position().getX();
        double dz = nextWp.position().getZ() - wp.position().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) {
            return new NoiseTerrainClassification(TerrainZoneAnalyzer.TerrainType.OPEN, true, true);
        }

        double perpX = -dz / len;
        double perpZ = dx / len;
        double fwdX = dx / len;
        double fwdZ = dz / len;

        int aheadHeight = sampler.getBaseHeight(
                (int)(wp.position().getX() + fwdX * CoarseRoutePlanner.SAMPLE_INTERVAL),
                (int)(wp.position().getZ() + fwdZ * CoarseRoutePlanner.SAMPLE_INTERVAL));

        int verticalClearance = 8;
        if (aheadHeight > trackY + verticalClearance) {
            boolean leftOpen = isSideOpen(sampler, wp.position(), perpX, perpZ, trackY);
            boolean rightOpen = isSideOpen(sampler, wp.position(), -perpX, -perpZ, trackY);
            return new NoiseTerrainClassification(TerrainZoneAnalyzer.TerrainType.MOUNTAIN, leftOpen, rightOpen);
        }

        if (aheadHeight < trackY - RAVINE_DEPTH_THRESHOLD) {
            return new NoiseTerrainClassification(TerrainZoneAnalyzer.TerrainType.RAVINE, true, true);
        }

        return new NoiseTerrainClassification(TerrainZoneAnalyzer.TerrainType.OPEN, true, true);
    }

    private static boolean isSideOpen(NoiseTerrainSampler sampler, BlockPos center,
                                       double perpX, double perpZ, int trackY) {
        for (int i = 1; i <= PERP_SAMPLE_COUNT; i++) {
            int dist = i * PERP_SAMPLE_INTERVAL;
            int sampleX = center.getX() + (int)(perpX * dist);
            int sampleZ = center.getZ() + (int)(perpZ * dist);
            int height = sampler.getBaseHeight(sampleX, sampleZ);
            if (height > trackY + MOUNTAIN_HEIGHT_THRESHOLD) {
                return false;
            }
        }
        return true;
    }

    /**
     * Inserts curve waypoints around mountain zones and S-curve waypoints for cliff transitions.
     */
    static List<CoarseWaypoint> insertTerrainCurveWaypoints(
            NoiseTerrainSampler sampler, List<CoarseWaypoint> waypoints) {

        if (waypoints.size() < 3) return waypoints;

        int classifyInterval = 4;
        NoiseTerrainClassification[] classifications = new NoiseTerrainClassification[waypoints.size()];
        NoiseTerrainClassification lastSampled = null;

        for (int i = 0; i < waypoints.size() - 1; i++) {
            CoarseWaypoint wp = waypoints.get(i);
            CoarseWaypoint nextWp = waypoints.get(i + 1);

            int elevDelta = nextWp.advisedTrackY() - wp.advisedTrackY();
            if (elevDelta > CLIFF_DELTA_THRESHOLD) {
                classifications[i] = new NoiseTerrainClassification(
                        TerrainZoneAnalyzer.TerrainType.CLIFF_UP, true, true);
                continue;
            }
            if (elevDelta < -CLIFF_DELTA_THRESHOLD) {
                classifications[i] = new NoiseTerrainClassification(
                        TerrainZoneAnalyzer.TerrainType.CLIFF_DOWN, true, true);
                continue;
            }

            if (i % classifyInterval == 0) {
                lastSampled = classifyTerrainAtWaypoint(sampler, wp, nextWp);
            }

            classifications[i] = lastSampled != null ? lastSampled
                    : new NoiseTerrainClassification(TerrainZoneAnalyzer.TerrainType.OPEN, true, true);
        }
        classifications[waypoints.size() - 1] = classifications[waypoints.size() - 2];

        List<CoarseWaypoint> result = new ArrayList<>();
        int i = 0;

        while (i < waypoints.size()) {
            NoiseTerrainClassification cls = classifications[i];

            if (cls.type() == TerrainZoneAnalyzer.TerrainType.MOUNTAIN && (cls.leftOpen() || cls.rightOpen())) {
                int zoneStart = i;
                int zoneEnd = i;
                while (zoneEnd + 1 < waypoints.size() &&
                       classifications[zoneEnd + 1].type() == TerrainZoneAnalyzer.TerrainType.MOUNTAIN) {
                    zoneEnd++;
                }

                if (zoneEnd - zoneStart >= MIN_MOUNTAIN_ZONE_LENGTH) {
                    List<CoarseWaypoint> curveWps = createMountainCurveWaypoints(
                            sampler, waypoints, classifications, zoneStart, zoneEnd);
                    result.addAll(curveWps);
                    i = zoneEnd + 1;
                    continue;
                }
            }

            result.add(waypoints.get(i));
            i++;
        }

        return result;
    }

    private static List<CoarseWaypoint> createMountainCurveWaypoints(
            NoiseTerrainSampler sampler, List<CoarseWaypoint> waypoints,
            NoiseTerrainClassification[] classifications,
            int zoneStart, int zoneEnd) {

        CoarseWaypoint startWp = waypoints.get(zoneStart);
        CoarseWaypoint endWp = waypoints.get(Math.min(zoneEnd + 1, waypoints.size() - 1));

        double dx = endWp.position().getX() - startWp.position().getX();
        double dz = endWp.position().getZ() - startWp.position().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1) return List.of(startWp);

        double fwdX = dx / len;
        double fwdZ = dz / len;
        double[] perp = RouteGeometry.perpendicular(fwdX, fwdZ, -1.0);
        double perpX = perp[0];
        double perpZ = perp[1];

        int leftSampleX = startWp.position().getX() + (int)(perpX * CURVE_OFFSET);
        int leftSampleZ = startWp.position().getZ() + (int)(perpZ * CURVE_OFFSET);
        int rightSampleX = startWp.position().getX() - (int)(perpX * CURVE_OFFSET);
        int rightSampleZ = startWp.position().getZ() - (int)(perpZ * CURVE_OFFSET);

        int leftHeight = sampler.getBaseHeight(leftSampleX, leftSampleZ);
        int rightHeight = sampler.getBaseHeight(rightSampleX, rightSampleZ);

        NoiseTerrainClassification cls = classifications[zoneStart];
        boolean goLeft;
        if (cls.leftOpen() && !cls.rightOpen()) {
            goLeft = true;
        } else if (cls.rightOpen() && !cls.leftOpen()) {
            goLeft = false;
        } else {
            goLeft = leftHeight <= rightHeight;
        }

        double chosenPerpX = goLeft ? perpX : -perpX;
        double chosenPerpZ = goLeft ? perpZ : -perpZ;

        int midIdx = (zoneStart + zoneEnd) / 2;
        BlockPos midPos = waypoints.get(midIdx).position();

        int approachX = startWp.position().getX() + (int)(chosenPerpX * CURVE_OFFSET / 2);
        int approachZ = startWp.position().getZ() + (int)(chosenPerpZ * CURVE_OFFSET / 2);
        int approachY = sampler.getBaseHeight(approachX, approachZ);

        int bypassX = midPos.getX() + (int)(chosenPerpX * CURVE_OFFSET);
        int bypassZ = midPos.getZ() + (int)(chosenPerpZ * CURVE_OFFSET);
        int bypassY = sampler.getBaseHeight(bypassX, bypassZ);

        int returnX = endWp.position().getX() + (int)(chosenPerpX * CURVE_OFFSET / 2);
        int returnZ = endWp.position().getZ() + (int)(chosenPerpZ * CURVE_OFFSET / 2);
        int returnY = sampler.getBaseHeight(returnX, returnZ);

        List<CoarseWaypoint> curveWps = new ArrayList<>();
        curveWps.add(new CoarseWaypoint(
                new BlockPos(approachX, approachY, approachZ), approachY, WaypointType.CURVE_AROUND));
        curveWps.add(new CoarseWaypoint(
                new BlockPos(bypassX, bypassY, bypassZ), bypassY, WaypointType.CURVE_AROUND));
        curveWps.add(new CoarseWaypoint(
                new BlockPos(returnX, returnY, returnZ), returnY, WaypointType.CURVE_AROUND));
        return curveWps;
    }
}
