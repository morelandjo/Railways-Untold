package com.vodmordia.railwaysuntold.worldgen.head;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.village.*;
import com.vodmordia.railwaysuntold.worldgen.village.network.StructureConnection;
import com.vodmordia.railwaysuntold.worldgen.village.network.VillageNetworkPlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Handles village assignment for expansion heads.
 */
public class VillageHeadAssigner {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int EXPLORATION_TARGET_DISTANCE = 800;
    private static final double MIN_ANGULAR_SEPARATION = 25.0;
    private static final double MIN_BRANCH_ANGULAR_SEPARATION = 15.0;

    /** Salts for deriving deterministic exploration-target RNGs from position + world seed. */
    private static final long SALT_EXPLORATION_TARGET = 6101L;
    private static final long SALT_FORCE_EXPLORATION_TARGET = 6102L;

    /**
     * Attempts to assign a village to an expansion head.
     * Uses the head's initial direction (not current direction) to search for villages
     * within the cardinal range (allows diagonal movement as long as majority is in the cardinal direction).
     * If no village is found, sets an exploration target in the cardinal direction.
     *
     * @param head    The head to assign a village to
     * @param level   The server level
     * @param config  Configuration settings
     * @param tracker Village assignment tracker
     * @return true if a village was assigned, false otherwise
     */
    public static boolean tryAssignVillage(TrackExpansionHead head, ServerLevel level,
                                           RailwaysUntoldConfig config, VillageAssignmentTracker tracker) {
        if (!config.ENABLE_VILLAGE_TARGETING) {
                setExplorationTarget(head, head.getInitialDirection(), level);
            return false;
        }

        VillageNetworkPlanner.computeIfStale(level);

        VillageTargetingSavedData savedData = VillageTargetingSavedData.get(level);
        AttemptedVillageTracker attemptedTracker = savedData.getAttemptedTracker();
        Direction initialDirection = head.getInitialDirection();
        java.util.function.Predicate<BlockPos> angularFilter = buildAngularFilter(head, tracker, level);

        double headDistFromSpawn = DirectionUtil.horizontalDistance(head.getPosition(), level.getSharedSpawnPos());
        VillageInfo plannerSuggested = tryAssignFromPlannerEdges(
                head, level, config, tracker, attemptedTracker, savedData,
                initialDirection, angularFilter, headDistFromSpawn, "");
        if (plannerSuggested != null) {
            head.getVillageState().clearExplorationTarget();
            return true;
        }

        VillageInfo village = null;
        VillagePredictor.PredictedVillage predicted = null;

        // Progressive search: try half radius first, then full, then 1.5x
        int[] searchRadii = {
                config.VILLAGE_SEARCH_RADIUS / 2,
                config.VILLAGE_SEARCH_RADIUS,
                (int)(config.VILLAGE_SEARCH_RADIUS * 1.5)
        };

        for (int radius : searchRadii) {
            if (radius <= 0) continue;

            predicted = VillagePredictor.findNearestPredictedVillageNotBehind(
                    level, head.getPosition(), radius, initialDirection, tracker, attemptedTracker, angularFilter);

            if (predicted != null) {
                village = VillagePredictor.createVillageInfoFromPrediction(predicted);
                VillageLocator.cachePredictedVillage(level, village);
                break;
            }

            VillageInfo fallbackVillage = VillageLocator.findNearestUnassignedVillage(
                    level, head.getPosition(), radius, tracker, attemptedTracker, config);

            if (fallbackVillage != null) {
                if (DirectionUtil.isNotBehind(head.getPosition(), fallbackVillage.center, initialDirection)) {
                    village = fallbackVillage;
                    break;
                }
            }
        }

        if (village != null) {
            double distFromSpawn = DirectionUtil.horizontalDistance(village.center, level.getSharedSpawnPos());
            if (distFromSpawn < config.VILLAGE_MIN_DISTANCE_FROM_SPAWN) {
                attemptedTracker.markVillageAttempted(village.villageId,
                        AttemptedVillageTracker.AttemptReason.TOO_CLOSE_TO_SPAWN);
                savedData.setDirty();
                setExplorationTarget(head, initialDirection, level);
                return false;
            }

            // Keep the exploration target set across the finalize attempt. If finalize
            // succeeds, we clear it below; if it fails (angular conflict, layout missing,
            // off-axis arrival, ...), the existing target stays

            VillageInfo assigned = finalizeVillageAssignment(
                    head, village, tracker, savedData, level);
            if (assigned != null) {
                head.getVillageState().clearExplorationTarget();
                return true;
            }
            setExplorationTarget(head, initialDirection, level);
            return false;
        }

        setExplorationTarget(head, initialDirection, level);
        return false;
    }

