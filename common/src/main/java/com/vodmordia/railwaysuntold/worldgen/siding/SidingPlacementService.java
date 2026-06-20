package com.vodmordia.railwaysuntold.worldgen.siding;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.head.state.SidingState;
import com.vodmordia.railwaysuntold.worldgen.head.state.SidingState.PendingSiding;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementConstants;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Service that checks and triggers siding placement after each track placement.
 */
public class SidingPlacementService {

    private static final int SIDING_STRAIGHT_LENGTH = 16;
    private static final int MAX_SIDING_ATTEMPTS = 4;

    /**
     * Updates siding tracking state
     * and triggers siding creation when conditions are met.
     */
    public static void onPlacementComplete(TrackExpansionHead head, PlacementDecision decision, ServerLevel level) {
        PlacementDecision.Type type = decision.getType();

        // Only straight-like placements contribute to siding eligibility
        if (type == PlacementDecision.Type.BEZIER || type == PlacementDecision.Type.STRAIGHT) {
            // Only count if direction didn't change (bezier in same direction as head)
            Direction decisionDir = decision.getDirection();
            if (decisionDir == null || decisionDir == head.getDirection()) {
                int distance = decision.getDistance() > 0
                        ? decision.getDistance()
                        : PlacementConstants.STANDARD_SEGMENT_LENGTH;
                head.incrementSidingDistance(distance);
            } else {
                head.resetSidingState();
                return;
            }
        } else {
            // Any non-straight placement resets the counter
            head.resetSidingState();
            return;
        }

        // Drain any pending siding retries first. They take precedence over new attempts,
        // since the head has already committed (markPlaced) to that location's stretch.
        drainPendingRetries(head, level);
        if (head.getSidingState().hasPendingRetries()) {
            return;
        }

        // Check if eligible for siding placement
        int minBlocks = RailwaysUntoldConfig.getSidingMinStraightSegments() * PlacementConstants.STANDARD_SEGMENT_LENGTH;
        if (!head.getSidingState().isEligible(minBlocks)) {
            return;
        }

        // Validate conditions
        RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
        SidingValidator.ValidationResult validation = SidingValidator.validate(
                head, head.getPosition(), head.getDirection(), level, config);

        if (!validation.approved) {
            return;
        }

        // Calculate siding origin behind current head position
        Direction dir = head.getDirection();
        int totalForward = SidingTrackCreator.calculateTotalForwardDistance(SIDING_STRAIGHT_LENGTH);

        // Ensure we don't place the siding beyond where the head started its straight stretch
        if (totalForward > head.getSidingState().getConsecutiveStraightBlocks()) {
            return;
        }

        BlockPos horizontalOrigin = head.getPosition().relative(dir.getOpposite(), totalForward);

        // Find the actual track block at the origin - the Y level may differ from head
        // if beziers with elevation change were counted as straight
        BlockPos sidingOrigin = findTrackAtPosition(level, horizontalOrigin);
        if (sidingOrigin == null) {
            return;
        }

        // Also verify the reconnect point (end of siding on parent track) has track
        BlockPos reconnectPos = new BlockPos(
                head.getPosition().getX(), sidingOrigin.getY(), head.getPosition().getZ());
        BlockPos reconnectTrack = findTrackAtPosition(level, reconnectPos);
        if (reconnectTrack == null || reconnectTrack.getY() != sidingOrigin.getY()) {
            return; // junction endpoints at different Y or no track to reconnect to - skip the siding
        }

        // Build the siding
        Direction sidingSide = DirectionUtil.getPerpendicularDirection(dir, validation.placeLeft);
        SidingTrackCreator.SidingResult result = SidingTrackCreator.buildSiding(
                level, sidingOrigin, dir, validation.placeLeft, SIDING_STRAIGHT_LENGTH, head.getHeadId());

        if (result.success) {
            head.getSidingState().markPlaced(sidingSide);
        } else if (result.needsRetry) {
            // Lock the stretch so a new siding isn't attempted at a later origin while this one retries.
            head.getSidingState().markPlaced(sidingSide);
            head.getSidingState().enqueueRetry(new PendingSiding(
                    sidingOrigin, dir, validation.placeLeft, SIDING_STRAIGHT_LENGTH, sidingSide, 1));
        }
    }

    /**
     * Re-attempts each pending siding. Successful attempts settle silently; still-not-ready
     * attempts re-enqueue with an incremented attempt count; attempts that have exceeded
     * MAX_SIDING_ATTEMPTS or hit a hard failure are dropped with a warning.
     */
    private static void drainPendingRetries(TrackExpansionHead head, ServerLevel level) {
        SidingState state = head.getSidingState();
        if (!state.hasPendingRetries()) {
            return;
        }
        List<PendingSiding> pending = state.drainRetries();
        for (PendingSiding p : pending) {
            SidingTrackCreator.SidingResult result = SidingTrackCreator.buildSiding(
                    level, p.sidingOrigin(), p.parentDir(), p.placeLeft(),
                    p.sidingStraightLength(), head.getHeadId());

            if (result.success) {
                continue;
            }
            if (result.needsRetry && p.attempts() < MAX_SIDING_ATTEMPTS) {
                state.enqueueRetry(p.withIncrementedAttempts());
                continue;
            }
        }
    }

    /**
     * Searches for a track block at or near the given position, scanning ±2 Y levels.
     * Returns the position where track was found, or null if none exists.
     */
    private static BlockPos findTrackAtPosition(ServerLevel level, BlockPos pos) {
        // Check exact position first
        BlockState state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (state != null && CreateTrackUtil.isTrackBlock(state)) {
            return pos;
        }
        // Scan nearby Y levels
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos above = pos.above(dy);
            state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, above);
            if (state != null && CreateTrackUtil.isTrackBlock(state)) {
                return above;
            }
            BlockPos below = pos.below(dy);
            state = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, below);
            if (state != null && CreateTrackUtil.isTrackBlock(state)) {
                return below;
            }
        }
        return null;
    }
}
