package com.vodmordia.railwaysuntold.worldgen.branching;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.util.spatial.PositionRandom;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRoute;
import com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteRegistry;
import com.vodmordia.railwaysuntold.worldgen.terrain.BranchTerrainAnalyzer;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageAssignmentTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;


/**
 * Selects the optimal direction for a branch based on terrain analysis
 * and sibling branch awareness.
 */
public class BranchDirectionSelector {

    /**
     * Bonus score applied to the direction that diverges from existing siblings.
     * This makes branches strongly prefer spreading outward rather than clustering.
     */
    private static final int SIBLING_DIVERGENCE_BONUS = 50;

    /**
     * Penalty applied when existing track is found in a branch direction.
     * Scaled by proximity - closer track = higher penalty.
     */
    private static final int EXISTING_TRACK_PENALTY = 80;

    /**
     * Distance (blocks) within which a parallel track triggers a hard veto.
     */
    private static final int PARALLEL_TRACK_VETO_DISTANCE = 32;

    /**
     * Multiplier applied to existing track penalty when the nearby track is parallel.
     */
    private static final int PARALLEL_PENALTY_MULTIPLIER = 3;

    /**
     * Interval (blocks) between sample points along the branch direction for track checking.
     */
    private static final int RAY_SAMPLE_INTERVAL = 32;

    /**
     * Distance (blocks) to check for existing track in each branch direction.
     */
    private static final int TRACK_PROXIMITY_CHECK_DISTANCE = 200;

    /**
     * Penalty applied when a candidate direction points back toward the parent's origin.
     * Prevents branches from doubling back on the network.
     */
    private static final int PARENT_ORIGIN_PENALTY = 70;

    /**
     * Minimum distance (blocks) from origin before applying the parent origin penalty.
     * Avoids noise when branch is still very close to its spawn point.
     */
    private static final int MIN_ORIGIN_DISTANCE = 32;

    /**
     * Penalty applied when a branch direction runs close to the parent's coarse route.
     */
    private static final int PARENT_ROUTE_PROXIMITY_PENALTY = 80;

    /**
     * Distance threshold (blocks) for parent route proximity penalty.
     */
    private static final int PARENT_ROUTE_PROXIMITY_THRESHOLD = 64;

    /**
     * Distance (blocks) to project the branch trajectory for parent route checking.
     */
    private static final int PARENT_ROUTE_CHECK_DISTANCE = 200;

    /**
     * Penalty applied when a branch direction has poor angular separation from other heads.
     */
    private static final int ANGULAR_SPREAD_PENALTY = 60;

    /**
     * Minimum angular separation (degrees) required between branch directions.
     */
    private static final double BRANCH_ANGULAR_MIN_SEPARATION = 25.0;

    /**
     * Distance (blocks) to project branch for angular spread computation.
     */
    private static final int ANGULAR_PROJECTION_DISTANCE = 200;

