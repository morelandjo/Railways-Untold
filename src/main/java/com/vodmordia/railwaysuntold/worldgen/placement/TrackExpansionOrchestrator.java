package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.VillageHeadAssigner;
import com.vodmordia.railwaysuntold.worldgen.placement.analyzer.LookaheadCalculator;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.ExplorationReaimer;
import com.vodmordia.railwaysuntold.worldgen.placement.decider.TunnelEscapeSystem;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.HandlerResult;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.PlacementContext;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.PlacementExecutor;
import com.vodmordia.railwaysuntold.worldgen.placement.handler.PlacementExecutorRegistry;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.AtGradeCrossingRegistry;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.ForcedCrossingRegistry;
import com.vodmordia.railwaysuntold.worldgen.siding.SidingPlacementService;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.village.AttemptedVillageTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Track expansion orchestrator that coordinates head lifecycle and chunk loading.
*/
public class TrackExpansionOrchestrator {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int NEARBY_HEAD_DISTANCE = 15;

    private final ServerLevel level;
    private final RailwaysUntoldConfig config;
    private final Random random;
    private final TrackPlacementSavedData savedData;
    private final ExpansionHeadManager headManager;
    private final HeadScheduler scheduler;

    private final ReplanEscalator replanEscalator = new ReplanEscalator();
    private final InitialPlacementBootstrapper bootstrapper;

    /**
     * Stall watchdog state: per active head, the wall-clock of its last forward progress and how many
     * times it has been kicked since. The replan give-up only catches a head that keeps FAILING; a head
     * that goes SILENT - IDLE with no scheduled task, never failing, never chunk-deferred (e.g. a
     * continuation that was lost after a placement) - is invisible to it and squats a generation slot
     * forever. {@link #runStallWatchdog} re-kicks such a head, then re-aims it off its village, then
     * retires it. Updated on real placement progress; pruned to the active set each sweep.
     */
    private final Map<UUID, Long> headLastProgressMs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> headStallKicks = new ConcurrentHashMap<>();
    /** Wall-clock of the previous watchdog sweep, so a held (draining / out-of-window) interval can be
     *  excluded from a head's stall clock by sliding its anchor forward rather than resetting it. */
    private long lastWatchdogRunMs = 0L;
    /** Heads that have already emitted a [HEAD-IDLE] diagnostic this episode; cleared on real progress so
     *  a head that goes silent (paused / stuck-rebuilding / deferred-for-chunks) logs its state exactly
     *  once per stall rather than every continuation tick. */
    private final java.util.Set<UUID> idleDiagLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** No-progress time after which the watchdog acts; comfortably above the round-robin slice so a
     *  merely-parked head (which progresses on its turn ~every 40s) never trips it. */
    private static final long STALL_WATCHDOG_THRESHOLD_MS = 120_000;
    /** On the 2nd consecutive kick, also abandon the head's village target (it may be unreachable). */
    private static final int STALL_ABANDON_KICK = 2;
    /** On the 3rd, retire the head (junction-terminate if a line is near) so the slot is freed. */
    private static final int STALL_RETIRE_KICK = 3;

    public TrackExpansionOrchestrator(ServerLevel level, RailwaysUntoldConfig config, Direction trackDirection,
                                       TrackPlacementSavedData savedData, BlockPos worldSpawnPos) {
        this.level = level;
        this.config = config;
        this.savedData = savedData;
        this.headManager = new ExpansionHeadManager();
        this.random = new Random(level.getSeed());
        this.scheduler = new HeadScheduler(level, config.TRACK_PLACEMENT_DELAY_TICKS);
        this.bootstrapper = new InitialPlacementBootstrapper(level, config, trackDirection, worldSpawnPos,
                savedData, headManager, scheduler, this::performExpansion);

        ConnectedBoundaryTracker.initFromSavedData(level);
        headManager.restoreFromSavedData(savedData, level);
    }

    public void expandFromInitialChunk(ChunkPos initialChunk) {
        bootstrapper.expandFromInitialChunk(initialChunk);
    }

    private void performExpansion(TrackExpansionHead head) {
        try {
            expandHead(head);
        } catch (Exception e) {
            LOGGER.error("[EXPANSION] Head {} at {} dir={}: UNCAUGHT EXCEPTION during expandHead - head may be stuck!",
                    head.getHeadNumber(), head.getPosition(), head.getDirection(), e);
            return;
        }
    }

    /**
     * Runs a single expansion tick for the given head. Used by gametests to drive
     * heads deterministically, one tick at a time, through the same code path as
     * normal expansion.
     */
    public void stepHead(TrackExpansionHead head) {
        expandHeadInternal(head);
    }

    private void expandHead(TrackExpansionHead head) {
        if (head.isComplete()) {
            return;
        }

        expandHeadInternal(head);
    }

    private void expandHeadInternal(TrackExpansionHead head) {
        if (head.isComplete()) {
            return;
        }

        // Halt gate: a previous execution failure called head.pause().
        if (head.isPaused()) {
            logIdleDiag(head, "paused");
            return;
        }

        // Planning gate: if an async route rebuild is in flight, do NOT place blocks.
        if (head.getTerrainPlanState().isRouteRebuilding()) {
            logIdleDiag(head, "route-rebuilding");
            scheduler.scheduleContinuation(head, this::performExpansion);
            return;
        }

        // If the installed precision route is invalid because the planner couldn't
        // stitch a tail in the required arrival direction, abandon this village so
        // the head can pick a different target.
        if (handleTailAlignFailure(head)) return;

        if (tryStationCommit(head)) return;
        if (handleStationArrival(head)) return;

        LookaheadContext ctx = checkChunksAndLookahead(head);
        if (ctx == null) {
            logIdleDiag(head, "deferred-for-chunks");
            return;
        }

        if (tryParallelMerge(head)) return;

        if (tryCompleteConverge(head)) return;

        if (tryParallelConverge(head)) return;

        if (yieldToNearbyHead(head, ctx.currentPos())) return;

        TerrainScanner.TerrainScan scan = scanTerrainOrDefer(head, ctx);
        if (scan == null) return;

        dispatchDecision(head, ctx, scan);
    }