    /**
     * Sets an exploration target for the head when no village is found.
     * The exploration target is a position in the cardinal range that the head will
     * path toward while continuing to check for villages.
     *
     * @param head The expansion head
     * @param level The server level
     * @param cardinalDirection The cardinal direction to explore in
     */
    public static void setExplorationTarget(TrackExpansionHead head, Direction cardinalDirection,
                                              @Nullable ServerLevel level) {
        if (head.getVillageState().hasExplorationTarget()) {
            return;
        }

        long worldSeed = level != null ? level.getSeed() : 0L;
        BlockPos explorationTarget = DirectionUtil.generateExplorationTarget(
            head.getPosition(), cardinalDirection, EXPLORATION_TARGET_DISTANCE,
            PositionRandom.createWithSalt(head.getPosition(), worldSeed, SALT_EXPLORATION_TARGET));

        head.getVillageState().setExplorationTarget(explorationTarget);

        if (level != null) {
            CoarseRouteFactory.createAndAttach(level, head, explorationTarget);
        }
    }

    /**
     * Like {@link #setExplorationTarget} but always generates a fresh target even if one
     * is already set. Used when an existing target is confirmed unreachable
     */
    public static void forceSetExplorationTarget(TrackExpansionHead head, Direction cardinalDirection,
                                                 @Nullable ServerLevel level) {
        long worldSeed = level != null ? level.getSeed() : 0L;
        BlockPos explorationTarget = DirectionUtil.generateExplorationTarget(
            head.getPosition(), cardinalDirection, EXPLORATION_TARGET_DISTANCE,
            PositionRandom.createWithSalt(head.getPosition(), worldSeed, SALT_FORCE_EXPLORATION_TARGET));

        head.getVillageState().setExplorationTarget(explorationTarget);

        if (level != null) {
            CoarseRouteFactory.createAndAttach(level, head, explorationTarget);
        }
    }

    /**
     * Attempts to reassign a village to an exploring head.
     * Called periodically when a head is exploring without a village target.
     *
     * @param head The exploring head
     * @param level The server level
     * @param config Configuration settings
     * @param tracker Village assignment tracker
     * @return true if a village was found and assigned
     */
    public static boolean tryReassignVillageWhileExploring(TrackExpansionHead head, ServerLevel level,
                                                            RailwaysUntoldConfig config, VillageAssignmentTracker tracker) {
        if (!config.ENABLE_VILLAGE_TARGETING || !head.getVillageState().isExploring()) {
            return false;
        }

        return tryAssignVillage(head, level, config, tracker);
    }

    /**
     * Assigns a new village to a head during placement decisions.
     * Used when a head needs a village target during track expansion.
     *
     * @param head   The head to assign
     * @param level  The server level
     * @param config Configuration settings
     * @return The assigned village info, or null if none found
     */
    @Nullable
    public static VillageInfo assignNewVillageToHead(
            TrackExpansionHead head,
            ServerLevel level,
            RailwaysUntoldConfig config) {

        if (!config.ENABLE_VILLAGE_TARGETING) {
            return null;
        }

        // Check-then-swap: don't release the head's existing village binding up front.
        // searchAndAssignVillage will reassign atomically on success
        VillageInfo assigned = searchAndAssignVillage(head, level, config, SearchMode.NEW);
        if (assigned == null) {
            VillageTargetingSavedData.get(level).getAssignmentTracker().unassignHead(head.getHeadId());
            setExplorationTarget(head, head.getDirection(), level);
        }
        return assigned;
    }