    /**
     * Selects the optimal branch direction based on terrain analysis, sibling awareness,
     * existing track proximity, parent route proximity, and angular spread.
     *
     * @param pos              Current position (used for deterministic random)
     * @param seed             World seed (used for deterministic random)
     * @param leftDir          Direction to the left of current heading
     * @param rightDir         Direction to the right of current heading
     * @param leftScore        Terrain score for left direction
     * @param rightScore       Terrain score for right direction
     * @param allowPoorTerrain If true, allows selection even when both scores are <= 0
     * @param parentHead       The parent head creating this branch (nullable)
     * @param headManager      The expansion head manager for looking up siblings (nullable)
     * @param level            The server level for existing-track proximity checks (nullable)
     * @param villageTracker   Village assignment tracker for angular spread checks (nullable)
     * @return the chosen direction, or empty if both directions are poor and not forced
     */
    public static Optional<Direction> selectDirection(
            BlockPos pos, long seed,
            Direction leftDir, Direction rightDir,
            BranchTerrainAnalyzer.BranchTerrainScore leftScore,
            BranchTerrainAnalyzer.BranchTerrainScore rightScore,
            boolean allowPoorTerrain,
            @Nullable TrackExpansionHead parentHead,
            @Nullable ExpansionHeadManager headManager,
            @Nullable ServerLevel level,
            @Nullable VillageAssignmentTracker villageTracker
    ) {
        // Apply sibling divergence bonus before evaluating viability
        int adjustedLeftScore = leftScore.score;
        int adjustedRightScore = rightScore.score;

        if (parentHead != null && headManager != null) {
            Direction siblingBias = getSiblingDivergenceDirection(parentHead, headManager, pos, leftDir, rightDir);
            if (siblingBias == leftDir) {
                adjustedLeftScore += SIBLING_DIVERGENCE_BONUS;
            } else if (siblingBias == rightDir) {
                adjustedRightScore += SIBLING_DIVERGENCE_BONUS;
            }
        }

        // Apply parent origin penalty - discourage doubling back toward where the parent came from
        if (parentHead != null) {
            Direction penaltyDir = getOriginPenaltyDirection(parentHead, headManager, pos, leftDir, rightDir);
            if (penaltyDir != null) {
                if (penaltyDir == leftDir) {
                    adjustedLeftScore -= PARENT_ORIGIN_PENALTY;
                } else if (penaltyDir == rightDir) {
                    adjustedRightScore -= PARENT_ORIGIN_PENALTY;
                }
            }
        }

        // Apply existing-track proximity penalty
        if (level != null) {
            int leftPenalty = getExistingTrackPenalty(pos, leftDir, level);
            int rightPenalty = getExistingTrackPenalty(pos, rightDir, level);

            // Hard veto: if one direction is vetoed, force the other
            if (leftPenalty == Integer.MAX_VALUE && rightPenalty == Integer.MAX_VALUE) {
                return Optional.empty();
            }
            if (leftPenalty == Integer.MAX_VALUE) {
                adjustedLeftScore = Integer.MIN_VALUE / 2;
            } else {
                adjustedLeftScore -= leftPenalty;
            }
            if (rightPenalty == Integer.MAX_VALUE) {
                adjustedRightScore = Integer.MIN_VALUE / 2;
            } else {
                adjustedRightScore -= rightPenalty;
            }
        }

        // Apply parent route proximity penalty
        if (parentHead != null && level != null) {
            int leftRoutePenalty = getParentRoutePenalty(pos, leftDir, parentHead, level);
            int rightRoutePenalty = getParentRoutePenalty(pos, rightDir, parentHead, level);
            // Veto when both candidates are heavily penalized: the parent's own coarse
            // route crosses both rays closely, meaning whichever way we branch the new
            // head will end up parallel to (or re-converging with) the parent.
            if (leftRoutePenalty > 70 && rightRoutePenalty > 70) {
                return Optional.empty();
            }
            adjustedLeftScore -= leftRoutePenalty;
            adjustedRightScore -= rightRoutePenalty;
        }

        // Veto branch direction toward an active siding
        if (parentHead != null) {
            net.minecraft.core.Direction sidingSide = parentHead.getSidingState().getSidingSide();
            if (sidingSide != null) {
                if (sidingSide == leftDir) {
                    adjustedLeftScore = Integer.MIN_VALUE / 2;
                } else if (sidingSide == rightDir) {
                    adjustedRightScore = Integer.MIN_VALUE / 2;
                }
            }
        }

        // Apply angular spread penalty
        if (villageTracker != null && level != null && parentHead != null) {
            int leftAngularPenalty = getAngularSpreadPenalty(pos, leftDir, level, villageTracker, parentHead.getHeadId());
            int rightAngularPenalty = getAngularSpreadPenalty(pos, rightDir, level, villageTracker, parentHead.getHeadId());
            adjustedLeftScore -= leftAngularPenalty;
            adjustedRightScore -= rightAngularPenalty;
        }

        boolean leftViable = adjustedLeftScore > 0;
        boolean rightViable = adjustedRightScore > 0;

        // Both directions are poor terrain
        if (!leftViable && !rightViable) {
            if (!allowPoorTerrain) {
                return Optional.empty();
            }
            // Forced - still prefer sibling divergence direction if available
            if (adjustedLeftScore != adjustedRightScore) {
                Direction chosen = adjustedLeftScore > adjustedRightScore ? leftDir : rightDir;
                return Optional.of(chosen);
            }
            Direction chosen = pickRandom(pos, seed, leftDir, rightDir);
            return Optional.of(chosen);
        }

        // Only one direction is viable
        if (leftViable && !rightViable) {
            return Optional.of(leftDir);
        }
        if (rightViable && !leftViable) {
            return Optional.of(rightDir);
        }

        // Both directions are viable - use weighted random selection with adjusted scores
        Direction chosen = pickWeightedRandom(pos, seed, leftDir, rightDir, adjustedLeftScore, adjustedRightScore);
        return Optional.of(chosen);
    }

