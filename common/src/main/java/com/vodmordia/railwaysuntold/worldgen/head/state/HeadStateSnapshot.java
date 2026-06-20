package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.util.chunk.ChunkBounds;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import com.vodmordia.railwaysuntold.worldgen.village.VillageEdgeFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable snapshot of a TrackExpansionHead's complete state for persistence.
 */
public record HeadStateSnapshot(
        BlockPos position,
        Direction direction,
        boolean complete,
        boolean paused,
        long pausedAtTime,
        int blocksSinceLastCurve,
        int previousCurveDirection,
        int priorCurveDirection,
        boolean lastPlacementWasEmergencyCurve,
        int blocksSinceLastEvent,
        int blocksSinceLastBranch,
        String diagonalHeading,
        List<UUID> childBranchIds,
        int torchPlacementOffset,
        VillageTargetingSnapshot villageTargeting
) {
    public HeadStateSnapshot {
        childBranchIds = childBranchIds != null ? new ArrayList<>(childBranchIds) : new ArrayList<>();
    }

    /**
     * Immutable snapshot of village targeting state for persistence.
     */
    public record VillageTargetingSnapshot(
            UUID targetVillageId,
            BlockPos targetVillageCenter,
            int initialDistanceToVillage,
            boolean hasReachedVillage,
            boolean villageConfirmed,
            boolean runwayConfirmed,
            boolean stationPlaced,
            VillageEdgeFinder.StationZone runway,
            int villageDeferCount,
            int[] layoutTotalBounds,
            int[] layoutChunkBounds,
            BlockPos approachWaypoint,
            BlockPos explorationTarget,
            int[] lockedStationPlanData
    ) {
        /**
         * Reconstructs a PredictedVillageLayout from the persisted bounds, or null if not available.
         */
        @Nullable
        public PredictedVillageLayout toLayout() {
            if (layoutTotalBounds == null || layoutTotalBounds.length != 6 ||
                    layoutChunkBounds == null || layoutChunkBounds.length != 4) {
                return null;
            }
            BoundingBox totalBounds = new BoundingBox(
                    layoutTotalBounds[0], layoutTotalBounds[1], layoutTotalBounds[2],
                    layoutTotalBounds[3], layoutTotalBounds[4], layoutTotalBounds[5]);
            ChunkBounds chunkBounds = new ChunkBounds(
                    layoutChunkBounds[0], layoutChunkBounds[1], layoutChunkBounds[2], layoutChunkBounds[3]);
            BlockPos center = new BlockPos(
                    (totalBounds.minX() + totalBounds.maxX()) / 2,
                    (totalBounds.minY() + totalBounds.maxY()) / 2,
                    (totalBounds.minZ() + totalBounds.maxZ()) / 2);
            return new PredictedVillageLayout(
                    new net.minecraft.world.level.ChunkPos(center),
                    totalBounds, chunkBounds, center, java.util.List.of());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BlockPos position;
        private Direction direction;
        private boolean complete;
        private boolean paused;
        private long pausedAtTime;
        private int blocksSinceLastCurve;
        private int previousCurveDirection;
        private int priorCurveDirection;
        private boolean lastPlacementWasEmergencyCurve;
        private int blocksSinceLastEvent;
        private int blocksSinceLastBranch;
        private String diagonalHeading;
        private List<UUID> childBranchIds = new ArrayList<>();
        private int torchPlacementOffset;
        private VillageTargetingSnapshot villageTargeting;

        public Builder position(BlockPos position) {
            this.position = position;
            return this;
        }

        public Builder direction(Direction direction) {
            this.direction = direction;
            return this;
        }

        public Builder complete(boolean complete) {
            this.complete = complete;
            return this;
        }

        public Builder paused(boolean paused) {
            this.paused = paused;
            return this;
        }

        public Builder pausedAtTime(long pausedAtTime) {
            this.pausedAtTime = pausedAtTime;
            return this;
        }

        public Builder blocksSinceLastCurve(int blocks) {
            this.blocksSinceLastCurve = blocks;
            return this;
        }

        public Builder previousCurveDirection(int direction) {
            this.previousCurveDirection = direction;
            return this;
        }

        public Builder priorCurveDirection(int direction) {
            this.priorCurveDirection = direction;
            return this;
        }

        public Builder lastPlacementWasEmergencyCurve(boolean wasEmergency) {
            this.lastPlacementWasEmergencyCurve = wasEmergency;
            return this;
        }

        public Builder blocksSinceLastEvent(int blocks) {
            this.blocksSinceLastEvent = blocks;
            return this;
        }

        public Builder blocksSinceLastBranch(int blocks) {
            this.blocksSinceLastBranch = blocks;
            return this;
        }

        public Builder diagonalHeading(String diagonalHeading) {
            this.diagonalHeading = diagonalHeading;
            return this;
        }

        public Builder childBranchIds(List<UUID> childBranchIds) {
            this.childBranchIds = childBranchIds != null ? new ArrayList<>(childBranchIds) : new ArrayList<>();
            return this;
        }

        public Builder torchPlacementOffset(int offset) {
            this.torchPlacementOffset = offset;
            return this;
        }

        public Builder villageTargeting(VillageTargetingSnapshot villageTargeting) {
            this.villageTargeting = villageTargeting;
            return this;
        }

        public HeadStateSnapshot build() {
            return new HeadStateSnapshot(
                    position, direction, complete, paused, pausedAtTime,
                    blocksSinceLastCurve, previousCurveDirection, priorCurveDirection,
                    lastPlacementWasEmergencyCurve, blocksSinceLastEvent, blocksSinceLastBranch,
                    diagonalHeading, childBranchIds,
                    torchPlacementOffset, villageTargeting
            );
        }
    }
}