    /**
     * Attempts to retarget a head to a new village.
     * Called periodically when a head has no village target.
     *
     * @param head   The head to retarget
     * @param level  The server level
     * @param config Configuration settings
     * @return true if a village was assigned
     */
    public static boolean tryRetargetHead(TrackExpansionHead head, ServerLevel level, RailwaysUntoldConfig config) {
        if (!config.ENABLE_VILLAGE_TARGETING) {
            return false;
        }

        return searchAndAssignVillage(head, level, config, SearchMode.RETARGET) != null;
    }

    /**
     * Shared core for both initial assignment and periodic retargeting: tries a predicted
     * village first, falls back to a located village, rejecting either if they would require
     * the head to backtrack toward spawn.
     */
    @Nullable
    private static VillageInfo searchAndAssignVillage(
            TrackExpansionHead head, ServerLevel level, RailwaysUntoldConfig config, SearchMode mode) {
        VillageInfo result = searchAndAssignVillageImpl(head, level, config, mode);
        emitAssignRecord(head, level, config, mode, result);
        return result;
    }

    /**
     * Emits a {@code [VILLAGE-ASSIGN]} record of this decision - the head's pose, the spawn/radius scalars,
     * every nearby candidate village with its pose-filter outcomes and tracker flags, and the chosen
     * village - so a bad choice can be replayed offline by {@link VillageAssignmentReplay}. Observational:
     * it re-queries the same candidate sources the selection used and never alters the assignment. Gated on
     * verbose logging, like {@code [COMPILE]}/{@code [REPLAY]}; the body is unprefixed so a pasted block
     * extracts cleanly into a regression case.
     */
    private static void emitAssignRecord(TrackExpansionHead head, ServerLevel level,
                                         RailwaysUntoldConfig config, SearchMode mode, @Nullable VillageInfo result) {
        if (!RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            return;
        }
        try {
            BlockPos headPos = head.getPosition();
            BlockPos spawn = level.getSharedSpawnPos();
            int radius = config.VILLAGE_SEARCH_RADIUS;
            double headDistFromSpawn = DirectionUtil.horizontalDistance(headPos, spawn);
            Direction initDir = head.getInitialDirection();

            VillageTargetingSavedData savedData = VillageTargetingSavedData.get(level);
            VillageAssignmentTracker assignmentTracker = savedData.getAssignmentTracker();
            AttemptedVillageTracker attemptedTracker = savedData.getAttemptedTracker();

            List<VillageAssignRecord.Candidate> candidates = new ArrayList<>();
            for (VillageInfo v : VillageLocator.findVillagesInRadius(level, headPos, radius)) {
                candidates.add(buildCandidate(VillageAssignRecord.Source.LOCATED, v.center, v.villageId,
                        headPos, initDir, headDistFromSpawn, spawn, assignmentTracker, attemptedTracker));
            }
            for (VillagePredictor.PredictedVillage p : VillagePredictor.predictVillagesInRadius(level, headPos, radius)) {
                VillageInfo info = VillagePredictor.createVillageInfoFromPrediction(p);
                candidates.add(buildCandidate(VillageAssignRecord.Source.PREDICTED, info.center, info.villageId,
                        headPos, initDir, headDistFromSpawn, spawn, assignmentTracker, attemptedTracker));
            }

            VillageAssignRecord rec = new VillageAssignRecord(
                    head.getHeadNumber(), mode.name(), headPos, head.getDirection(), initDir,
                    head.isOriginalHead(), spawn, radius, (int) Math.round(headDistFromSpawn),
                    result == null ? null : result.center, candidates);
            LOGGER.info("[VILLAGE-ASSIGN] head {} mode {} pos {} -> {} ({} candidates)\n{}",
                    head.getHeadNumber(), mode.name(), headPos.toShortString(),
                    result == null ? "none" : result.center.toShortString(), candidates.size(), rec.serialize());
        } catch (RuntimeException e) {
            // Observability must never break assignment.
            LOGGER.warn("[VILLAGE-ASSIGN] capture failed for head {}: {}", head.getHeadNumber(), e.toString());
        }
    }