    /**
     * One-shot (per stall episode) capture of WHY a head's expansion stopped producing work - the silent
     * exits (paused, stuck route-rebuild, deferred-for-chunks) leave no other trace, so a head that goes
     * idle mid-journey is invisible in the log. Records the discriminating state needed to tell a genuine
     * stall from a legitimate wait. Verbose-gated and gated once per episode (re-armed on real progress).
     */
    private void logIdleDiag(TrackExpansionHead head, String reason) {
        if (!RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            return;
        }
        UUID id = head.getHeadId();
        if (!idleDiagLogged.add(id)) {
            return;
        }
        var vs = head.getVillageState();
        var pr = head.getTerrainPlanState().getPrecisionRoute();
        LOGGER.warn("[HEAD-IDLE] head {} at {} dir={} reason={} paused={} rebuilding={} waitingChunks={} mayRun={} autoload={} hasVillage={} lockedPlan={} stationPlaced={} route={}",
                head.getHeadNumber(), head.getPosition(), head.getDirection(), reason,
                head.isPaused(), head.getTerrainPlanState().isRouteRebuilding(),
                ChunkLoadTrackExpander.isHeadWaitingForChunks(level, id),
                AutoLoadController.mayHeadRun(id), AutoLoadController.isActive(),
                vs.hasTargetVillage(), vs.getLockedStationPlan() != null, vs.isStationPlaced(),
                pr == null ? "null" : (pr.isValid() ? ("valid,remaining=" + pr.hasRemainingPath()) : "invalid"));
    }

    /**
     * If a foreign line is running parallel and adjacent to this head, merge the head into it (curve
     * across + join the graphs) and complete the head, eliminating the side-by-side parallel-track
     * artifacts. Merging takes priority over reaching a village - a head pursuing a village target that
     * meets a parallel line drops the binding and merges.
     */
    private boolean tryParallelMerge(TrackExpansionHead head) {
        if (!RailwaysUntoldConfig.isParallelMergeEnabled()) {
            return false;
        }
        if (!ParallelMergeService.tryMergeIntoParallel(level, head)) {
            return false;
        }
        if (head.getVillageState().hasTargetVillage()) {
            head.getVillageState().clear();
        }
        ParallelConvergeRegistry.clear(head.getHeadId());
        head.markComplete();
        headManager.saveState(savedData);
        return true;
    }

    /** Search radius for curving a converging head into its line. Larger than the stuck-tip junction's
     *  radius: the joining curve needs at least the minimum curve radius of room, so the head connects
     *  from a few blocks out rather than running all the way onto the line (which laid track-on-track). */
    private static final int CONVERGE_JOIN_RADIUS = 28;

    /**
     * Completes a converging head by curving its tip into the line it is converging toward, as soon as a
     * legal joining curve fits ({@link CreateTrackConnector#connectTracks} returns null until it does, so
     * this fires from the right distance out, not on the line). Owning the join here - rather than waiting
     * for the touching {@link #tryParallelMerge} to catch a parallel-adjacent pose, which an angled
     * approach never strikes - is what stops the head running onto the line and laying track-on-track.
     */
    private boolean tryCompleteConverge(TrackExpansionHead head) {
        if (!ParallelConvergeRegistry.isConverging(head.getHeadId())) {
            return false;
        }
        if (!JunctionTerminator.tryTerminateIntoForeignLine(level, head, CONVERGE_JOIN_RADIUS, true)) {
            return false;
        }
        LOGGER.warn("[PARALLEL-CONVERGE] Head {} at {}: curved into the parallel line - completing head",
                head.getHeadNumber(), head.getPosition());
        ParallelConvergeRegistry.clear(head.getHeadId());
        head.markComplete();
        headManager.saveState(savedData);
        return true;
    }

    /**
     * If a parallel foreign line is running within the long-range convergence band beside this head,
     * steer the head onto it: drop any village binding (merging takes priority, as with the touching
     * merge), retarget to a point on that line ahead of the head, and replan. The head curves over via
     * the normal planner; {@link #tryCompleteConverge} then curves the tip into the line and completes the
     * head once a legal joining curve fits. Marked converging so the scan isn't re-run every step while it
     * heads for the line, and so {@link #tryCompleteConverge} owns the completion.
     */
    private boolean tryParallelConverge(TrackExpansionHead head) {
        if (!RailwaysUntoldConfig.isParallelMergeEnabled()) {
            return false;
        }
        if (ParallelConvergeRegistry.isConverging(head.getHeadId())) {
            return false;
        }
        BlockPos target = ParallelMergeService.findParallelConvergeTarget(level, head);
        if (target == null) {
            return false;
        }
        if (head.getVillageState().hasTargetVillage()) {
            head.getVillageState().clear();
        }
        ParallelConvergeRegistry.mark(head.getHeadId());
        LOGGER.warn("[PARALLEL-CONVERGE] Head {} at {}: parallel line within range - steering toward {} to merge",
                head.getHeadNumber(), head.getPosition(), target);
        CoarseRouteFactory.createAndAttach(level, head, target);
        headManager.saveState(savedData);
        scheduler.scheduleContinuation(head, this::performExpansion);
        return true;
    }

