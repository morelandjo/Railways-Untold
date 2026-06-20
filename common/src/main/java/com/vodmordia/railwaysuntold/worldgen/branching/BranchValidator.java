package com.vodmordia.railwaysuntold.worldgen.branching;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteRegistry;
import com.vodmordia.railwaysuntold.worldgen.terrain.BranchTerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageInfo;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Validates branch eligibility and decides optimal branch direction.
 *
 */
public class BranchValidator {

    // Position-based random salts for deterministic randomness
    private static final long SALT_RANDOM_CHANCE = 200L;
    private static final long SALT_VILLAGE_TARGETING = 201L;

    private static final int PARENT_ROUTE_PROXIMITY_THRESHOLD = 200;
    private static final double PARENT_ANGULAR_SEPARATION_MIN = 30.0;

    // ==================== PUBLIC API ====================

    /**
     * Main entry point: validates eligibility and decides branch direction.
     *
     * @param head The head considering branching
     * @param currentPos Current position of the head
     * @param currentDir Current direction of the head
     * @param scan Terrain scan result for checking terrain ahead
     * @param level The server level
     * @param config The configuration
     * @param tracker Village assignment tracker (nullable)
     * @param headManager The expansion head manager for sibling awareness (nullable)
     * @return BranchDecision with whether to branch and which direction, or null if no branch
     */
    @Nullable
    public static BranchDecision validateAndDecide(
            TrackExpansionHead head,
            BlockPos currentPos,
            Direction currentDir,
            TerrainScanner.TerrainScan scan,
            ServerLevel level,
            RailwaysUntoldConfig config,
            @Nullable VillageAssignmentTracker tracker,
            @Nullable ExpansionHeadManager headManager
    ) {
        if (!head.isEligibleForBranch(config)) {
            return null;
        }

        if (!hasValidGroundForBranch(currentPos, level)) {
            return null;
        }

        // Defer branching over ravines
        if (scan != null) {
            TerrainAnalyzer.TerrainAnalysis analysis = TerrainAnalyzer.analyzeTerrain(scan, currentPos);
            if (analysis.isRavine()) {
                return null;
            }
        }

        boolean wasForced = shouldForceBranch(head, config);

        if (!wasForced && !passesRandomChance(currentPos, level.getSeed(), config.BRANCH_CHANCE)) {
            return null;
        }

        // Compute the parent's trajectory direction so the branch goes perpendicular to
        // where the parent is actually heading - not its transient mid-curve heading.
        Direction trajectoryDir = computeTrajectoryDirection(head, currentPos, currentDir);
        Direction leftDir = DirectionUtil.getLeftDirection(trajectoryDir);
        Direction rightDir = DirectionUtil.getRightDirection(trajectoryDir);

        BranchTerrainAnalyzer.BranchTerrainScore leftScore = BranchTerrainAnalyzer.scoreBranchTerrain(currentPos, leftDir, level);
        BranchTerrainAnalyzer.BranchTerrainScore rightScore = BranchTerrainAnalyzer.scoreBranchTerrain(currentPos, rightDir, level);

        Optional<Direction> directionResult = BranchDirectionSelector.selectDirection(
            currentPos, level.getSeed(),
            leftDir, rightDir,
            leftScore, rightScore,
            wasForced,  // allowPoorTerrain only if forced
            head,       // parent head for sibling awareness
            headManager, // head manager for looking up siblings
            level,       // for existing-track proximity checks
            tracker     // for angular spread checks
        );

        if (directionResult.isEmpty()) {
            return null;
        }

        Direction chosenDir = directionResult.get();

        // The branch curve geometry assumes branch is perpendicular to the parent's
        // current direction. When the parent has just curved into trajectoryDir's
        // perpendicular (e.g. trajectory=east, currentDir=north after a left curve),
        // chosenDir computed from trajectoryDir can land parallel to currentDir -
        // BranchTrackCreator's isLeftTurn check then defaults to a right turn whose
        // exit direction differs from chosenDir, leaving the new head facing the
        // wrong way relative to the curve it just placed.
        if (chosenDir.getAxis() == currentDir.getAxis()) {
            return null;
        }

        VillageInfo targetVillage = findTargetVillage(head, currentPos, chosenDir, level, config, tracker);


        return new BranchDecision(true, chosenDir, targetVillage);
    }