    private static VillageAssignRecord.Candidate buildCandidate(
            VillageAssignRecord.Source source, BlockPos center, java.util.UUID villageId, BlockPos headPos,
            Direction initDir, double headDistFromSpawn, BlockPos spawn,
            VillageAssignmentTracker assignmentTracker, AttemptedVillageTracker attemptedTracker) {
        boolean notBehind = DirectionUtil.isNotBehind(headPos, center, initDir);
        boolean backtracking = DirectionUtil.horizontalDistance(center, spawn) < headDistFromSpawn * 0.8;
        boolean assigned = assignmentTracker.isVillageAssigned(villageId);
        boolean attempted = attemptedTracker.isVillageAttempted(villageId);
        return new VillageAssignRecord.Candidate(source, center, notBehind, backtracking, assigned, attempted);
    }

    @Nullable
    private static VillageInfo searchAndAssignVillageImpl(
            TrackExpansionHead head, ServerLevel level, RailwaysUntoldConfig config, SearchMode mode) {

        VillageNetworkPlanner.computeIfStale(level);

        VillageTargetingSavedData savedData = VillageTargetingSavedData.get(level);
        VillageAssignmentTracker assignmentTracker = savedData.getAssignmentTracker();
        AttemptedVillageTracker attemptedTracker = savedData.getAttemptedTracker();

        BlockPos headPos = head.getPosition();
        Direction headDir = head.getInitialDirection();
        int searchRadius = config.VILLAGE_SEARCH_RADIUS;
        double headDistFromSpawn = DirectionUtil.horizontalDistance(headPos, level.getSharedSpawnPos());

        java.util.function.Predicate<BlockPos> angularFilter = buildAngularFilter(head, assignmentTracker, level);


        VillageInfo plannerSuggested = tryAssignFromPlannerEdges(
                head, level, config, assignmentTracker, attemptedTracker, savedData,
                headDir, angularFilter, headDistFromSpawn, mode.logPrefix);
        if (plannerSuggested != null) {
            return plannerSuggested;
        }

        VillagePredictor.PredictedVillage prediction;
        if (mode.branchesUseDirectionPredictor && !head.isOriginalHead()) {
            prediction = VillagePredictor.findNearestPredictedVillageInDirection(
                    level, headPos, searchRadius, headDir, assignmentTracker, attemptedTracker, angularFilter);
        } else {
            prediction = VillagePredictor.findNearestPredictedVillageNotBehind(
                    level, headPos, searchRadius, headDir, assignmentTracker, attemptedTracker, angularFilter);
        }

        if (prediction != null && isBacktrackingToSpawn(prediction.approximateCenter,
                headDistFromSpawn, level, head.getHeadNumber(), mode.logPrefix + "rejected predicted village")) {
            prediction = null;
        }

        VillageInfo result = tryAssignPredictedVillage(
                head, prediction, assignmentTracker, savedData, level);
        if (result != null) {
            return result;
        }

        VillageInfo locatedVillage = VillageLocator.findNearestUnassignedVillage(
                level, headPos, searchRadius, assignmentTracker, attemptedTracker, config);

        // Both branch and original heads use the same 45° cone filter.
        if (locatedVillage != null && headDir != null
                && !DirectionUtil.isNotBehind(headPos, locatedVillage.center, headDir)) {
            locatedVillage = null;
        }

        if (locatedVillage != null && isBacktrackingToSpawn(locatedVillage.center,
                headDistFromSpawn, level, head.getHeadNumber(), mode.logPrefix + "rejected located village")) {
            locatedVillage = null;
        }

        VillageInfo assignedLocated = tryAssignLocatedVillage(head, locatedVillage, assignmentTracker, savedData, level);
        return assignedLocated;
    }

