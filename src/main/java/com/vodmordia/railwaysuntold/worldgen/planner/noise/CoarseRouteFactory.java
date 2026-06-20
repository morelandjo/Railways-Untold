package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.ServerTickHandler;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.core.TrackLogger;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coarse route creation: plan + register + attach + persist.
 */
public final class CoarseRouteFactory {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile ExecutorService routePlannerExecutor;

    /**
     * Post-plan validation caps out at this many replan passes.
     */
    private static final int MAX_VALIDATION_ITERATIONS = 2;

    /**
     * Per-head generation counter. Each call to createAndAttach increments the counter
     * for that head.
     */
    private static final Map<UUID, AtomicLong> routeGenerations = new ConcurrentHashMap<>();

    /**
     * The last plan's [REPLAY] record per head, captured on every plan (not just when verbose). When a
     * head later hits an anomaly (HALT / aborted plan-apply), this is the record emitted so the failure
     * reproduces with no world - without the user having pre-enabled verbose logging.
     */
    static final Map<UUID, ReplayRecord> lastReplayByHead = new ConcurrentHashMap<>();

    /**
     * Heads that have already emitted an anomaly [REPLAY] for the current stuck episode. Cleared by
     * {@link #notifyHeadProgress} on real placement progress, so a replanning storm dumps one record,
     * not one per failed retry, while a genuinely new anomaly after recovery still emits.
     */
    static final Set<UUID> replayAnomalyGate = ConcurrentHashMap.newKeySet();

    /**
     * The authoritative [COMPILE] record per head - the precision compiler's exact inputs (waypoints) for
     * the route the executor actually places, captured on every recompile (not just when verbose). On an
     * anomaly it's emitted alongside the [REPLAY] so a track-geometry failure (a sub-radius curve, a
     * disconnected seam - the kind a CURVE/ELEVATED_TUNNEL placement failure produces) reproduces through
     * CompileCaseTest with no world. [REPLAY] gives the planner inputs; [COMPILE] gives the compiled route.
     */
    static final Map<UUID, CompileRecord> lastCompileByHead = new ConcurrentHashMap<>();
    static final Set<UUID> compileAnomalyGate = ConcurrentHashMap.newKeySet();

    private CoarseRouteFactory() {
    }