    /**
     * Station commitment phase: when the head gets near a locked station plan's
     * approach waypoint, place the schematic synchronously and fire a fresh replan
     * toward the real-terrain entry point.
     */
    private boolean tryStationCommit(TrackExpansionHead head) {
        if (!com.vodmordia.railwaysuntold.worldgen.placement.village.StationCommitter.shouldTriggerCommit(head)) {
            return false;
        }
        com.vodmordia.railwaysuntold.worldgen.placement.village.StationCommitter.CommitResult result =
                com.vodmordia.railwaysuntold.worldgen.placement.village.StationCommitter.tryCommit(level, head);
        switch (result.status()) {
            case PLACED -> {
                // Replan is in flight via createAndAttach; next tick will see
                // routeRebuilding=TRUE and early-return until the new route lands.
                scheduler.scheduleContinuation(head, this::performExpansion);
                return true;
            }
            case DEFER -> {
                // Footprint chunks aren't loaded.
                Set<ChunkPos> deferChunks = result.deferChunks();
                if (deferChunks != null && !deferChunks.isEmpty()) {
                    deferForChunks(head, deferChunks);
                } else {
                    scheduler.scheduleContinuation(head, this::performExpansion);
                }
                return true;
            }
            case RETARGET -> {
                // The arrival pose would bury existing track. Re-place the station at a better
                // coordinate (the next station plan that clears track) and re-route there; only
                // abandon if no clear alternative remains.
                if (retargetStationToClearAlternative(head)) {
                    scheduler.scheduleContinuation(head, this::performExpansion);
                } else {
                    abandonVillageFromOrchestrator(head);
                    scheduler.scheduleContinuation(head, this::performExpansion);
                }
                return true;
            }
            case FAILED -> {
                // Station commit permanently failed (slope/terrain wall). Abandon this
                // village and assign a new target so the head can continue.
                // StationCommitter paused the head - unpause before continuing.
                head.resume();
                abandonVillageFromOrchestrator(head);
                scheduler.scheduleContinuation(head, this::performExpansion);
                return true;
            }
        }
        return false;
    }

    /**
     * Re-targets the head's locked station plan to the next ranked alternative whose footprint
     * clears existing track, updates the approach pose, and fires a replan toward it. Returns
     * false if no clear alternative remains, in which case the caller abandons the village.
     */
    private boolean retargetStationToClearAlternative(TrackExpansionHead head) {
        var villageState = head.getVillageState();
        com.vodmordia.railwaysuntold.datapack.SelectedStation station = villageState.getSelectedStation();
        if (station == null) {
            return false;
        }
        java.util.List<com.vodmordia.railwaysuntold.worldgen.village.StationPlan> remaining =
                new java.util.ArrayList<>(villageState.getStationPlanAlternatives());
        remaining.remove(villageState.getLockedStationPlan());

        com.vodmordia.railwaysuntold.worldgen.village.StationPlan chosen = null;
        for (com.vodmordia.railwaysuntold.worldgen.village.StationPlan alt : remaining) {
            if (!com.vodmordia.railwaysuntold.worldgen.village.StationTrackBurialCheck.planBuriesTrack(level, alt, station)) {
                chosen = alt;
                break;
            }
        }
        if (chosen == null) {
            return false;
        }
        remaining.remove(chosen);
        java.util.List<com.vodmordia.railwaysuntold.worldgen.village.StationPlan> reordered =
                new java.util.ArrayList<>(remaining.size() + 1);
        reordered.add(chosen);
        reordered.addAll(remaining);

        villageState.setLockedStationPlan(chosen);
        villageState.setStationPlanAlternatives(reordered);
        villageState.setApproachWaypoint(chosen.arrivalPos());
        BlockPos villageCenter = villageState.getTargetVillageCenter();
        if (villageCenter != null) {
            Direction approachSide = com.vodmordia.railwaysuntold.util.spatial.DirectionUtil
                    .getSegmentDirection(villageCenter, chosen.arrivalPos());
            if (approachSide != null) {
                villageState.setPlannedApproachDirection(approachSide);
            }
        }
        CoarseRouteFactory.createAndAttach(level, head, chosen.arrivalPos());
        return true;
    }

    /**
     * If the planner couldn't stitch a final tail in the required arrival direction,
     * the precision route is marked invalid with reason TAIL_ALIGN_FAILED. Abandon the
     * locked village and re-trigger expansion so a different target can be assigned.
     */
    private boolean handleTailAlignFailure(TrackExpansionHead head) {
        var precision = head.getTerrainPlanState().getPrecisionRoute();
        if (precision == null) return false;
        var path = precision.getPrecisionPath();
        if (path == null || path.valid) return false;
        var reason = path.invalidReasonCode;
        if (reason != com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.InvalidReason.TAIL_ALIGN_FAILED
                && reason != com.vodmordia.railwaysuntold.worldgen.planner.PlannedPath.InvalidReason.SKIRT_OVERDRIVE) {
            return false;
        }
        if (!head.getVillageState().hasTargetVillage()) return false;
        abandonVillageFromOrchestrator(head);
        scheduler.scheduleContinuation(head, this::performExpansion);
        return true;
    }

    /**
     * Station arrival: once the station is placed and the head has reached the
     * station entry point, move it to the exit side, clear the village binding, and
     * assign a new target.
     */
    private boolean handleStationArrival(TrackExpansionHead head) {
        if (!head.getVillageState().isStationPlaced()) {
            return false;
        }
        SchematicPlacer.SchematicPlacementResult placed = head.getVillageState().getPlacedStation();
        if (placed == null || placed.trackStart == null || placed.trackEnd == null) {
            return false;
        }
        if (head.getPosition().distManhattan(placed.trackStart) > 3) {
            return false;
        }

        // Continue in the head's current travel direction.
        BlockPos exitPoint = placed.trackEnd;
        Direction exitDir = head.getDirection();

        head.setPosition(exitPoint);
        head.setDirection(exitDir);
        head.getTerrainPlanState().setPrecisionRoute(null);
        head.getTerrainPlanState().setCoarseRoute(null);

        head.getVillageState().clear();
        headManager.saveState(savedData);

        VillageHeadAssigner.assignNewVillageToHead(head, level, config);
        scheduler.scheduleContinuation(head, this::performExpansion);
        return true;
    }

    /** Collected lookahead state passed between the chunk check, yield, scan, and dispatch phases. */
    private record LookaheadContext(BlockPos currentPos, Direction expandDir, int availableLookahead,
                                      Set<ChunkPos> requiredChunks) {}