    private enum SearchMode {
        /** Initial assignment: branch heads use the direction predictor. */
        NEW("", true),
        /** Periodic retarget: always use the not-behind predictor, even for branch heads. */
        RETARGET("retarget ", false);

        final String logPrefix;
        final boolean branchesUseDirectionPredictor;

        SearchMode(String logPrefix, boolean branchesUseDirectionPredictor) {
            this.logPrefix = logPrefix;
            this.branchesUseDirectionPredictor = branchesUseDirectionPredictor;
        }
    }

    /**
     * Tries to assign one of the planner-suggested edges leaving any village near the head's
     * current position. Returns the first village that finalizes
     * successfully, or null to fall through to the existing predicted/located logic.
     */
    @Nullable
    private static VillageInfo tryAssignFromPlannerEdges(
            TrackExpansionHead head,
            ServerLevel level,
            RailwaysUntoldConfig config,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            VillageTargetingSavedData savedData,
            Direction headDir,
            @Nullable java.util.function.Predicate<BlockPos> angularFilter,
            double headDistFromSpawn,
            String logPrefix) {

        List<StructureConnection> nearbyEdges = VillageNetworkPlanner.getEdgesFrom(
                level, head.getPosition(), config.VILLAGE_SEARCH_RADIUS);
        if (nearbyEdges.isEmpty()) return null;

        BlockPos headPos = head.getPosition();
        long radius2 = (long) config.VILLAGE_SEARCH_RADIUS * (long) config.VILLAGE_SEARCH_RADIUS;

        // Each edge has two endpoints; the candidate is whichever endpoint is NOT the
        // node we're conceptually leaving from.
        List<BlockPos> candidates = new ArrayList<>(nearbyEdges.size() * 2);
        for (StructureConnection edge : nearbyEdges) {
            if (within2D(edge.from(), headPos, radius2)) candidates.add(edge.from());
            if (within2D(edge.to(), headPos, radius2)) candidates.add(edge.to());
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingLong(p -> dist2D(p, headPos)));

        for (BlockPos candidate : candidates) {
            if (headDir != null && !DirectionUtil.isNotBehind(headPos, candidate, headDir)) continue;
            if (angularFilter != null && !angularFilter.test(candidate)) continue;
            if (isBacktrackingToSpawn(candidate, headDistFromSpawn, level, head.getHeadNumber(),
                    logPrefix + "rejected planner edge")) continue;

            VillageInfo info = VillageNetworkPlanner.resolveVillage(level, candidate);
            if (info == null) continue;
            if (assignmentTracker.isVillageAssigned(info.villageId)) continue;
            if (attemptedTracker.isVillageAttempted(info.villageId)) continue;

            VillageInfo finalized = finalizeVillageAssignment(head, info, assignmentTracker, savedData, level);
            if (finalized != null) {
                return finalized;
            }
        }
        return null;
    }

    private static boolean within2D(BlockPos a, BlockPos b, long r2) {
        return dist2D(a, b) <= r2;
    }

