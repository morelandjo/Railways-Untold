package com.vodmordia.railwaysuntold.worldgen.head.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks state for rail siding placement on a track expansion head.
 * Sidings are placed once per long straight stretch of track.
 */
public class SidingState {

    private int consecutiveStraightBlocks;
    private boolean sidingPlacedThisStretch;
    private Direction sidingSide;

    private final Deque<PendingSiding> pendingRetries = new ArrayDeque<>();

    public void incrementStraight(int blocks) {
        consecutiveStraightBlocks += blocks;
    }

    public void reset() {
        consecutiveStraightBlocks = 0;
        sidingPlacedThisStretch = false;
        sidingSide = null;
        pendingRetries.clear();
    }

    public boolean isEligible(int minBlocks) {
        return consecutiveStraightBlocks >= minBlocks && !sidingPlacedThisStretch;
    }

    public void markPlaced(Direction side) {
        sidingPlacedThisStretch = true;
        sidingSide = side;
    }

    public Direction getSidingSide() {
        return sidingSide;
    }

    public int getConsecutiveStraightBlocks() {
        return consecutiveStraightBlocks;
    }

    public void enqueueRetry(PendingSiding pending) {
        pendingRetries.add(pending);
    }

    public List<PendingSiding> drainRetries() {
        if (pendingRetries.isEmpty()) {
            return List.of();
        }
        List<PendingSiding> drained = new ArrayList<>(pendingRetries);
        pendingRetries.clear();
        return drained;
    }

    public boolean hasPendingRetries() {
        return !pendingRetries.isEmpty();
    }

    /**
     * Parameters needed to retry a siding placement at the same location.
     * {@code attempts} counts attempts already made (the first failure produces a pending entry with attempts=1).
     */
    public record PendingSiding(
            BlockPos sidingOrigin,
            Direction parentDir,
            boolean placeLeft,
            int sidingStraightLength,
            Direction sidingSide,
            int attempts) {

        public PendingSiding withIncrementedAttempts() {
            return new PendingSiding(sidingOrigin, parentDir, placeLeft, sidingStraightLength, sidingSide, attempts + 1);
        }
    }
}
