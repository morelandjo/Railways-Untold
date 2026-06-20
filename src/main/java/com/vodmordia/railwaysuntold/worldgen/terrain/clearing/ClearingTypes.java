package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import com.vodmordia.railwaysuntold.worldgen.village.VillageInfo;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLayoutPredictor;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared types used by both bezier and straight-line clearing pipelines.
 */
public class ClearingTypes {

    /**
     * Finds bounding boxes of villages near the given area (between start and end positions).
     */
    public static List<BoundingBox> findNearbyVillageBounds(ServerLevel level, BlockPos start, BlockPos end) {
        BlockPos midpoint = new BlockPos(
                (start.getX() + end.getX()) / 2,
                (start.getY() + end.getY()) / 2,
                (start.getZ() + end.getZ()) / 2);
        int searchRadius = (int) Math.sqrt(start.distSqr(end)) / 2 + 80;

        List<VillageInfo> nearby = VillageLocator.findVillagesInRadius(level, midpoint, searchRadius);
        if (nearby.isEmpty()) {
            return Collections.emptyList();
        }

        List<BoundingBox> bounds = new ArrayList<>();
        for (VillageInfo village : nearby) {
            ChunkPos villageChunk = new ChunkPos(village.center);
            PredictedVillageLayout layout = VillageLayoutPredictor.predict(level, villageChunk);
            if (layout != null) {
                bounds.addAll(layout.pieceBounds());
            } else {
                int fallbackRadius = 40;
                bounds.add(new BoundingBox(
                        village.center.getX() - fallbackRadius,
                        village.center.getY() - 10,
                        village.center.getZ() - fallbackRadius,
                        village.center.getX() + fallbackRadius,
                        village.center.getY() + 20,
                        village.center.getZ() + fallbackRadius));
            }
        }
        return bounds;
    }

    /**
     * Parameter object for clearing requests.
     */
    public static class ClearingRequest {
        public final BlockPos origin;
        public final RailwaysUntoldConfig config;
        public final BlockPos exclusionMin;
        public final BlockPos exclusionMax;
        public final int torchOffset;
        public final List<BoundingBox> protectedVillageBounds;

        private ClearingRequest(Builder builder) {
            this.origin = builder.origin;
            this.config = builder.config;
            this.exclusionMin = builder.exclusionMin;
            this.exclusionMax = builder.exclusionMax;
            this.torchOffset = builder.torchOffset;
            this.protectedVillageBounds = builder.protectedVillageBounds;
        }

        public static Builder builder(BlockPos origin, RailwaysUntoldConfig config) {
            return new Builder(origin, config);
        }

        public static class Builder {
            private final BlockPos origin;
            private final RailwaysUntoldConfig config;
            private BlockPos exclusionMin = null;
            private BlockPos exclusionMax = null;
            private int torchOffset = 0;
            private List<BoundingBox> protectedVillageBounds = Collections.emptyList();

            private Builder(BlockPos origin, RailwaysUntoldConfig config) {
                this.origin = origin;
                this.config = config;
            }

            /**
             * Sets a bounding box where clearing should be skipped.
             */
            public Builder exclusionBox(BlockPos min, BlockPos max) {
                this.exclusionMin = min;
                this.exclusionMax = max;
                return this;
            }

            /**
             * Sets the torch placement offset for continuous torch spacing.
             */
            public Builder torchOffset(int offset) {
                this.torchOffset = offset;
                return this;
            }

            /**
             * Sets village bounding boxes where clearing should be skipped (village protection).
             */
            public Builder villageBounds(List<BoundingBox> bounds) {
                this.protectedVillageBounds = bounds != null ? bounds : Collections.emptyList();
                return this;
            }

            public ClearingRequest build() {
                return new ClearingRequest(this);
            }
        }
    }

    /**
     * Data holder for segment position and normal vector.
     */
    static class SegmentData {
        final Vec3 position;
        final Vec3 normal;
        final boolean isOriginal;

        SegmentData(Vec3 position, Vec3 normal, boolean isOriginal) {
            this.position = position;
            this.normal = normal;
            this.isOriginal = isOriginal;
        }
    }

    /**
     * Maps of underground and underwater status keyed by block position.
     */
    record TunnelStatusMaps(
            Map<BlockPos, Boolean> underground,
            Map<BlockPos, Boolean> underwater
    ) {}
}