    private static long dist2D(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Builds a per-head pre-filter for the predictor that rejects candidate villages whose
     * angle-from-spawn is too close to another head's locked angular sector. 
     */
    @Nullable
    private static java.util.function.Predicate<BlockPos> buildAngularFilter(
            TrackExpansionHead head, VillageAssignmentTracker tracker, ServerLevel level) {
        if (!head.isOriginalHead()) {
            return null;
        }
        BlockPos spawnPos = level.getSharedSpawnPos();
        return villageCenter -> {
            double angle = DirectionUtil.computeAngleFromOrigin(spawnPos, villageCenter);
            return !tracker.isAngleTooClose(angle, head.getHeadId(), MIN_ANGULAR_SEPARATION);
        };
    }

    /**
     * Returns true (and logs rejection) if the candidate village would require the head to
     * backtrack toward spawn - i.e. the candidate is closer to spawn than 80% of the head's
     * current distance from spawn.
     */
    private static boolean isBacktrackingToSpawn(BlockPos candidateCenter, double headDistFromSpawn,
                                                  ServerLevel level, int headNumber, String logTag) {
        double villageDist = DirectionUtil.horizontalDistance(candidateCenter, level.getSharedSpawnPos());
        if (villageDist < headDistFromSpawn * 0.8) {
            return true;
        }
        return false;
    }

    private static VillageInfo tryAssignPredictedVillage(
            TrackExpansionHead head,
            VillagePredictor.PredictedVillage prediction,
            VillageAssignmentTracker assignmentTracker,
            VillageTargetingSavedData savedData,
            ServerLevel level) {

        if (prediction == null) {
            return null;
        }

        VillageInfo villageInfo = VillagePredictor.createVillageInfoFromPrediction(prediction);
        return finalizeVillageAssignment(head, villageInfo, assignmentTracker, savedData, level);
    }

    private static VillageInfo tryAssignLocatedVillage(
            TrackExpansionHead head,
            VillageInfo villageInfo,
            VillageAssignmentTracker assignmentTracker,
            VillageTargetingSavedData savedData,
            ServerLevel level) {

        return villageInfo == null ? null : finalizeVillageAssignment(head, villageInfo, assignmentTracker, savedData, level);
    }

    private static VillageInfo finalizeVillageAssignment(
            TrackExpansionHead head,
            VillageInfo villageInfo,
            VillageAssignmentTracker assignmentTracker,
            VillageTargetingSavedData savedData,
            ServerLevel level) {

        boolean assigned = assignmentTracker.reassignVillage(villageInfo.villageId, head.getHeadId());
        if (!assigned) {
            return null;
        }

        // Angular sector exclusion: prevent heads from targeting villages in the same angular sector
        BlockPos spawnPos = level.getSharedSpawnPos();
        double angle = DirectionUtil.computeAngleFromOrigin(spawnPos, villageInfo.center);

        // Angular separation: branches use a smaller minimum (15°) since they naturally diverge.
        // Original heads keep 25° to prevent convergence from spawn.
        boolean isBranch = !head.isOriginalHead();
        boolean tooClose = assignmentTracker.isAngleTooClose(
                angle, head.getHeadId(), MIN_ANGULAR_SEPARATION, MIN_BRANCH_ANGULAR_SEPARATION, isBranch);

        if (tooClose) {
            if (isBranch) {
                // Soft constraint for branches: log warning but allow if it's the only option
            } else {
                assignmentTracker.unassignHead(head.getHeadId());
                return null;
            }
        }

        assignmentTracker.registerHeadAngle(head.getHeadId(), angle, head.isOriginalHead());

        head.getVillageState().setTarget(villageInfo.villageId, villageInfo.center, head.getPosition());

        NoiseTerrainSampler sampler = NoiseTerrainSampler.forLevel(level);

        // Confirm village exists and get its layout. Prefer surveyed ground truth (exact placed piece
        // bounds) when available; otherwise fall back to the structure.generate prediction. Either way
        // fire a one-time lazy survey so a future replan/assignment of this village uses ground truth.
        ChunkPos predictedChunk = new ChunkPos(villageInfo.center);
        com.vodmordia.railwaysuntold.worldgen.survey.SurveyedLayoutProvider.requestSurvey(level, predictedChunk);
        PredictedVillageLayout layout =
                com.vodmordia.railwaysuntold.worldgen.survey.SurveyedLayoutProvider.resolveLayout(level, predictedChunk);

        if (layout == null) {
            assignmentTracker.unassignHead(head.getHeadId());
            head.getVillageState().clear();
            savedData.getAttemptedTracker().markVillageAttempted(villageInfo.villageId,
                    AttemptedVillageTracker.AttemptReason.LAYOUT_PREDICTION_FAILED);
            savedData.setDirty();
            return null;
        }

        head.getVillageState().setPrecomputedLayout(layout);

        // Select station schematic for biome at village center
        com.vodmordia.railwaysuntold.datapack.SelectedStation selectedStation =
                StationSchematicCache.selectStation(level, villageInfo.center,
                        new Random(level.getSeed() + villageInfo.center.hashCode()));
        if (selectedStation == null) {
            assignmentTracker.unassignHead(head.getHeadId());
            head.getVillageState().clear();
            savedData.getAttemptedTracker().markVillageAttempted(villageInfo.villageId,
                    AttemptedVillageTracker.AttemptReason.NO_VIABLE_STATION_POSITION);
            savedData.setDirty();
            return null;
        }

        // Pre-compute a ranked list of viable arrival poses. The first is the primary
        // (locked on the head); the rest are fallbacks tried in order if the
        // primary's compile+fit check fails.
        java.util.List<StationPlan> rankedPlans = NoiseStationPlanner.planStationWithAlternatives(
                sampler, layout, head.getPosition(), head.getDirection(),
                head.getPosition().getY(), selectedStation);
        // Prefer an approach side whose station footprint clears existing track. A head that
        // branched and looped back could otherwise pick a side that buries its own pre-branch
        // mainline; demoting those sides avoids building the doomed approach (the commit-time
        // burial guard still rejects if the actual arrival pose lands on track).
        rankedPlans = StationTrackBurialCheck.demoteBuryingPlans(level, rankedPlans, selectedStation);
        StationPlan plan = rankedPlans.isEmpty() ? null : rankedPlans.get(0);

        if (plan == null) {
            assignmentTracker.unassignHead(head.getHeadId());
            head.getVillageState().clear();
            savedData.getAttemptedTracker().markVillageAttempted(villageInfo.villageId,
                    AttemptedVillageTracker.AttemptReason.NO_VIABLE_STATION_POSITION);
            savedData.setDirty();
            return null;
        }

        // Verify the arrival pose is predominantly in the head's forward direction.
        // A village whose target arrival is more lateral than forward creates bad routes.
        BlockPos arrivalPos = plan.arrivalPos();
        int forwardToArrival = (arrivalPos.getX() - head.getPosition().getX()) * head.getDirection().getStepX()
                + (arrivalPos.getZ() - head.getPosition().getZ()) * head.getDirection().getStepZ();
        int lateralToArrival = Math.abs(
                (arrivalPos.getX() - head.getPosition().getX()) * head.getDirection().getClockWise().getStepX()
                + (arrivalPos.getZ() - head.getPosition().getZ()) * head.getDirection().getClockWise().getStepZ());

        if (forwardToArrival <= 0 || lateralToArrival > forwardToArrival) {
            assignmentTracker.unassignHead(head.getHeadId());
            head.getVillageState().clear();
            savedData.getAttemptedTracker().markVillageAttempted(villageInfo.villageId,
                    AttemptedVillageTracker.AttemptReason.NO_VALID_APPROACH);
            savedData.setDirty();
            return null;
        }

        // Lock the station plan.
        Direction approachSide = com.vodmordia.railwaysuntold.util.spatial.DirectionUtil
                .getSegmentDirection(villageInfo.center, plan.arrivalPos());
        if (approachSide == null) approachSide = head.getDirection();
        head.getVillageState().setLockedStationPlan(plan);
        head.getVillageState().setStationPlanAlternatives(rankedPlans);
        head.getVillageState().setSelectedStation(selectedStation);
        head.getVillageState().setPlannedApproachDirection(approachSide);
        head.getVillageState().setApproachWaypoint(plan.arrivalPos());
        CoarseRouteFactory.createAndAttach(level, head, plan.arrivalPos());


        VillageLocator.cachePredictedVillage(level, villageInfo);
        savedData.addDiscoveredVillage(villageInfo);
        savedData.setDirty();

        return villageInfo;
    }
}
