package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects bridge and tunnel opportunities in a coarse route's waypoint sequence.
 */
final class RouteBridgeTunnelDetector {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PEAK_RISE_THRESHOLD = 25;
    private static final int MAX_TUNNEL_SPAN = 384;

    /**
     * Builds a NoiseTerrainProfile from existing waypoints for camel hump detection.
     */
    static NoiseTerrainProfile buildProfileFromWaypoints(List<CoarseWaypoint> waypoints) {
        List<NoiseTerrainProfile.SamplePoint> samples = new ArrayList<>(waypoints.size());
        for (CoarseWaypoint wp : waypoints) {
            samples.add(new NoiseTerrainProfile.SamplePoint(
                    wp.position().getX(), wp.position().getZ(),
                    wp.advisedTrackY(), 0.0, 0.0, false));
        }
        return new NoiseTerrainProfile(samples, CoarseRoutePlanner.SAMPLE_INTERVAL);
    }

    static void applyBridgeDetection(List<CoarseWaypoint> waypoints,
                                      NoiseTerrainProfile profile, int currentTrackY) {
        List<CamelHumpDetector.DipRegion> dips = CamelHumpDetector.detectCamelHumps(profile, currentTrackY);
        for (CamelHumpDetector.DipRegion dip : dips) {
            // Span a straight grade between the two rim heights instead of flattening to the
            // higher one - a flat-at-the-high-rim bridge forces the low rim to ramp up to meet
            // it (a drop-flat-climb that slope validation then has to re-ramp). The rims are
            // local edge heights, NOT the global station height (which set far-off bridges at
            // cliff height over flat terrain). Read the rims from the immutable terrain profile,
            // not the waypoint list, so they stay the original terrain heights regardless of what
            // an adjacent bridge/tunnel region already wrote into the list.
            int startEdgeY = edgeHeight(profile, dip.startIndex());
            int endEdgeY = (dip.endIndex() < waypoints.size() - 1)
                    ? edgeHeight(profile, dip.endIndex() + 1)
                    : edgeHeight(profile, dip.endIndex());
            int span = dip.endIndex() - dip.startIndex();
            for (int i = dip.startIndex(); i <= dip.endIndex() && i < waypoints.size(); i++) {
                int bridgeY = span <= 0 ? Math.max(startEdgeY, endEdgeY)
                        : (int) Math.round(startEdgeY
                                + (double) (i - dip.startIndex()) / span * (endEdgeY - startEdgeY));
                CoarseWaypoint old = waypoints.get(i);
                waypoints.set(i, new CoarseWaypoint(old.position(), bridgeY, WaypointType.BRIDGE, CoarseRoute.YBasis.CONSTRAINT));
            }
            LOGGER.debug("[COARSE-ROUTE] Marked BRIDGE region at indices {}-{}, depth={}, span={}",
                    dip.startIndex(), dip.endIndex(), dip.dipDepth(), dip.spanLength());
        }
    }

    static void applyTunnelDetection(List<CoarseWaypoint> waypoints,
                                      NoiseTerrainProfile profile, int currentTrackY) {
        List<CamelHumpDetector.DipRegion> peaks = CamelHumpDetector.detectPeaks(
                profile, currentTrackY, PEAK_RISE_THRESHOLD);
        for (CamelHumpDetector.DipRegion peak : peaks) {
            if (peak.spanLength() > MAX_TUNNEL_SPAN) {
                LOGGER.debug("[COARSE-ROUTE] Skipping peak at indices {}-{}: span {} too large for tunnel",
                        peak.startIndex(), peak.endIndex(), peak.spanLength());
                continue;
            }
            // Bore a straight grade between the two portal heights instead of flattening to
            // the lower one - a flat-at-the-low-portal tunnel forces the high portal to drop
            // to meet it (an extra elevation change slope validation then re-ramps). The
            // portals are local edge heights, not the global station height. Read the portals
            // from the immutable terrain profile, not the waypoint list, so a bridge region the
            // earlier pass already wrote can't inflate this tunnel's portal heights.
            int startEdgeY = edgeHeight(profile, peak.startIndex());
            int endEdgeY = (peak.endIndex() < waypoints.size() - 1)
                    ? edgeHeight(profile, peak.endIndex() + 1)
                    : edgeHeight(profile, peak.endIndex());
            int span = peak.endIndex() - peak.startIndex();
            for (int i = peak.startIndex(); i <= peak.endIndex() && i < waypoints.size(); i++) {
                CoarseWaypoint old = waypoints.get(i);
                if (old.type() != WaypointType.BRIDGE) {
                    int tunnelY = span <= 0 ? Math.min(startEdgeY, endEdgeY)
                            : (int) Math.round(startEdgeY
                                    + (double) (i - peak.startIndex()) / span * (endEdgeY - startEdgeY));
                    waypoints.set(i, new CoarseWaypoint(old.position(), tunnelY, WaypointType.TUNNEL, CoarseRoute.YBasis.CONSTRAINT));
                }
            }
            LOGGER.debug("[COARSE-ROUTE] Marked TUNNEL region at indices {}-{}, rise={}, span={}",
                    peak.startIndex(), peak.endIndex(), peak.dipDepth(), peak.spanLength());
        }
    }

    /**
     * Terrain height at a profile sample index - the original sampled Y, before any
     * bridge/tunnel pass rewrote the corresponding waypoint. The profile is parallel
     * to the waypoint list (same indices) and immutable, so edge lookups against it are
     * independent of which detection pass ran first.
     */
    private static int edgeHeight(NoiseTerrainProfile profile, int index) {
        return profile.getSamples().get(index).height();
    }

    /**
     * Detects water crossings (oceans and rivers) and marks them as BRIDGE waypoints
     * with height set to seaLevel + bridgeWaterDistance.
     */
    static void applyWaterBridgeDetection(List<CoarseWaypoint> waypoints,
                                           NoiseTerrainSampler sampler) {
        int bridgeWaterDistance = RailwaysUntoldConfig.getBridgeWaterDistance();
        if (bridgeWaterDistance <= 0) return;

        int seaLevel = sampler.getSeaLevel();
        int bridgeY = seaLevel + bridgeWaterDistance;

        int regionStart = -1;
        for (int i = 0; i <= waypoints.size(); i++) {
            boolean isWater = false;
            if (i < waypoints.size()) {
                CoarseWaypoint wp = waypoints.get(i);
                int x = wp.position().getX();
                int z = wp.position().getZ();
                isWater = sampler.isLikelyWater(x, z);
            }

            if (isWater) {
                if (regionStart == -1) regionStart = i;
            } else {
                if (regionStart != -1) {
                    int regionEnd = i - 1;
                    int extStart = Math.max(0, regionStart - 2);
                    int extEnd = Math.min(waypoints.size() - 1, regionEnd + 2);
                    for (int j = extStart; j <= extEnd; j++) {
                        CoarseWaypoint old = waypoints.get(j);
                        if (old.type() == WaypointType.BRIDGE && old.advisedTrackY() >= bridgeY) {
                            continue;
                        }
                        // Water bridge height is seaLevel + configured distance - don't inflate
                        // with adjacent edge heights (a nearby mountain shouldn't raise a water bridge).
                        int advisedY = Math.max(bridgeY, old.advisedTrackY());
                        waypoints.set(j, new CoarseWaypoint(old.position(), advisedY, WaypointType.BRIDGE, CoarseRoute.YBasis.CONSTRAINT));
                    }
                    regionStart = -1;
                }
            }
        }
    }
}