    /**
     * Calculates a penalty for existing track proximity in a given direction.
     * Samples points along the branch direction every RAY_SAMPLE_INTERVAL blocks,
     * checking a 3x3 chunk area around each sample for tracked segments.
     * Detects parallel tracks (hard veto if within PARALLEL_TRACK_VETO_DISTANCE)
     * and applies scaled penalties based on proximity and alignment.
     *
     * @return Penalty score (0 if no track nearby, Integer.MAX_VALUE for hard veto,
     *         or up to EXISTING_TRACK_PENALTY * PARALLEL_PENALTY_MULTIPLIER)
     */
    private static int getExistingTrackPenalty(BlockPos branchPos, Direction dir, ServerLevel level) {
        double closestDist = Double.MAX_VALUE;
        boolean closestIsParallel = false;

        Direction branchDir = dir;
        int sampleCount = TRACK_PROXIMITY_CHECK_DISTANCE / RAY_SAMPLE_INTERVAL;

        for (int s = 1; s <= sampleCount; s++) {
            BlockPos samplePos = branchPos.relative(dir, s * RAY_SAMPLE_INTERVAL);
            ChunkPos sampleChunk = new ChunkPos(samplePos);

            // Check 3x3 chunk area around the sample point
            for (int cx = -1; cx <= 1; cx++) {
                for (int cz = -1; cz <= 1; cz++) {
                    ChunkPos checkChunk = new ChunkPos(sampleChunk.x + cx, sampleChunk.z + cz);
                    List<ConnectedSegment> segments = ConnectedBoundaryTracker.getSegmentsInChunk(level, checkChunk);

                    for (ConnectedSegment seg : segments) {
                        Direction segDir = DirectionUtil.getSegmentDirection(seg.start, seg.end);
                        boolean isParallel = !DirectionUtil.arePerpendicular(branchDir, segDir);

                        double distStart = DirectionUtil.horizontalDistance(samplePos, seg.start);
                        double distEnd = DirectionUtil.horizontalDistance(samplePos, seg.end);
                        double dist = Math.min(distStart, distEnd);

                        // Hard veto: parallel track within veto distance
                        if (isParallel && dist < PARALLEL_TRACK_VETO_DISTANCE) {
                            return Integer.MAX_VALUE;
                        }

                        if (dist < closestDist) {
                            closestDist = dist;
                            closestIsParallel = isParallel;
                        }
                    }
                }
            }
        }

        if (closestDist < TRACK_PROXIMITY_CHECK_DISTANCE) {
            double factor = 1.0 - closestDist / TRACK_PROXIMITY_CHECK_DISTANCE;
            int basePenalty = (int) (EXISTING_TRACK_PENALTY * factor);
            return closestIsParallel ? basePenalty * PARALLEL_PENALTY_MULTIPLIER : basePenalty;
        }
        return 0;
    }

    /**
     * Calculates a penalty for branch directions that run close to the parent head's
     * coarse route. Projects the branch trajectory forward and checks perpendicular
     * distance to each parent route waypoint.
     *
     * @return Penalty score (0 if no proximity issue, up to PARENT_ROUTE_PROXIMITY_PENALTY)
     */
    private static int getParentRoutePenalty(BlockPos branchPos, Direction dir,
                                             TrackExpansionHead parentHead, ServerLevel level) {
        CoarseRoute parentRoute = CoarseRouteRegistry.forLevel(level).getRoute(parentHead.getHeadId());
        if (parentRoute == null) {
            return 0;
        }

        // Project branch trajectory as a ray
        double rayDirX = dir.getStepX();
        double rayDirZ = dir.getStepZ();
        double rayLength = PARENT_ROUTE_CHECK_DISTANCE;

        double minPerpDist = Double.MAX_VALUE;

        for (CoarseRoute.CoarseWaypoint wp : parentRoute.getWaypoints()) {
            BlockPos wpPos = wp.position();

            // Vector from branchPos to waypoint
            double toWpX = wpPos.getX() - branchPos.getX();
            double toWpZ = wpPos.getZ() - branchPos.getZ();

            // Project onto ray direction (dot product)
            double projection = toWpX * rayDirX + toWpZ * rayDirZ;

            // Skip waypoints not alongside the ray (behind or beyond)
            if (projection < 0 || projection > rayLength) {
                continue;
            }

            // Compute perpendicular distance
            double projX = branchPos.getX() + rayDirX * projection;
            double projZ = branchPos.getZ() + rayDirZ * projection;
            double perpDist = Math.sqrt(
                    (wpPos.getX() - projX) * (wpPos.getX() - projX) +
                    (wpPos.getZ() - projZ) * (wpPos.getZ() - projZ));

            if (perpDist < minPerpDist) {
                minPerpDist = perpDist;
            }
        }

        if (minPerpDist < PARENT_ROUTE_PROXIMITY_THRESHOLD) {
            double factor = 1.0 - minPerpDist / PARENT_ROUTE_PROXIMITY_THRESHOLD;
            return (int) (PARENT_ROUTE_PROXIMITY_PENALTY * factor);
        }
        return 0;
    }

    /**
     * Calculates an angular spread penalty for branch directions that are too close
     * to existing head angles as tracked by the VillageAssignmentTracker.
     * Projects the branch destination and checks angular separation from the network origin.
     *
     * @return ANGULAR_SPREAD_PENALTY if angle is too close, 0 otherwise
     */
    private static int getAngularSpreadPenalty(BlockPos branchPos, Direction dir,
                                               ServerLevel level,
                                               VillageAssignmentTracker tracker,
                                               UUID parentHeadId) {
        BlockPos projected = branchPos.relative(dir, ANGULAR_PROJECTION_DISTANCE);
        BlockPos origin = level.getSharedSpawnPos();
        double angle = DirectionUtil.computeAngleFromOrigin(origin, projected);

        if (tracker.isAngleTooClose(angle, parentHeadId, BRANCH_ANGULAR_MIN_SEPARATION)) {
            return ANGULAR_SPREAD_PENALTY;
        }
        return 0;
    }