    private static ExecutorService getExecutor() {
        ExecutorService exec = routePlannerExecutor;
        if (exec == null || exec.isShutdown()) {
            synchronized (CoarseRouteFactory.class) {
                exec = routePlannerExecutor;
                if (exec == null || exec.isShutdown()) {
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "RailwaysUntold-RoutePlanner");
                        t.setDaemon(true);
                        return t;
                    });
                    routePlannerExecutor = exec;
                }
            }
        }
        return exec;
    }

    /**
     * Creates a coarse route asynchronously from the head's current position to the target.
     *
     * @param level  The server level
     * @param head   The expansion head
     * @param target The target position (village edge, exploration target, or custom target)
     * @return null (route is applied asynchronously; kept for API compatibility)
     */
    public static CoarseRoute createAndAttach(ServerLevel level, TrackExpansionHead head, BlockPos target) {
        createAndAttachInternal(level, head, target, List.of(), 0);
        return null;
    }

    /**
     * Plans a route on the planner thread and installs it on the server thread one tick
     * later. This is not a synchronous call: the work crosses a thread hop (the executor)
     * and a tick boundary (ServerTickHandler.scheduleDelayed), so the method returns long
     * before the route is applied.
     *
     * A per-head generation counter guards against stale results. Every invocation
     * increments the head's generation before dispatching; when the scheduled apply runs it
     * compares the generation it captured against the current one and discards itself if a
     * newer plan has been dispatched in the meantime. That newer plan owns the
     * routeRebuilding flag and will clear it.
     *
     * Post-plan validation can trigger a replan: if avoidance is on and the planned
     * waypoints reveal structures the initial bounding-box scan missed, this method calls
     * itself with those structures merged in and iteration incremented. That call is itself
     * a fresh async dispatch (new generation, new executor task, new scheduled tick) - not an
     * in-place loop - so threading, tick ordering and the staleness gate all still hold.
     * iteration caps the number of replan passes; past the cap a final scan runs only for
     * logging and the best-effort route is installed.
     *
     * On any failure the routeRebuilding flag is deliberately left set: a halted head is
     * safer than a half-applied route, and the next replan event re-sets and clears it.
     */
    private static void createAndAttachInternal(ServerLevel level, TrackExpansionHead head, BlockPos target,
                                                List<PredictedStructure> extraStructures, int iteration) {
        UUID headId = head.getHeadId();
        int headNumber = head.getHeadNumber();
        CoarseRouteRegistry registry = CoarseRouteRegistry.forLevel(level);
        CoarseRouteSavedData savedData = CoarseRouteSavedData.get(level);

        // PLANNING GATE: mark the head as rebuilding its route before dispatching the
        // async worker.
        head.getTerrainPlanState().setRouteRebuilding(true);

        if (registry.getRoute(headId) != null) {
            registry.removeRoute(headId);
            savedData.removeRoute(headId);
        }

        BlockPos headPos = head.getPosition();

        // Seed the replan from the physical rail, not from whatever planner-node the
        // previous placement wrote into head.getPosition(). 
        BlockPos tip = com.vodmordia.railwaysuntold.worldgen.tracking.PhysicalTrackTipLocator
                .findTipOrFallback(level, head, headPos);
        if (!tip.equals(headPos)) {
            LOGGER.warn("[TIP-REANCHOR] Head {} at {}: snapping to physical tip {} (delta dx={} dy={} dz={}) before replan to {}",
                    headNumber, headPos, tip,
                    tip.getX() - headPos.getX(), tip.getY() - headPos.getY(), tip.getZ() - headPos.getZ(),
                    target);
            head.setPosition(tip);
        }

        BlockPos routeStart = tip;
        Direction headDir = head.getDirection();
        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        // When routing to a locked station arrival pose, extract the arrival Y as a
        // "descent hint" (so terrain routing aims for the planned Y instead of following
        // the surface up over a cliff), plus the village layout and selected station so
        // the precision compiler can validate that its natural final pose produces a
        // usable station site. 
        int descentHintY = -1;
        com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout villageLayout = null;
        com.vodmordia.railwaysuntold.datapack.SelectedStation villageStation = null;
        java.util.List<com.vodmordia.railwaysuntold.worldgen.village.StationPlan> alternatives = java.util.List.of();
        Direction arrivalDirection = null;
        var lockedPlan = head.getVillageState().getLockedStationPlan();
        if (lockedPlan != null && target.equals(lockedPlan.arrivalPos())) {
            descentHintY = lockedPlan.arrivalPos().getY();
            villageLayout = head.getVillageState().getPrecomputedLayout();
            villageStation = head.getVillageState().getSelectedStation();
            alternatives = java.util.List.copyOf(head.getVillageState().getStationPlanAlternatives());
            arrivalDirection = lockedPlan.arrivalDir();
        }
        final int finalDescentHintY = descentHintY;
        final com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout finalVillageLayout = villageLayout;
        final com.vodmordia.railwaysuntold.datapack.SelectedStation finalVillageStation = villageStation;
        final java.util.List<com.vodmordia.railwaysuntold.worldgen.village.StationPlan> finalAlternatives = alternatives;
        final Direction finalArrivalDirection = arrivalDirection;
        final BlockPos finalTarget = target;

        List<PredictedStructure> structures = RailwaysUntoldConfig.isAllAvoidanceEnabled()
                ? new ArrayList<>(RouteObstacleAvoider.scanStructures(level, routeStart, finalTarget, sampler))
                : new ArrayList<>();

        // Merge in any conflicts discovered by a previous iteration's post-plan validation,
        // deduped by chunk so the planner doesn't see the same structure twice.
        mergeStructuresDedupedByChunk(structures, extraStructures);

        // When routing to a committed station, replace the loosely-scanned target village (a radius circle
        // around its center) with a tight footprint of its built area. The approach must skirt the body and
        // reach the flank arrival from outside, not drive into the body and stop short inside it - but it
        // still ends at the arrival pose on the village edge, so only the body itself is avoided. Pairs with
        // routing the avoidance to the runway start past the body (CoarseRoutePlanner).
        if (lockedPlan != null && head.getVillageState().hasTargetVillage()) {
            BlockPos villageCenter = head.getVillageState().getTargetVillageCenter();
            if (villageCenter != null) {
                structures.removeIf(s -> s.approximateCenter().distManhattan(villageCenter) < 200);
                if (finalVillageLayout != null) {
                    java.util.List<net.minecraft.world.level.levelgen.structure.BoundingBox> body =
                            !finalVillageLayout.pieceBounds().isEmpty()
                                    ? finalVillageLayout.pieceBounds()
                                    : java.util.List.of(finalVillageLayout.totalBounds());
                    structures.add(new PredictedStructure(
                            new net.minecraft.world.level.ChunkPos(villageCenter),
                            villageCenter, "target_village_skirt", java.util.List.of(), false, body));
                }
            }
        }

        final List<PredictedStructure> plannerStructures = structures;

        List<RouteObstacleAvoider.ExistingTrackCluster> corridors = RailwaysUntoldConfig.isAllAvoidanceEnabled()
                ? RouteObstacleAvoider.collectExistingTrackClusters(level, routeStart, finalTarget, headId)
                : List.of();

        // Recovery flag: if this head has placed any segment, routeStart is the tip
        // of real track and the coarse planner must keep the first leg on-axis so
        // the precision compiler does not emit an alignment curve at the tip.
        final boolean isFromTrackTip = head.hasPlacedAnySegments();

        long generation = routeGenerations
                .computeIfAbsent(headId, k -> new AtomicLong())
                .incrementAndGet();

        getExecutor().submit(() -> {
            // Always record every terrain height/water query the planner makes so a [REPLAY] record can
            // reproduce this exact plan with no world: emitted immediately when verbose logging is on,
            // and stored so this head can dump it later on an anomaly (HALT / abort) even if the user
            // never enabled verbose. Capturing is a cheap per-thread map on the planner thread.
            NoiseTerrainSampler.beginCapture();
            NoiseTerrainSampler.Capture capture = null;
            try {
                // Run noise terrain sampling off the server thread.
                // Pass level=null here since ConnectedBoundaryTracker isn't thread-safe.
                CoarseRoutePlanner.PlanResult planResult;
                try {
                    planResult = CoarseRoutePlanner.planRouteOffThread(
                            sampler, routeStart, finalTarget, headId, headDir, finalArrivalDirection, structures, null, corridors,
                            finalDescentHintY, finalVillageLayout, finalVillageStation, finalAlternatives, isFromTrackTip);
                } finally {
                    capture = NoiseTerrainSampler.endCapture();
                }
                ReplayRecord replay = buildReplayRecord(routeStart, finalTarget, headId, headDir,
                        finalArrivalDirection, finalDescentHintY, isFromTrackTip, sampler, capture, structures, corridors);
                lastReplayByHead.put(headId, replay);
                if (TrackLogger.isVerboseEnabled()) {
                    logReplayRecord(headNumber, replay, null);
                }
                // The compiler rejects routes with an illegal seam (a >=90° kink between consecutive
                // segments that no curve absorbs). Emit the [REPLAY] record tagged ILLEGAL-SEAM so the
                // scenario is greppable among the routine records and reproduces deterministically
                // through the test harness for a fix.
                PlannedPath offThreadPath = (planResult != null && planResult.route() != null)
                        ? planResult.route().getPrecisionPath() : null;
                if (offThreadPath != null
                        && offThreadPath.invalidReasonCode == PlannedPath.InvalidReason.ILLEGAL_SEAM) {
                    logReplayRecord(headNumber, replay, "ILLEGAL-SEAM (" + offThreadPath.invalidReason + ")");
                }
                // Off-thread compile result. Its precision path already drove station-fit and
                // alternative-arrival selection inside planRouteOffThread; here it serves as
                // diagnostic input and as the fallback installed when the authoritative on-thread
                // recompile (below) comes back invalid.
                PrecisionRoute offThreadRoute = planResult.route();
                com.vodmordia.railwaysuntold.worldgen.village.StationPlan selectedAlternative = planResult.selectedAlternative();
                CoarseRoute route = offThreadRoute.getCoarseRoute();


                // Apply result on the server thread next tick.
                ServerTickHandler.scheduleDelayed(() -> {
                    try {
                        // Check if a newer route plan has been submitted since this one started.
                        // If so, discard this result - a newer task owns the routeRebuilding
                        // flag and will clear it when IT completes. Don't touch the flag here.
                        AtomicLong currentGen = routeGenerations.get(headId);
                        if (currentGen != null && currentGen.get() != generation) {
                            return;
                        }

                        // Placed-track avoidance and route-vs-route conflict resolution both
                        // run inside registerRoute below (RouteConflictAnalyzer/Resolver +
                        // PlacedTrackConflictDetector), mutating this route's waypoints in place.
                        CoarseRoute adjusted = route;

                        // Post-plan validation: scan the actual planned waypoints for structures
                        // the initial bounding-box scan missed (typically because detours pushed
                        // the route sideways out of the start->target rectangle). If any show up,
                        // replan once with them merged in - capped so we can't loop forever.
                        if (RailwaysUntoldConfig.isAllAvoidanceEnabled() && iteration < MAX_VALIDATION_ITERATIONS) {
                            List<PredictedStructure> newConflicts = validateRouteAgainstStructures(
                                    level, adjusted, plannerStructures, sampler, head);
                            if (!newConflicts.isEmpty()) {
                                List<PredictedStructure> merged = new ArrayList<>(plannerStructures);
                                merged.addAll(newConflicts);
                                // The recursive call increments generation and re-dispatches async.
                                // Do not install the current route or clear the routeRebuilding
                                // flag - the new plan will own both.
                                createAndAttachInternal(level, head, target, merged, iteration + 1);
                                return;
                            }
                        }

                        registry.registerRoute(headId, adjusted, level);

                        // Authoritative on-thread compile. The off-thread compile ran before
                        // registerRoute mutated waypoints via RouteConflictResolver /
                        // PlacedTrackConflictDetector. The executor follows this recompile against
                        // the adjusted waypoints; without it the executor would use a precision
                        // route that ignores those adjustments, producing repeated runtime
                        // collisions against placed track at the same head position.
                        PlannedPath recompiledPath = PrecisionRouteCompiler.compile(
                                adjusted.getWaypoints(), routeStart, finalTarget, headDir, isFromTrackTip);
                        // Capture the authoritative compile inputs so a later placement failure can dump a
                        // reproducible [COMPILE] record (always-on, gated like [REPLAY]) - this is the exact
                        // route the executor follows.
                        lastCompileByHead.put(headId, new CompileRecord(
                                routeStart, finalTarget, headDir, isFromTrackTip, adjusted.getWaypoints()));
                        PrecisionRoute installedRoute = selectInstalledPrecisionRoute(
                                offThreadRoute, adjusted, recompiledPath, headNumber);

                        // Skirt-overdrive guard: the corridor avoids the target village body, but the
                        // arrival maneuver can curve back and cut across it. Reject any route whose
                        // compiled path enters the body and abandon the village (handleTailAlignFailure),
                        // so the head never lays track over the houses.
                        installedRoute = rejectIfEntersVillageBody(installedRoute, adjusted, plannerStructures, headNumber);

                        head.getTerrainPlanState().setCoarseRoute(adjusted);
                        head.getTerrainPlanState().setPrecisionRoute(installedRoute);
                        savedData.addRoute(headId, adjusted);

                        // If station-fit fell back to an alternative arrival, the route was
                        // built for that alternative - sync the head's village state so
                        // downstream readers (route-target lookup, station commit, replan)
                        // see the same target the route was actually planned to.
                        if (selectedAlternative != null) {
                            head.getVillageState().setLockedStationPlan(selectedAlternative);
                            head.getVillageState().setApproachWaypoint(selectedAlternative.arrivalPos());
                        }

                        // Gate released: new plan is installed, the orchestrator can
                        // resume placing blocks on the next tick.
                        head.getTerrainPlanState().setRouteRebuilding(false);
                    } catch (Exception e) {
                        // Leave routeRebuilding=TRUE on exception. A broken plan-apply
                        // is worse than a halted head - require an external replan to
                        // recover. The flag will be re-set and cleared by the next
                        // createAndAttach call.
                        LOGGER.error("[COARSE-FACTORY] Failed to apply async route for head {}: {} - head will stay halted until manual replan",
                                headNumber, e.getMessage());
                        emitReplayOnAnomaly(headId, headNumber, "APPLY-ABORT");
                    }
                }, 1);

            } catch (Exception e) {
                // If terrain was captured before the failure, store a record of the failed plan so the
                // anomaly emit below reproduces it.
                if (capture != null) {
                    lastReplayByHead.put(headId, buildReplayRecord(routeStart, finalTarget, headId, headDir,
                            finalArrivalDirection, finalDescentHintY, isFromTrackTip, sampler, capture, structures, corridors));
                }
                // Same as above: don't clear the flag; let the next replan event
                // re-set and clear it.
                LOGGER.error("[COARSE-FACTORY] Async route planning failed for head {}: {} - head will stay halted until manual replan",
                        headNumber, e.getMessage(), e);
                emitReplayOnAnomaly(headId, headNumber, "PLAN-ABORT");
            }
        });
    }

    /**
     * Chooses which precision route to install. The on-thread recompile against the
     * conflict-adjusted waypoints is authoritative when valid; when it comes back invalid the
     * off-thread route (already validated for station fit) is kept as the fallback.
     */
    static PrecisionRoute selectInstalledPrecisionRoute(
            PrecisionRoute offThreadRoute, CoarseRoute adjusted, PlannedPath recompiledPath, int headNumber) {
        if (recompiledPath.valid) {
            return new PrecisionRoute(adjusted, recompiledPath);
        }
        LOGGER.warn("[COARSE-FACTORY] Head {} precision recompile returned invalid path; keeping original precision route",
                headNumber);
        return offThreadRoute;
    }

    /**
     * Rejects the route if its compiled path enters the target village body (the {@code target_village_skirt}
     * footprint), returning a {@code SKIRT_OVERDRIVE}-invalid route so the orchestrator abandons the village
     * rather than laying track over it. Returns the route unchanged when there is no skirt body or the route
     * stays clear.
     */
    private static PrecisionRoute rejectIfEntersVillageBody(PrecisionRoute installedRoute, CoarseRoute adjusted,
                                                            List<PredictedStructure> plannerStructures, int headNumber) {
        if (installedRoute == null) return installedRoute;
        PlannedPath path = installedRoute.getPrecisionPath();
        if (path == null || !path.valid || path.segments == null) return installedRoute;

        List<net.minecraft.world.level.levelgen.structure.BoundingBox> skirtBody = null;
        for (PredictedStructure s : plannerStructures) {
            if ("target_village_skirt".equals(s.structureSetName())
                    && s.footprint() != null && !s.footprint().isEmpty()) {
                skirtBody = s.footprint();
                break;
            }
        }
        if (skirtBody == null) return installedRoute;

        int seg = SkirtBodyIntrusionInspector.findBodyIntrusion(path.segments, skirtBody);
        if (seg < 0) return installedRoute;

        LOGGER.warn("[SKIRT-OVERDRIVE] Head {} route enters the target village body at segment {} - rejecting route, abandoning village",
                headNumber, seg);
        return new PrecisionRoute(adjusted, PlannedPath.invalidWithSegments(
                PlannedPath.InvalidReason.SKIRT_OVERDRIVE, "approach enters target village body", path.segments));
    }

    /**
     * Adds every structure from extra whose chunk is not already represented in into,
     * deduping by chunk position. Mutates into in place.
     */
    static void mergeStructuresDedupedByChunk(List<PredictedStructure> into,
                                              List<PredictedStructure> extra) {
        if (extra.isEmpty()) return;
        Set<Long> seen = new HashSet<>();
        for (PredictedStructure ps : into) {
            seen.add(ps.chunkPos().toLong());
        }
        for (PredictedStructure ps : extra) {
            if (seen.add(ps.chunkPos().toLong())) {
                into.add(ps);
            }
        }
    }

    /**
     * Builds the machine-parseable [REPLAY] record for a plan: the planner inputs, the terrain
     * heights/ocean/water it sampled, the structures and existing-track clusters it routed around, and
     * the config slope, so the route reproduces deterministically with no world (see ReplayRoundTripTest
     * / ReplayCaseTest).
     */
    static ReplayRecord buildReplayRecord(BlockPos start, BlockPos target, UUID headId, Direction headDir,
                                          Direction arrivalDir, int descentHintY, boolean isFromTrackTip,
                                          NoiseTerrainSampler sampler, NoiseTerrainSampler.Capture capture,
                                          List<PredictedStructure> structures,
                                          List<RouteObstacleAvoider.ExistingTrackCluster> tracks) {
        return new ReplayRecord(start, target, headId, headDir, arrivalDir, descentHintY,
                isFromTrackTip, sampler.getSeaLevel(), RailwaysUntoldConfig.getMaxSlopeRatio(),
                capture.heights(), structures, tracks, capture.oceanCoords(), capture.waterCoords());
    }

    /** Logs a [REPLAY] record; {@code reason} tags an anomaly-triggered emit (null for the verbose emit). */
    private static void logReplayRecord(int headNumber, ReplayRecord rec, String reason) {
        LOGGER.info("[REPLAY] head {} start {} target {} ({} heights, {} structures, {} tracks, {} ocean, {} water{})\n{}",
                headNumber, rec.start.toShortString(), rec.target.toShortString(), rec.heights.size(),
                rec.structures.size(), rec.tracks.size(), rec.oceanCoords.size(), rec.waterCoords.size(),
                reason == null ? "" : ", reason=" + reason, rec.serialize());
    }

    /**
     * Emits the head's last captured plan as a [REPLAY] record when it hits an anomaly (HALT / aborted
     * plan-apply / stuck-head recovery), so a halted head produces a reproduction even when verbose
     * logging was never enabled. Emits once per stuck episode - the gate clears on the next real
     * placement progress ({@link #notifyHeadProgress}) - so a replanning storm dumps one record, not one
     * per retry.
     */
    public static void emitReplayOnAnomaly(UUID headId, int headNumber, String reason) {
        emitCompileOnAnomaly(headId, headNumber, reason);
        ReplayRecord rec = lastReplayByHead.get(headId);
        if (rec == null) {
            return;
        }
        if (!replayAnomalyGate.add(headId)) {
            return;
        }
        logReplayRecord(headNumber, rec, reason);
    }

    /**
     * Emits the head's authoritative compiled route as a [COMPILE] record on an anomaly, so a track-geometry
     * failure reproduces through CompileCaseTest with no world even when verbose was never enabled. Always
     * called alongside the [REPLAY] emit; gated once per stuck episode (its own gate, re-armed by
     * {@link #notifyHeadProgress}), so a replanning storm dumps one record rather than one per retry.
     */
    public static void emitCompileOnAnomaly(UUID headId, int headNumber, String reason) {
        CompileRecord rec = lastCompileByHead.get(headId);
        if (rec == null) {
            return;
        }
        if (!compileAnomalyGate.add(headId)) {
            return;
        }
        LOGGER.info("[COMPILE] head {} start {} target {} dir {} fromTip {} waypoints {}, reason={}\n{}",
                headNumber, rec.start.toShortString(), rec.target.toShortString(), rec.headDir,
                rec.isFromTrackTip, rec.waypoints.size(), reason, rec.serialize());
    }

    /** Clears the anomaly gates after real forward progress, so a later independent anomaly emits again. */
    public static void notifyHeadProgress(UUID headId) {
        replayAnomalyGate.remove(headId);
        compileAnomalyGate.remove(headId);
    }

    /**
     * Rescans structures along the actual planned coarse waypoints and returns anything
     * that wasn't already in the planner's avoidance list.
     */
    private static List<PredictedStructure> validateRouteAgainstStructures(
            ServerLevel level, CoarseRoute route,
            List<PredictedStructure> originalStructures,
            NoiseTerrainSampler sampler, TrackExpansionHead head) {

        List<CoarseRoute.CoarseWaypoint> waypoints = route.getWaypoints();
        if (waypoints.size() < 2) {
            return List.of();
        }

        Set<Long> known = new HashSet<>();
        for (PredictedStructure ps : originalStructures) {
            known.add(ps.chunkPos().toLong());
        }

        List<PredictedStructure> newConflicts = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos segStart = waypoints.get(i).position();
            BlockPos segEnd = waypoints.get(i + 1).position();
            // includeAvoidanceZones=false: the global avoidance zones were already merged by the initial
            // whole-route scan (they're in `known`), so re-collecting them per segment is pure waste.
            List<PredictedStructure> segResult =
                    RouteObstacleAvoider.scanStructures(level, segStart, segEnd, sampler, false);
            for (PredictedStructure ps : segResult) {
                if (known.add(ps.chunkPos().toLong())) {
                    newConflicts.add(ps);
                }
            }
        }

        // Same target-village exclusion the initial plan applies 
        var lockedPlan = head.getVillageState().getLockedStationPlan();
        if (lockedPlan != null && head.getVillageState().hasTargetVillage()) {
            BlockPos villageCenter = head.getVillageState().getTargetVillageCenter();
            if (villageCenter != null) {
                newConflicts.removeIf(s -> s.approximateCenter().distManhattan(villageCenter) < 200);
            }
        }

        return newConflicts;
    }

    /**
     * Shuts down the route planner executor. Called during server shutdown/world change.
     * A new executor will be created automatically on next use.
     */
    public static void shutdown() {
        ExecutorService exec = routePlannerExecutor;
        if (exec != null) {
            exec.shutdownNow();
            routePlannerExecutor = null;
        }
        routeGenerations.clear();
        lastReplayByHead.clear();
        replayAnomalyGate.clear();
        lastCompileByHead.clear();
        compileAnomalyGate.clear();
    }
}
