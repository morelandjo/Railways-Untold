package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.CoarseWaypoint;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute.WaypointType;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainProfile;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates a coarse route from head position to village target using noise terrain data.
 *
 * @see RouteObstacleAvoider
 * @see RouteTerrainClassifier
 * @see RouteBridgeTunnelDetector
 * @see RoutePostProcessor
 */
public class CoarseRoutePlanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result of an async coarse-route plan. Carries the route plus, when the
     * primary station-fit failed and an alternative succeeded, the selected
     * alternative - so the factory can update the head's locked plan to match
     * the route that was actually built.
     */
    public record PlanResult(
            PrecisionRoute route,
            @Nullable com.vodmordia.railwaysuntold.worldgen.village.StationPlan selectedAlternative
    ) {}

    static final int SAMPLE_INTERVAL = 8;

    /**
     * How far past the village body the approach runway starts, on the arrival side. Kept below
     * NoiseStationPlanner.ARRIVAL_OFFSET_BLOCKS (16) so the runway-start, which sits on the arrival axis at
     * the arrival's lateral offset, stays outside the body while still leaving the arrival pose reachable.
     */
    private static final int RUNWAY_SKIRT_MARGIN = 8;

    /**
     * Max Y-change per waypoint interval the route may hold, from the config slope ratio over the
     * interval's slope-effective distance (the span minus the straight endpoint blocks).
     */
    static int getMaxSlopePerSegment() {
        double maxRatio = RailwaysUntoldConfig.getMaxSlopeRatio();
        int effectiveDistance = SAMPLE_INTERVAL - SlopeValidator.STRAIGHT_ENDPOINT_BLOCKS;
        int maxSlope = (int) Math.floor(effectiveDistance * maxRatio);
        return Math.max(1, maxSlope);
    }

    /**
     * Plans a coarse route using pre-computed structure data.
     */
    /**
     * @param descentHintY If >= 0, the Y level of the eventual station entry below the
     *                     approach waypoint. The planner will tunnel downward through terrain
     *                     near the target to reach this Y. Use -1 for no hint (exploration routes).
     */
    static PlanResult planRouteOffThread(NoiseTerrainSampler sampler, BlockPos start,
                                                        BlockPos target, UUID headId,
                                                        Direction headDirection,
                                                        @Nullable Direction arrivalDirection,
                                                        List<PredictedStructure> avoidableStructures,
                                                        @Nullable ServerLevel level,
                                                        List<RouteObstacleAvoider.ExistingTrackCluster> existingTracks,
                                                        int descentHintY,
                                                        @Nullable com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout villageLayout,
                                                        @Nullable com.vodmordia.railwaysuntold.datapack.SelectedStation villageStation,
                                                        List<com.vodmordia.railwaysuntold.worldgen.village.StationPlan> alternatives,
                                                        boolean isFromTrackTip) {
        PrecisionRoute result = attemptSinglePlan(sampler, start, target, headId, headDirection,
                arrivalDirection, avoidableStructures, level, existingTracks, descentHintY,
                villageLayout, villageStation, isFromTrackTip);

        if (isTailAlignFailure(result) && alternatives != null && alternatives.size() > 1) {
            // alternatives[0] is the primary (already attempted via `target`); skip it.
            int maxRetries = Math.min(alternatives.size() - 1, 3);
            for (int i = 1; i <= maxRetries; i++) {
                com.vodmordia.railwaysuntold.worldgen.village.StationPlan alt = alternatives.get(i);
                if (RouteObstacleAvoider.segmentCrossesAvoidableStructure(
                        start, alt.arrivalPos(), avoidableStructures, level)) {
                    continue;
                }
                result = attemptSinglePlan(sampler, start, alt.arrivalPos(), headId, headDirection,
                        alt.arrivalDir(), avoidableStructures, level, existingTracks, alt.arrivalPos().getY(),
                        villageLayout, villageStation, isFromTrackTip);
                if (!isTailAlignFailure(result)) {
                    return new PlanResult(result, alt);
                }
            }
        }
        return new PlanResult(result, null);
    }

    private static boolean isTailAlignFailure(PrecisionRoute result) {
        if (result == null) return false;
        var path = result.getPrecisionPath();
        return path != null && !path.valid
                && path.invalidReasonCode == com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.InvalidReason.TAIL_ALIGN_FAILED;
    }

    /**
     * Runs the coarse-to-precision pipeline once for a single target and returns a
     * PrecisionRoute (the coarse route plus its compiled precision path). The outer
     * planRouteOffThread wraps this call with an alternative-arrival retry loop: when
     * the compile reports TAIL_ALIGN_FAILED for a village fit, it re-runs this method
     * against up to three alternative arrival positions.
     *
     * Step order is load-bearing because each step consumes the previous step's output.
     * Some steps return a new waypoint list whose result must be reassigned; others
     * mutate the passed list in place. The two kinds are called out per step because
     * the distinction is not visible from the call site.
     *
     * 1. step2_BuildAvoidancePath (RouteObstacleAvoider.buildPathWithAvoidance): start,
     *    target, avoidable structures and existing-track clusters produce a list of
     *    BlockPos path corners. Returns a new list. With no structures or tracks the
     *    result is just start then target.
     * 2. injectForwardAxisPrefix, only when isFromTrackTip: prepends an on-axis waypoint
     *    ahead of the head so the first compiled run matches headDirection. Returns a
     *    new list.
     * 3. injectArrivalAxisSuffix, only when arrivalDirection is set: appends an on-axis
     *    waypoint behind the target so the final run is cardinal-aligned. Returns a new
     *    list.
     * 4. step3_SampleTerrainAlongPath (RouteTerrainClassifier.sampleWaypointsAlongPath):
     *    samples noise terrain along the corners into CoarseWaypoints carrying an advised
     *    track Y. Returns a new list. An empty result short-circuits to an invalid route.
     * 5. anchorTerminalWaypoints: mutates the list in place. Pins the first waypoint to
     *    the station-exit Y, and when descentHintY is set also pins the last waypoint to
     *    target Y.
     * 6. step3_5_InsertTerrainCurves (RouteTerrainClassifier.insertTerrainCurveWaypoints):
     *    inserts curve waypoints around mountain zones and S-curve waypoints at cliff
     *    transitions. Returns a new list.
     * 7. step4_DetectBridgesAndTunnels (RouteBridgeTunnelDetector): builds a terrain
     *    profile, then applyBridgeDetection, applyTunnelDetection and
     *    applyWaterBridgeDetection mutate the waypoint list in place to tag BRIDGE and
     *    TUNNEL spans. Returns the profile, which is used only for the ocean-crossing log.
     * 8. step5_ValidateSlopes (RoutePostProcessor.enforceSlopeConstraints): bands movable
     *    waypoints between higher neighbours ahead and low pins, then fills any over-steep gap
     *    with ramp waypoints, so no segment exceeds the configured max slope. Returns a new list.
     * 9. step6_RemoveSelfIntersections (RoutePostProcessor.removeSelfIntersections): drops
     *    waypoints that double back on the route. Returns a new list.
     * 10. step6_5_SmoothLaterals (RoutePostProcessor.smoothLateralOscillations): removes
     *     small left-right zigzags. Returns a new list.
     * 11. step7_SmoothElevations (RoutePostProcessor.smoothElevationOscillations): removes
     *     small up-down bumps. Returns a new list.
     * 12. step8_CompilePrecisionRoute (PrecisionRouteCompiler.compile): turns the waypoints
     *     into precise PathSegments. Returns a PlannedPath.
     *
     * After step 12, when villageLayout and villageStation are both supplied and the compiled
     * path is valid, StationFitValidator checks whether the path's natural final pose yields a
     * station site tangent to the village. A failed fit replaces the path with an invalid one
     * carrying TAIL_ALIGN_FAILED, which is what triggers the outer alternative-arrival retry.
     */
    private static PrecisionRoute attemptSinglePlan(NoiseTerrainSampler sampler, BlockPos start,
                                                        BlockPos target, UUID headId,
                                                        Direction headDirection,
                                                        @Nullable Direction arrivalDirection,
                                                        List<PredictedStructure> avoidableStructures,
                                                        @Nullable ServerLevel level,
                                                        List<RouteObstacleAvoider.ExistingTrackCluster> existingTracks,
                                                        int descentHintY,
                                                        @Nullable com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout villageLayout,
                                                        @Nullable com.vodmordia.railwaysuntold.datapack.SelectedStation villageStation,
                                                        boolean isFromTrackTip) {
        // When approaching a village, route the avoidance to a runway start backed off past the village
        // body on the arrival side, not straight to the arrival pose. The avoidance then detours around
        // the body to a point already on the arrival side, and the cardinal runway leg from there into the
        // arrival lies alongside the body. Routing straight to the arrival drives the route into the body
        // and stops it short inside it - no buildable station, the village is abandoned, and the head
        // reverses onto its own approach curve.
        boolean villageApproach = arrivalDirection != null && villageLayout != null;
        BlockPos avoidanceTarget = villageApproach
                ? WaypointAxisInjector.runwayStartClearingBody(
                        target, arrivalDirection, villageLayout.totalBounds(), RUNWAY_SKIRT_MARGIN)
                : target;

        List<BlockPos> pathWaypoints = step2_BuildAvoidancePath(
                sampler, start, avoidanceTarget, avoidableStructures, level, existingTracks);

        if (isFromTrackTip) {
            pathWaypoints = WaypointAxisInjector.injectForwardAxisPrefix(pathWaypoints, start, headDirection);
        }

        if (villageApproach) {
            // The avoidance ends at the runway start on the arrival axis; append the arrival so the final
            // leg is the cardinal tangent runway into the village edge.
            if (!pathWaypoints.get(pathWaypoints.size() - 1).equals(target)) {
                pathWaypoints = new ArrayList<>(pathWaypoints);
                pathWaypoints.add(target);
            }
        } else if (arrivalDirection != null) {
            // Non-village fixed-direction routes keep the original short on-axis suffix.
            pathWaypoints = WaypointAxisInjector.injectArrivalAxisSuffix(pathWaypoints, target, arrivalDirection);
        }

        List<CoarseWaypoint> waypoints = step3_SampleTerrainAlongPath(sampler, pathWaypoints);
        int currentTrackY = start.getY();

        if (waypoints.isEmpty()) {
            LOGGER.warn("[COARSE-ROUTE] Empty terrain profile from {} to {}", start, target);
            CoarseRoute emptyRoute = new CoarseRoute(headId, List.of(
                    new CoarseWaypoint(start, start.getY(), WaypointType.TERRAIN_FOLLOW)));
            return new PrecisionRoute(emptyRoute, com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.invalid("Empty terrain profile"));
        }

        anchorTerminalWaypoints(waypoints, currentTrackY, target, descentHintY);

        waypoints = step3_5_InsertTerrainCurves(sampler, waypoints);

        step4_DetectBridgesAndTunnels(waypoints, sampler, currentTrackY);

        waypoints = step5_ValidateSlopes(waypoints);
        waypoints = step6_RemoveSelfIntersections(waypoints);
        waypoints = step6_5_SmoothLaterals(waypoints);
        waypoints = step7_SmoothElevations(waypoints);

        CoarseRoute route = new CoarseRoute(headId, waypoints);

        com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath precisionPath =
                step8_CompilePrecisionRoute(waypoints, start, target, headDirection, isFromTrackTip);

        // Validate that the compile's natural final pose produces a usable station site
        // for the target village. Accept whatever direction the natural compile ended in
        // (track-first commit rotates the station to match) as long as the resulting
        // station would fit tangent to the village. Failure marks the route invalid
        // with TAIL_ALIGN_FAILED; the orchestrator abandons the village and picks another.
        if (villageLayout != null && villageStation != null && precisionPath.valid) {
            com.vodmordia.railwaysuntold.worldgen.village.StationFitValidator.Result fit =
                    com.vodmordia.railwaysuntold.worldgen.village.StationFitValidator.validate(
                            precisionPath.finalPosition, precisionPath.finalDirection,
                            villageLayout, villageStation);
            if (!fit.ok()) {
                LOGGER.warn("[STATION-FIT] fail pos={} dir={} - {}",
                        precisionPath.finalPosition, precisionPath.finalDirection, fit.reason());
                precisionPath = com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.invalid(
                        com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.InvalidReason.TAIL_ALIGN_FAILED,
                        fit.reason());
            }
        }

        return new PrecisionRoute(route, precisionPath);
    }


    private static List<BlockPos> step2_BuildAvoidancePath(NoiseTerrainSampler sampler,
                                                            BlockPos start, BlockPos target,
                                                            List<PredictedStructure> avoidableStructures,
                                                            @Nullable ServerLevel level,
                                                            List<RouteObstacleAvoider.ExistingTrackCluster> existingTracks) {
        return RouteObstacleAvoider.buildPathWithAvoidance(
                sampler, start, target, avoidableStructures, level, existingTracks);
    }

    private static List<CoarseWaypoint> step3_SampleTerrainAlongPath(NoiseTerrainSampler sampler,
                                                                       List<BlockPos> pathWaypoints) {
        return RouteTerrainClassifier.sampleWaypointsAlongPath(sampler, pathWaypoints);
    }

    /**
     * Anchors the first waypoint to the station-exit Y so the route starts at the actual
     * track height rather than noise-sampled terrain (without this, elevated terrain near
     * the exit pushes bridgeY above the station track, creating an impassable vertical
     * gap). Also anchors the last waypoint to target.getY() when routing to a station
     * (descentHintY >= 0) so terrain-following waypoints can't pull the route
     * away from the station entry Y. Exploration routes skip the last-waypoint anchor
     * and let terrain shape the endpoint naturally.
     */
    private static void anchorTerminalWaypoints(List<CoarseWaypoint> waypoints,
                                                  int currentTrackY, BlockPos target,
                                                  int descentHintY) {
        CoarseWaypoint firstWp = waypoints.get(0);
        if (firstWp.advisedTrackY() != currentTrackY) {
            waypoints.set(0, new CoarseWaypoint(
                    new BlockPos(firstWp.position().getX(), currentTrackY, firstWp.position().getZ()),
                    currentTrackY, firstWp.type(), CoarseRoute.YBasis.CONSTRAINT));
        }

        if (descentHintY >= 0) {
            int targetY = target.getY();
            CoarseWaypoint lastWp = waypoints.get(waypoints.size() - 1);
            if (lastWp.advisedTrackY() != targetY) {
                waypoints.set(waypoints.size() - 1, new CoarseWaypoint(
                        new BlockPos(lastWp.position().getX(), targetY, lastWp.position().getZ()),
                        targetY, lastWp.type(), CoarseRoute.YBasis.CONSTRAINT));
            }
        }
    }

    private static List<CoarseWaypoint> step3_5_InsertTerrainCurves(NoiseTerrainSampler sampler,
                                                                      List<CoarseWaypoint> waypoints) {
        return RouteTerrainClassifier.insertTerrainCurveWaypoints(sampler, waypoints);
    }

    private static NoiseTerrainProfile step4_DetectBridgesAndTunnels(List<CoarseWaypoint> waypoints,
                                                                        NoiseTerrainSampler sampler,
                                                                        int currentTrackY) {
        NoiseTerrainProfile profile = RouteBridgeTunnelDetector.buildProfileFromWaypoints(waypoints);
        RouteBridgeTunnelDetector.applyBridgeDetection(waypoints, profile, currentTrackY);
        RouteBridgeTunnelDetector.applyTunnelDetection(waypoints, profile, currentTrackY);
        RouteBridgeTunnelDetector.applyWaterBridgeDetection(waypoints, sampler);
        return profile;
    }

    private static List<CoarseWaypoint> step5_ValidateSlopes(List<CoarseWaypoint> waypoints) {
        return RoutePostProcessor.enforceSlopeConstraints(waypoints);
    }

    private static List<CoarseWaypoint> step6_RemoveSelfIntersections(List<CoarseWaypoint> waypoints) {
        return RoutePostProcessor.removeSelfIntersections(waypoints);
    }

    private static List<CoarseWaypoint> step6_5_SmoothLaterals(List<CoarseWaypoint> waypoints) {
        return RoutePostProcessor.smoothLateralOscillations(waypoints);
    }

    private static List<CoarseWaypoint> step7_SmoothElevations(List<CoarseWaypoint> waypoints) {
        return RoutePostProcessor.smoothElevationOscillations(waypoints);
    }

    private static com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath step8_CompilePrecisionRoute(
            List<CoarseWaypoint> waypoints,
            BlockPos start, BlockPos target, Direction headDirection, boolean isFromTrackTip) {
        return PrecisionRouteCompiler.compile(waypoints, start, target, headDirection, isFromTrackTip);
    }
}