    /**
     * Verifies the head's current chunk is loaded and computes dynamic lookahead.
     * Defers the head for chunks if the current chunk or any lookahead chunk isn't
     * loaded - those chunks get tickets via ChunkLoadTrackExpander and will load
     * regardless of player proximity. Returns the lookahead context on success,
     * or null if this tick was already handled.
     */
    @Nullable
    private LookaheadContext checkChunksAndLookahead(TrackExpansionHead head) {
        BlockPos currentPos = head.getPosition();
        Direction expandDir = head.getDirection();

        int chunkX = currentPos.getX() >> 4;
        int chunkZ = currentPos.getZ() >> 4;
        if (ChunkCoordinateUtil.getLoadedChunk(level, chunkX, chunkZ) == null) {
            deferForChunks(head, Set.of(new ChunkPos(chunkX, chunkZ)));
            return null;
        }

        int availableLookahead = LookaheadCalculator.calculateDynamicLookahead(level, currentPos, expandDir);

        if (availableLookahead == 0) {
            // Use +1 to match LookaheadCalculator which checks distance+1 for TerrainScanner sampling.
            // Without this, the deferral requests fewer chunks than the calculator needs, causing
            // an immediate-resume loop when the requested chunks load but the calculator still returns 0.
            BlockPos endPos = currentPos.relative(expandDir, PlacementConstants.REQUIRED_LOOKAHEAD + 1);
            deferForChunks(head, ChunkCoordinateUtil.getChunksInBoundingBox(currentPos, endPos));
            return null;
        }

        // Use availableLookahead + 1 because TerrainScanner.scanAhead samples positions
        // start+1 through start+distance+1, requiring chunks one block further.
        BlockPos lookaheadEnd = currentPos.relative(expandDir, availableLookahead + 1);
        Set<ChunkPos> requiredChunks = ChunkCoordinateUtil.getChunksInBoundingBox(currentPos, lookaheadEnd);

        // Pin the head's working chunks. They're already loaded (we just verified) but
        // may be held only by organic tickets (chunky / players) that can drop without
        // notice.
        ChunkLoadTrackExpander.holdWorkingChunks(level, head.getHeadId(), requiredChunks);

        return new LookaheadContext(currentPos, expandDir, availableLookahead, requiredChunks);
    }

    /**
     * Yields this tick if a higher-priority head (lower head number) is nearby, so the
     * higher-priority head can place first and avoid collisions. Returns true if the
     * tick was yielded.
     */
    private boolean yieldToNearbyHead(TrackExpansionHead head, BlockPos currentPos) {
        TrackExpansionHead nearbyHead = headManager.findNearbyHead(currentPos, head.getHeadId(), NEARBY_HEAD_DISTANCE);
        if (nearbyHead != null && head.getHeadNumber() > nearbyHead.getHeadNumber()) {
            scheduler.scheduleContinuation(head, this::performExpansion);
            return true;
        }
        return false;
    }

    /**
     * Runs TerrainScanner.scanAhead. On null, either defers for missing chunks or
     * schedules a delayed retry when all chunks are already loaded (a scanner failure
     * for a non-chunk reason). Returns the scan on success, or null if the tick was
     * handled.
     */
    @Nullable
    private TerrainScanner.TerrainScan scanTerrainOrDefer(TrackExpansionHead head, LookaheadContext ctx) {
        TerrainScanner.TerrainScan scan = TerrainScanner.scanAhead(
                level, ctx.currentPos(), ctx.expandDir(), ctx.availableLookahead(), config);
        if (scan != null) {
            return scan;
        }

        // Deferring when all chunks are loaded would create an infinite loop (resume
        // fires immediately, scan still null). Schedule a delayed retry instead.
        boolean allLoaded = ctx.requiredChunks().stream()
                .allMatch(chunk -> ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null);
        if (allLoaded) {
            LOGGER.warn("[EXPAND] Head {} at {} dir={}: terrain scan returned null but all {} chunks loaded, scheduling delayed retry",
                    head.getHeadNumber(), ctx.currentPos(), ctx.expandDir(), ctx.requiredChunks().size());
            scheduler.scheduleContinuation(head, this::performExpansion);
            return null;
        }
        deferForChunks(head, ctx.requiredChunks());
        return null;
    }

    /**
     * Runs the decision pipeline and executes the resulting placement. If the pipeline
     * itself triggered a replan (e.g. VillageHeadAssigner called
     * CoarseRouteFactory.createAndAttach mid-tick), the decision is based on the old
     * plan and is discarded - the next tick picks up the new plan after it lands.
     */
    private void dispatchDecision(TrackExpansionHead head, LookaheadContext ctx, TerrainScanner.TerrainScan scan) {
        PlacementDecision decision = PlacementDecisionEngine.decidePlacementFromTerrain(
                head, ctx.currentPos(), ctx.expandDir(), scan, level, config, ctx.availableLookahead(), random, headManager);

        if (head.getTerrainPlanState().isRouteRebuilding()) {
            scheduler.scheduleContinuation(head, this::performExpansion);
            return;
        }

        executePlacement(head, decision, scan, ctx.currentPos(), ctx.expandDir());
    }

    private void abandonVillageFromOrchestrator(TrackExpansionHead head) {
        java.util.UUID villageId = head.getVillageState().getTargetVillageId();

        VillageTargetingSavedData villageSavedData = VillageTargetingSavedData.get(level);
        villageSavedData.getAttemptedTracker().markVillageAttempted(
                villageId, AttemptedVillageTracker.AttemptReason.NO_VALID_APPROACH);
        villageSavedData.setDirty();

        head.getVillageState().clear();

        VillageHeadAssigner.assignNewVillageToHead(head, level, config);
    }