    /**
     * Determines which direction diverges from existing sibling branches.
     * Looks at the parent's existing child branches and returns the direction
     * that is opposite to where the majority of siblings went.
     *
     * @return The direction that diverges from siblings, or null if no bias needed
     */
    @Nullable
    private static Direction getSiblingDivergenceDirection(
            TrackExpansionHead parentHead, ExpansionHeadManager headManager,
            BlockPos branchPos, Direction leftDir, Direction rightDir) {

        List<UUID> childBranchIds = parentHead.getChildBranchIds();
        if (childBranchIds == null || childBranchIds.isEmpty()) {
            return null;
        }

        int leftCount = 0;
        int rightCount = 0;

        for (UUID childId : childBranchIds) {
            TrackExpansionHead sibling = headManager.getHeadById(childId);
            if (sibling == null) continue;

            // Check where this sibling is relative to the proposed branch position
            BlockPos siblingPos = sibling.getPosition();
            int dx = siblingPos.getX() - branchPos.getX();
            int dz = siblingPos.getZ() - branchPos.getZ();

            // Determine if sibling is on the left or right side
            if (isInDirectionOf(dx, dz, leftDir)) {
                leftCount++;
            } else if (isInDirectionOf(dx, dz, rightDir)) {
                rightCount++;
            }
        }

        if (leftCount > rightCount) {
            return rightDir;  // Siblings went left, prefer right
        } else if (rightCount > leftCount) {
            return leftDir;   // Siblings went right, prefer left
        }
        return null;  // Balanced or no siblings found
    }

    /**
     * Determines which candidate direction (left or right) points back toward the parent's origin.
     * For branch heads, uses the branch origin position. For original heads, uses the opposite
     * of the initial direction.
     *
     * @return The direction that points toward the origin (should be penalized), or null if none
     */
    @Nullable
    private static Direction getOriginPenaltyDirection(
            TrackExpansionHead parentHead, @Nullable ExpansionHeadManager headManager,
            BlockPos pos, Direction leftDir, Direction rightDir) {

        UUID parentId = parentHead.getParentHeadId();

        if (parentId != null) {
            // This is a branch head - use position-based check
            BlockPos originPos = parentHead.getBranchOriginPos();
            if (originPos == null && headManager != null) {
                // Fallback: use grandparent head position
                TrackExpansionHead grandparent = headManager.getHeadById(parentId);
                if (grandparent != null) {
                    originPos = grandparent.getPosition();
                }
            }
            if (originPos == null) return null;

            int dx = originPos.getX() - pos.getX();
            int dz = originPos.getZ() - pos.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < MIN_ORIGIN_DISTANCE) return null;

            if (isInDirectionOf(dx, dz, leftDir)) return leftDir;
            if (isInDirectionOf(dx, dz, rightDir)) return rightDir;
        } else {
            // Original head - use opposite of initial direction
            Direction backward = parentHead.getInitialDirection().getOpposite();
            if (leftDir == backward) return leftDir;
            if (rightDir == backward) return rightDir;
        }

        return null;
    }

    /**
     * Checks if a delta vector (dx, dz) has a component in the given direction.
     */
    private static boolean isInDirectionOf(int dx, int dz, Direction dir) {
        return switch (dir) {
            case NORTH -> dz < 0;
            case SOUTH -> dz > 0;
            case EAST -> dx > 0;
            case WEST -> dx < 0;
            default -> false;
        };
    }

    /**
     * 50/50 random direction choice.
     */
    private static Direction pickRandom(BlockPos pos, long seed, Direction left, Direction right) {
        boolean pickLeft = PositionRandom.createWithSalt(pos, seed, 202L).nextInt(100) < 50;
        return pickLeft ? left : right;
    }

    /**
     * Weighted random choice based on terrain scores.
     * Higher score = higher probability of being chosen.
     */
    private static Direction pickWeightedRandom(BlockPos pos, long seed,
                                                Direction left, Direction right,
                                                int leftScore, int rightScore) {
        // Ensure scores are at least 1 to avoid division by zero
        int safeLeftScore = Math.max(1, leftScore);
        int safeRightScore = Math.max(1, rightScore);
        int total = safeLeftScore + safeRightScore;

        int roll = PositionRandom.createWithSalt(pos, seed, 203L).nextInt(total);
        return roll < safeLeftScore ? left : right;
    }
}