    /**
     * Validates if a branch can be safely spawned at the given position.
     *
     * @return ValidationResult with success flag and reason
     */
    public static ValidationResult validateSpawn(
            TrackExpansionHead head,
            ExpansionHeadManager manager,
            BlockPos branchPos,
            Direction branchDir,
            ServerLevel level
    ) {
        if (!BranchValidationChecks.passesRuntimeChecks(head, manager, branchPos, branchDir, level)) {
            return new ValidationResult(false, "Runtime safety checks failed");
        }

        if (!BranchValidationChecks.hasAdequateSpacing(branchPos, manager, RailwaysUntoldConfig.getMinBranchSpacing(), head)) {
            return new ValidationResult(false, "Insufficient spacing from other branches");
        }

        return new ValidationResult(true, "Branch spawn validated");
    }


    private static boolean shouldForceBranch(TrackExpansionHead head, RailwaysUntoldConfig config) {
        return config.MAX_BLOCKS_WITHOUT_BRANCH > 0 &&
                head.getBlocksSinceLastBranch() >= config.MAX_BLOCKS_WITHOUT_BRANCH;
    }

    /**
     * Returns the cardinal direction the parent is overall heading toward its village
     * target if it has one, otherwise its exploration target, otherwise its
     * initialDirection.
     */
    private static Direction computeTrajectoryDirection(
            TrackExpansionHead head, BlockPos currentPos, Direction currentDir) {
        BlockPos target = head.getVillageState().getRouteTarget();
        if (target != null) {
            Direction toTarget = DirectionUtil.getSegmentDirection(currentPos, target);
            if (toTarget != null) {
                return toTarget;
            }
        }
        Direction initial = head.getInitialDirection();
        return initial != null ? initial : currentDir;
    }

    private static boolean passesRandomChance(BlockPos pos, long seed, int chancePercent) {
        int roll = PositionRandom.createWithSalt(pos, seed, SALT_RANDOM_CHANCE).nextInt(100);
        return roll < chancePercent;
    }

    private static boolean hasValidGroundForBranch(BlockPos pos, ServerLevel level) {
        return TerrainScanner.hasValidGroundForTrack(level, pos);
    }

    @Nullable
    private static VillageInfo findTargetVillage(
            TrackExpansionHead head, BlockPos pos, Direction branchDirection, ServerLevel level,
            RailwaysUntoldConfig config, @Nullable VillageAssignmentTracker tracker
    ) {
        if (!config.ENABLE_VILLAGE_TARGETING || tracker == null) {
            return null;
        }

        int roll = PositionRandom.createWithSalt(pos, level.getSeed(), SALT_VILLAGE_TARGETING).nextInt(100);
        if (roll >= config.VILLAGE_TARGETING_CHANCE) {
            return null;
        }

        VillageInfo candidate = VillageLocator.findPredictedVillageForBranch(
                level, pos, config.VILLAGE_SEARCH_RADIUS, branchDirection, tracker);

        if (candidate == null) {
            return null;
        }

        // Reject if candidate village is too close to parent's coarse route
        CoarseRoute parentRoute = CoarseRouteRegistry.forLevel(level).getRoute(head.getHeadId());
        if (parentRoute != null) {
            List<CoarseRoute.CoarseWaypoint> waypoints = parentRoute.getWaypoints();
            for (CoarseRoute.CoarseWaypoint wp : waypoints) {
                if (DirectionUtil.horizontalDistance(wp.position(), candidate.center) < PARENT_ROUTE_PROXIMITY_THRESHOLD) {
                    return null;
                }
            }
        }

        // Reject if angular separation from parent's village target is too small
        BlockPos parentVillage = head.getVillageState().getTargetVillageCenter();
        if (parentVillage != null) {
            double separation = DirectionUtil.angularSeparation(pos, parentVillage, candidate.center);
            if (separation < PARENT_ANGULAR_SEPARATION_MIN) {
                return null;
            }
        }

        return candidate;
    }

    public static class BranchDecision {
        public final boolean approved;
        public final Direction branchDirection;
        public final VillageInfo targetVillage;

        public BranchDecision(boolean approved, Direction branchDirection,
                              VillageInfo targetVillage) {
            this.approved = approved;
            this.branchDirection = branchDirection;
            this.targetVillage = targetVillage;
        }
    }

    public static class ValidationResult {
        public final boolean success;
        public final String reason;

        public ValidationResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason;
        }
    }
}