    /**
     * Logs a single placement failure. These are transient: the head replans from here and the vast
     * majority recover (a planner-drift halt re-plans from the true tip, a bezier endpoint not yet placed
     * is retried). Only a head that keeps failing the same spot escalates to the always-on
     * {@code [REPLAN-GIVEUP]}/{@code [HEAD-FAIL]}. So this is verbose-gated - a recovered failure stays out
     * of the normal log; a genuine give-up still surfaces (with its [REPLAY]/[COMPILE]).
     */
    private static void logTransientPlacementFailure(int headNum, BlockPos pos, Direction dir, String detail) {
        if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            LOGGER.warn("[EXECUTE-FAIL] Head {} at {} dir={}: {}", headNum, pos, dir, detail);
        }
    }

    private void executePlacement(TrackExpansionHead head, PlacementDecision decision,
                                  TerrainScanner.TerrainScan scan, BlockPos currentPos, Direction expandDir) {
        int headNum = head.getHeadNumber();

        if (decision == null) {
            logTransientPlacementFailure(headNum, currentPos, expandDir, "decision engine returned NULL");
            handleExecutionFailure(head, currentPos, expandDir);
            return;
        }

        if (decision.getType() == PlacementDecision.Type.INVALID) {
            // The decision engine can't place here (e.g. the route's target is unreachable - a descent
            // to a sub-surface/water Y that no slope-legal segment can land on). Completing is the right
            // terminal (the head stops), but it MUST be logged: an unlogged markComplete here is exactly
            // what made a head look like it "silently vanished" mid-run with no FAIL/HALT/GIVEUP.
            LOGGER.warn("[EXPAND-COMPLETE] Head {} at {} dir={}: decision engine returned INVALID (cannot place) - completing head",
                    headNum, currentPos, expandDir);
            HeadFailureLog.report(head, HeadFailureLog.Kind.CANNOT_PLACE, "decision engine returned INVALID");
            head.markComplete();
            replanEscalator.clear(head.getHeadId());
            headManager.saveState(savedData);
            return;
        }

        if (decision.getType() == PlacementDecision.Type.DEFER) {
            handleDeferDecision(head, decision);
            return;
        }

        PlacementExecutor executor = PlacementExecutorRegistry.getExecutor(decision.getType());
        if (executor == null) {
            logTransientPlacementFailure(headNum, currentPos, expandDir,
                    "no executor for decision type " + decision.getType());
            handleExecutionFailure(head, currentPos, expandDir);
            return;
        }

        PlacementContext context = new PlacementContext(
                level,
                head,
                decision,
                scan,
                currentPos,
                expandDir,
                config,
                headManager,
                savedData,
                h -> scheduler.scheduleInitial(h, this::performExpansion),
                () -> scheduler.scheduleContinuation(head, this::performExpansion),
                TunnelEscapeSystem::tryTunnelEscape,
                random
        );

        // Per-segment drift trace: the planned start (decision.getStart()) should equal the live
        // tip (currentPos). Only the bezier executor guards this today; logging it for every segment
        // type makes a drifted diagonal/curve opener - the kind that places a disconnected stub - visible
        // in the log so it can be reproduced. Observability only; behind verbose.
        if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            BlockPos plannedStart = decision.getStart();
            String drift = plannedStart == null ? "n/a"
                    : plannedStart.equals(currentPos) ? "0"
                    : (plannedStart.getX() - currentPos.getX()) + ","
                            + (plannedStart.getY() - currentPos.getY()) + ","
                            + (plannedStart.getZ() - currentPos.getZ());
            LOGGER.info("[PLACE-SEG] head {} type {} liveTip {} plannedStart {} drift {} end {}",
                    headNum, decision.getType(), currentPos.toShortString(),
                    plannedStart == null ? "-" : plannedStart.toShortString(), drift,
                    decision.getEnd() == null ? "-" : decision.getEnd().toShortString());
        }

        HandlerResult result = executor.handle(context);

        if (result.shouldDefer()) {
            deferForChunks(head, result.deferChunks());
            return;
        }

        if (!result.success()) {
            String reason = result.deferReason() != null ? result.deferReason() : "unknown reason";
            logTransientPlacementFailure(headNum, currentPos, expandDir,
                    "handler " + decision.getType() + " failed - " + reason);
            head.clearAllPaths();
            // Hitting existing track means the head is on a crossing course. Rather than looping back
            // into lateral avoidance - which keeps curving into the same track and failing (the 'curve a
            // lot then fail' storm) until the 12-failure give-up - resolve it NOW by merging into the
            // blocking line as a junction. Only fall through to the replan/avoid loop if there's no
            // physical line to merge into (so a genuine non-track failure still replans as before).
            if (reason.contains("blocked by existing track")
                    && JunctionTerminator.tryTerminateIntoBlockingLine(level, head)) {
                LOGGER.warn("[CROSSING-MERGE] Head {} at {}: merged into the blocking line instead of avoiding - completing head",
                        headNum, currentPos);
                HeadFailureLog.report(head, HeadFailureLog.Kind.CROSSING_JUNCTION,
                        "blocked by existing track at " + currentPos.toShortString());
                head.markComplete();
                replanEscalator.clear(head.getHeadId());
                ForcedCrossingRegistry.clear(head.getHeadId());
                AtGradeCrossingRegistry.clear(head.getHeadId());
                ParallelConvergeRegistry.clear(head.getHeadId());
                headManager.saveState(savedData);
                return;
            }
            handleExecutionFailure(head, currentPos, expandDir);
            return;
        }

        headManager.saveState(savedData);

        replanEscalator.clear(head.getHeadId());
        ForcedCrossingRegistry.clear(head.getHeadId());
        AtGradeCrossingRegistry.clear(head.getHeadId());
        head.getVillageState().resetDeferCount();
        head.getVillageState().recordDirectionChange(head.getDirection());
        // Real progress re-arms the anomaly [REPLAY] gate, so a later independent halt emits again.
        CoarseRouteFactory.notifyHeadProgress(head.getHeadId());
        // ...and the stall watchdog: this head is alive and advancing.
        headLastProgressMs.put(head.getHeadId(), System.currentTimeMillis());
        headStallKicks.remove(head.getHeadId());
        idleDiagLogged.remove(head.getHeadId()); // re-arm the [HEAD-IDLE] diagnostic

        if (RailwaysUntoldConfig.isSideBySideEnabled() && !result.companionHandled()) {
            CompanionTrackPlacer.placeCompanionForDecision(level, decision, head, context);
        }

        SidingPlacementService.onPlacementComplete(head, decision, level);

        if (!result.skipContinuation()) {
            scheduleContinuation(head);
        }
    }

    private static final int MAX_VILLAGE_DEFER_COUNT = 50;
    private static final int MAX_NON_CHUNK_DEFER_COUNT = 10;

    private void handleDeferDecision(TrackExpansionHead head, PlacementDecision decision) {
        boolean isChunkDefer = decision.getDeferChunks() != null && !decision.getDeferChunks().isEmpty();

        // Count non-chunk defers - these indicate a condition that won't resolve on its own
        if (!isChunkDefer) {
            if (head.getVillageState().hasTargetVillage()) {
                // Don't abandon the village on defers - once assigned, commit to it.
                // Defers are temporary conditions, but if they persist the current
                // precision route is stuck: reset the counter and replan toward the
                // same village target (the binding survives the replan).
                int deferCount = head.getVillageState().incrementDeferCount();
                if (deferCount >= MAX_VILLAGE_DEFER_COUNT) {
                    LOGGER.warn("[DEFER] Head {} at {} deferred {} times while committed to its village target - replanning",
                            head.getHeadNumber(), head.getPosition(), deferCount);
                    head.getVillageState().resetDeferCount();
                    replanFromCurrentState(head, head.getPosition(), head.getDirection(), "VILLAGE-DEFER-RECOVER");
                    return;
                }
            } else {
                // No target village and repeated non-chunk defers indicate a stuck head
                // (typically a stale precision route or a bad exploration target).
                int deferCount = head.getVillageState().incrementDeferCount();
                if (deferCount >= MAX_NON_CHUNK_DEFER_COUNT) {
                    head.getVillageState().resetDeferCount();
                    replanFromCurrentState(head, head.getPosition(), head.getDirection(), "DEFER-RECOVER");
                    return;
                }
            }
        }

        if (isChunkDefer) {
            boolean allLoaded = decision.getDeferChunks().stream()
                    .allMatch(chunk -> ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null);

            if (allLoaded) {
                scheduler.scheduleContinuation(head, this::performExpansion);
            } else {
                deferForChunks(head, decision.getDeferChunks());
            }
        } else {
            scheduler.scheduleContinuation(head, this::performExpansion);
        }
    }

    /**
     * Called on any execution failure (null decision, missing executor, handler failure).
     *
     */
    private void handleExecutionFailure(TrackExpansionHead head, BlockPos currentPos, Direction expandDir) {
        replanFromCurrentState(head, currentPos, expandDir, "EXECUTE-HALT");
    }

    /**
     * Shared recovery path for stuck heads. Picks a replan target in priority order,
     * clears the stale routes, dispatches an async route rebuild, and schedules a
     * continuation so the head picks up the new route once the planner installs it.
     */
    private void replanFromCurrentState(TrackExpansionHead head, BlockPos currentPos,
                                        Direction expandDir, String reasonTag) {
        // A single replan is transient and usually recovers, so its [REPLAY]/[COMPILE] is verbose-only -
        // it would otherwise dump a record for every self-healing drift halt. The GENUINE failure, when a
        // head exhausts its retries, still emits always-on via HeadFailureLog.report at the give-up below.
        if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            CoarseRouteFactory.emitReplayOnAnomaly(head.getHeadId(), head.getHeadNumber(), reasonTag);
        }
        var vs = head.getVillageState();
        BlockPos replanTarget = null;
        var lockedPlan = vs.getLockedStationPlan();
        if (lockedPlan != null) {
            replanTarget = lockedPlan.arrivalPos();
        } else if (vs.getApproachWaypoint() != null) {
            replanTarget = vs.getApproachWaypoint();
        } else if (vs.hasTargetVillage()) {
            replanTarget = vs.getTargetVillageCenter();
        } else if (vs.getExplorationTarget() != null) {
            replanTarget = vs.getExplorationTarget();
        }

        head.getTerrainPlanState().setPrecisionRoute(null);
        head.getTerrainPlanState().setCoarseRoute(null);

        if (replanTarget != null) {
            // Identical-replan escalation: when the same (position, target) pair fails
            // N times in a row, the planner is deterministically producing the same bad
            // route. Drop the current target chain and force a fresh exploration
            // direction so the next plan has a different geometric problem to solve.
            ReplanEscalator.EscalationCheck check = replanEscalator.recordAndCheck(
                    head.getHeadId(), currentPos, replanTarget);
            if (check.shouldGiveUp()) {
                // The head has failed repeatedly without moving; re-exploring only changes the target,
                // not the geometry that is blocking it. Before retiring it into a dead stub, try to
                // terminate it into a T-junction with the foreign line that is blocking it (the common
                // cause of a no-progress stall is a head-on near-grade meeting with another head's track
                // that can't be crossed within the slope limit). If that connects, the line ends cleanly
                // on the existing network instead of dangling.
                boolean junctioned = JunctionTerminator.tryTerminateIntoForeignLine(level, head)
                        || JunctionTerminator.tryTerminateIntoForeignLineAhead(level, head, expandDir);
                if (!junctioned && !ForcedCrossingRegistry.hasAttempted(head.getHeadId())) {
                    // Merge into the blocking line is impossible (e.g. it is met at a 45° diagonal, where
                    // no tangent junction fits). As a last resort before retiring, force the next replan to
                    // RAMP the collision under/over the line instead of leaving it at-grade to merge again.
                    // recordAndCheck cleared the failure fingerprint at give-up, so the forced route gets a
                    // fresh budget; hasAttempted ensures we force only once - if the forced ramp also can't
                    // be placed, the next give-up retires the head cleanly.
                    ForcedCrossingRegistry.force(head.getHeadId(), currentPos, true);
                    LOGGER.warn("[FORCE-CROSSING] Head {} at {} dir={}: stuck after {} failures, merge impossible - forcing under/over crossing before retiring",
                            head.getHeadNumber(), currentPos, expandDir, check.posCount());
                    CoarseRouteFactory.createAndAttach(level, head, replanTarget);
                    headManager.saveState(savedData);
                    scheduler.scheduleContinuation(head, this::performExpansion);
                    return;
                }
                if (!junctioned && !AtGradeCrossingRegistry.hasAttempted(head.getHeadId())) {
                    // Neither a merge nor an over/under ramp could be placed (the ramp needs horizontal room
                    // the head no longer has once it has advanced to the crossing). As the final resort before
                    // retiring, permit the head to cross the blocking line AT GRADE: the planner leaves the
                    // route flat over the crossing and the placement gate lets the span through, so Create
                    // forms a crossing instead of the head dangling as a dead stub. The placement gate restricts
                    // this to a genuine crossing (perpendicular/diagonal) - a parallel overlap stays blocked and
                    // falls through to the retire below on the next give-up.
                    AtGradeCrossingRegistry.allow(head.getHeadId(), currentPos);
                    LOGGER.warn("[AT-GRADE-CROSSING] Head {} at {} dir={}: stuck after {} failures, cannot merge or grade-separate - crossing the blocking line at grade before retiring",
                            head.getHeadNumber(), currentPos, expandDir, check.posCount());
                    CoarseRouteFactory.createAndAttach(level, head, replanTarget);
                    headManager.saveState(savedData);
                    scheduler.scheduleContinuation(head, this::performExpansion);
                    return;
                }
                LOGGER.warn("[REPLAN-GIVEUP] Head {} at {} dir={}: stuck after {} no-progress failures (target {}) - {}",
                        head.getHeadNumber(), currentPos, expandDir, check.posCount(), replanTarget,
                        junctioned ? "terminated into junction" : "retiring head");
                HeadFailureLog.report(head,
                        junctioned ? HeadFailureLog.Kind.GIVEUP_JUNCTION : HeadFailureLog.Kind.RETIRED_STUB,
                        "stuck after " + check.posCount() + " no-progress failures");
                head.markComplete();
                replanEscalator.clear(head.getHeadId());
                ForcedCrossingRegistry.clear(head.getHeadId());
                AtGradeCrossingRegistry.clear(head.getHeadId());
                ParallelConvergeRegistry.clear(head.getHeadId());
                headManager.saveState(savedData);
                return;
            }
            if (check.shouldEscalate()) {
                // Transient recovery: the head re-aims and continues. A genuinely stuck head still
                // surfaces at the give-up ([REPLAN-GIVEUP]/[HEAD-FAIL]).
                Direction reaim = ExplorationReaimer.chooseEscalationDirection(head.getInitialDirection(), expandDir);
                vs.clearTarget();
                vs.clearExplorationTarget();
                VillageHeadAssigner.forceSetExplorationTarget(head, reaim, level);
            } else {
                // Routine per-replan recovery; the give-up after repeated fails is the surfaced signal.
                CoarseRouteFactory.createAndAttach(level, head, replanTarget);
            }
        } else {
            replanEscalator.clear(head.getHeadId());
            LOGGER.warn("[{}] Head {} at {} dir={}: no replan target, generating fresh exploration target",
                    reasonTag, head.getHeadNumber(), currentPos, expandDir);
            VillageHeadAssigner.forceSetExplorationTarget(head,
                    ExplorationReaimer.chooseEscalationDirection(head.getInitialDirection(), expandDir), level);
        }

        headManager.saveState(savedData);
        // Kick a continuation so the head doesn't go IDLE while waiting for the
        // async route. expandHeadInternal will see routeRebuilding=TRUE and
        // re-schedule itself until the new route lands; once the planner's
        // completion callback installs the route and clears the flag, this
        // continuation chain picks up and executes the new plan.
        scheduler.scheduleContinuation(head, this::performExpansion);
    }

    private void scheduleContinuation(TrackExpansionHead head) {
        BlockPos nextPos = head.getPosition();
        Direction nextDir = head.getDirection();

        int nextLookahead = LookaheadCalculator.calculateDynamicLookahead(level, nextPos, nextDir);

        if (nextLookahead == 0) {
            // No loaded lookahead available - defer directly for the full required range.
            BlockPos fullEnd = nextPos.relative(nextDir, PlacementConstants.REQUIRED_LOOKAHEAD + 1);
            Set<ChunkPos> fullChunks = ChunkCoordinateUtil.getChunksInBoundingBox(nextPos, fullEnd);
            deferForChunks(head, fullChunks);
            return;
        }

        // Use nextLookahead + 1 to match TerrainScanner's sampling range (start+1 to start+distance+1)
        BlockPos nextEndPos = nextPos.relative(nextDir, nextLookahead + 1);
        Set<ChunkPos> requiredChunks = ChunkCoordinateUtil.getChunksInBoundingBox(nextPos, nextEndPos);

        boolean allChunksLoaded = requiredChunks.stream()
                .allMatch(chunk -> ChunkCoordinateUtil.getLoadedChunk(level, chunk) != null);

        if (allChunksLoaded) {
            scheduler.scheduleContinuation(head, this::performExpansion);
        } else {
            deferForChunks(head, requiredChunks);
        }
    }

    private void deferForChunks(TrackExpansionHead head, Set<ChunkPos> chunks) {
        scheduler.transitionToDeferred(head.getHeadId(), chunks);
        String dimensionKey = level.dimension().location().toString();
        ChunkLoadTrackExpander.addWaitingChunksForHead(dimensionKey, head.getHeadId(), chunks, level);
    }

    /**
     * Schedules a head for expansion (called by external systems like ChunkLoadTrackExpander).
     */
    public boolean scheduleHeadExpansion(TrackExpansionHead head, int delayTicks, HeadScheduler.ScheduleCaller caller) {
        return scheduler.scheduleExpansion(head, delayTicks, caller, this::performExpansion);
    }

    /** What the watchdog should do to a head, given how long it's been silent and how many times it's
     *  already been kicked. World-free so the escalation is unit-tested without an orchestrator. */
    enum StallAction { NONE, KICK, ABANDON_AND_KICK, RETIRE }

    static StallAction stallAction(long idleMs, int priorKicks, long thresholdMs) {
        if (idleMs < thresholdMs) {
            return StallAction.NONE;
        }
        int kick = priorKicks + 1;
        if (kick >= STALL_RETIRE_KICK) return StallAction.RETIRE;
        if (kick >= STALL_ABANDON_KICK) return StallAction.ABANDON_AND_KICK;
        return StallAction.KICK;
    }

    /**
     * Periodic safety net (driven from {@link ChunkLoadTrackExpander}'s server tick): catches a head
     * that has gone SILENT - making no forward progress, but NOT via a failure (the replan give-up
     * handles those) - and escalates KICK -> ABANDON_AND_KICK -> RETIRE so it can't squat a generation
     * slot indefinitely. A merely round-robin-parked head progresses on its turn and never trips this.
     */
    public void runStallWatchdog() {
        if (isComplete()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Interval since the previous sweep. A head that is legitimately HELD this interval (chunk-deferred,
        // draining, or outside the round-robin window) has its stall anchor slid forward by this much, so the
        // held time is excluded from its stall clock WITHOUT erasing previously-accumulated eligible-stall.
        long heldSlideMs = (lastWatchdogRunMs == 0L) ? 0L : Math.max(0L, now - lastWatchdogRunMs);
        java.util.Set<UUID> active = new java.util.HashSet<>();
        for (TrackExpansionHead head : headManager.getActiveHeads()) {
            if (head == null || head.isComplete()) {
                continue;
            }
            UUID id = head.getHeadId();
            active.add(id);
            // Held heads are legitimately NOT progressing and must not be kicked: a chunk-deferred head is
            // waiting for chunks (player/Chunky/autoload), and a head the autoload throttle is holding is
            // either in a chunk-backlog drain (all heads held, the window frozen) or outside the current
            // round-robin window (parked this slice). PAUSE the stall clock for the held interval - slide the
            // anchor forward, don't reset it - so a head that is merely parked never trips the watchdog, yet a
            // head that is ELIGIBLE but silently stuck still accumulates stall across its windowed turns and
            // is eventually caught. (Resetting here defanged the watchdog under many heads: with a 4-head
            // window each head is out-of-window most ticks, so its clock was cleared every sweep and never
            // reached the threshold - genuinely-stuck heads were never kicked.)
            if (ChunkLoadTrackExpander.isHeadWaitingForChunks(level, id) || !AutoLoadController.mayHeadRun(id)) {
                Long anchor = headLastProgressMs.get(id);
                headLastProgressMs.put(id, anchor == null ? now : Math.min(anchor + heldSlideMs, now));
                continue;
            }
            long last = headLastProgressMs.computeIfAbsent(id, k -> now); // first sight = a fresh grace window
            StallAction action = stallAction(now - last, headStallKicks.getOrDefault(id, 0), STALL_WATCHDOG_THRESHOLD_MS);
            if (action != StallAction.NONE) {
                applyStallRecovery(head, action, (now - last) / 1000);
            }
        }
        lastWatchdogRunMs = now;
        // Drop bookkeeping for heads that completed/retired so the maps don't accumulate.
        headLastProgressMs.keySet().retainAll(active);
        headStallKicks.keySet().retainAll(active);
    }

    private void applyStallRecovery(TrackExpansionHead head, StallAction action, long idleSeconds) {
        UUID id = head.getHeadId();
        // Surface the route that led here the moment we detect the silent stall - the watchdog is often
        // the ONLY signal for an idle head (no failure, no halt fired), so its [REPLAY] is what lets the
        // episode reproduce offline. Always-on (verbose-independent) and gated to once per stall episode,
        // so the first kick captures it and the later kicks/retire don't re-spam it.
        CoarseRouteFactory.emitReplayOnAnomaly(id, head.getHeadNumber(), "STALL-WATCHDOG:" + action);
        if (action == StallAction.RETIRE) {
            boolean junctioned = JunctionTerminator.tryTerminateIntoForeignLine(level, head);
            LOGGER.warn("[STALL-WATCHDOG] Head {} at {}: no progress for {}s after repeated kicks - {}",
                    head.getHeadNumber(), head.getPosition(), idleSeconds,
                    junctioned ? "terminated into junction" : "retiring head");
            HeadFailureLog.report(head,
                    junctioned ? HeadFailureLog.Kind.GIVEUP_JUNCTION : HeadFailureLog.Kind.RETIRED_STUB,
                    "stall watchdog: silent " + idleSeconds + "s");
            head.markComplete();
            replanEscalator.clear(id);
            ForcedCrossingRegistry.clear(id);
            AtGradeCrossingRegistry.clear(id);
            ParallelConvergeRegistry.clear(id);
            headLastProgressMs.remove(id);
            headStallKicks.remove(id);
            headManager.saveState(savedData);
            return;
        }

        int kick = headStallKicks.merge(id, 1, Integer::sum);
        headLastProgressMs.put(id, System.currentTimeMillis()); // re-arm: next action one threshold later
        boolean abandon = action == StallAction.ABANDON_AND_KICK && head.getVillageState().hasTargetVillage();
        LOGGER.warn("[STALL-WATCHDOG] Head {} at {}: no progress for {}s - kick {} (re-scheduling{})",
                head.getHeadNumber(), head.getPosition(), idleSeconds, kick,
                abandon ? ", abandoning unreachable village target" : "");
        if (abandon) {
            abandonVillageFromOrchestrator(head);
        }
        if (head.isPaused()) {
            head.resume();
        }
        scheduler.forceIdle(id);
        scheduleHeadExpansion(head, PlacementConstants.MIN_EXPANSION_DELAY_TICKS, HeadScheduler.ScheduleCaller.EXTERNAL);
    }

    public boolean isComplete() {
        return headManager.isComplete();
    }

    public ExpansionHeadManager getHeadManager() {
        return headManager;
    }

}
