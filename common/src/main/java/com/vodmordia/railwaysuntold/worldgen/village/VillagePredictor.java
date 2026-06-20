package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.village.util.BiomeTypeExtractor;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver.ResolvedStructureTarget;
import com.vodmordia.railwaysuntold.worldgen.village.util.VillageConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Predicts structure locations using structure placement algorithms.
 */
public class VillagePredictor {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Information about a predicted structure location.
     */
    public static class PredictedVillage {
        public final ChunkPos chunkPos;
        public final BlockPos approximateCenter;
        public final String biomeType;
        public final double confidence;
        public final String structureSetName;
        public final int spacing;

        public PredictedVillage(ChunkPos chunkPos, BlockPos approximateCenter, String biomeType,
                                double confidence, String structureSetName, int spacing) {
            this.chunkPos = chunkPos;
            this.approximateCenter = approximateCenter;
            this.biomeType = biomeType;
            this.confidence = confidence;
            this.structureSetName = structureSetName;
            this.spacing = spacing;
        }

        @Override
        public String toString() {
            return String.format("PredictedVillage{chunk=%s, pos=%s, biome=%s, confidence=%.2f, set=%s}",
                chunkPos, approximateCenter, biomeType, confidence, structureSetName);
        }
    }

    /**
     * Predict structure locations within a radius using placement math for all resolved targets.
     */
    public static List<PredictedVillage> predictVillagesInRadius(ServerLevel level, BlockPos center, int radius) {
        List<PredictedVillage> predictions = new ArrayList<>();

        try {
            long worldSeed = level.getSeed();
            List<ResolvedStructureTarget> targets = StructureTagResolver.resolve(level);

            for (ResolvedStructureTarget target : targets) {
                int spacing = target.spacing();
                int separation = target.separation();
                int salt = target.salt();
                String setName = target.setName();

                int regionRadius = (radius / (spacing * 16)) + 2;
                ChunkPos centerChunk = new ChunkPos(center);
                int centerRegionX = Math.floorDiv(centerChunk.x, spacing);
                int centerRegionZ = Math.floorDiv(centerChunk.z, spacing);

                for (int regionX = centerRegionX - regionRadius; regionX <= centerRegionX + regionRadius; regionX++) {
                    for (int regionZ = centerRegionZ - regionRadius; regionZ <= centerRegionZ + regionRadius; regionZ++) {
                        PredictedVillage prediction = predictVillageInRegion(
                                level, worldSeed, regionX, regionZ, spacing, separation, salt, setName);

                        if (prediction != null) {
                            int distance = prediction.approximateCenter.distManhattan(center);
                            if (distance <= radius) {
                                predictions.add(prediction);
                            }
                        }
                    }
                }
            }

        } catch (RuntimeException e) {
            LOGGER.warn("[VILLAGE-PREDICTOR] Failed to predict structures in radius: {}", e.getMessage());
        }

        return predictions;
    }

    /**
     * Predict if there's a structure in a specific region
     */
    private static PredictedVillage predictVillageInRegion(ServerLevel level, long worldSeed,
                                                           int regionX, int regionZ,
                                                           int spacing, int separation, int salt,
                                                           String structureSetName) {
        try {
            long regionSeed = worldSeed + regionX * 341873128712L + regionZ * 132897987541L + salt;
            Random random = new Random(regionSeed);

            int offsetX = random.nextInt(spacing - separation);
            int offsetZ = random.nextInt(spacing - separation);

            int chunkX = regionX * spacing + offsetX;
            int chunkZ = regionZ * spacing + offsetZ;

            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);

            // Use sea level as baseline - this adapts to different world types including superflat
            int baseY = level.getSeaLevel();
            BlockPos approximateCenter = chunkPos.getMiddleBlockPosition(baseY);

            String rawBiomeName = getRawBiomeName(level, approximateCenter);

            if (BiomeTypeExtractor.isOceanBiome(rawBiomeName)) {
                return null;
            }

            String biomeType = BiomeTypeExtractor.extractBiomeType(rawBiomeName);

            // Skip biomes that don't support any village variant (e.g. dark_forest, jungle, swamp)
            if ("unknown".equals(biomeType)) {
                return null;
            }

            // Use flat confidence for all structure predictions
            double confidence = 0.7;

            return new PredictedVillage(chunkPos, approximateCenter, biomeType, confidence,
                    structureSetName, spacing);

        } catch (RuntimeException e) {
            LOGGER.warn("[VILLAGE-PREDICTOR] Failed to predict structure in region ({}, {}): {}", regionX, regionZ, e.getMessage());
        }

        return null;
    }

    /**
     * Get the raw biome name at a position
     */
    private static String getRawBiomeName(ServerLevel level, BlockPos pos) {
        try {
            Holder<Biome> biomeHolder = level.getNoiseBiome(
                pos.getX() >> 2,
                pos.getY() >> 2,
                pos.getZ() >> 2
            );

            return biomeHolder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
        } catch (RuntimeException e) {
            LOGGER.warn("[VILLAGE-PREDICTOR] Failed to get biome name at {}: {}", pos, e.getMessage());
            return "unknown";
        }
    }

    /**
     * Strictness tiers for branch village targeting, from tightest to loosest.
     * Prefers villages more directly in the branch direction, falling back to wider cones.
     * 1.5 = ~34° cone, 1.25 = ~39° cone, 1.0 = 45° cone.
     */
    private static final double[] BRANCH_DIRECTION_STRICTNESS_TIERS = {1.5, 1.25, 1.0};

    /**
     * Find the nearest predicted structure in a specific direction, excluding already-assigned and attempted.
     * Uses tiered strictness: first searches a tight cone (~34°), then progressively widens
     * to ~39° and finally 45°.
     */
    public static PredictedVillage findNearestPredictedVillageInDirection(
            ServerLevel level,
            BlockPos center,
            int maxRadius,
            @Nullable Direction branchDirection,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker) {
        return findNearestPredictedVillageInDirection(
                level, center, maxRadius, branchDirection, assignmentTracker, attemptedTracker, null);
    }

    /**
     * Variant accepting an optional pre-filter on the candidate's approximate center.
     */
    public static PredictedVillage findNearestPredictedVillageInDirection(
            ServerLevel level,
            BlockPos center,
            int maxRadius,
            @Nullable Direction branchDirection,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            @Nullable Predicate<BlockPos> centerFilter) {

        List<PredictedVillage> predictions = predictVillagesInRadius(level, center, maxRadius);

        if (branchDirection == null) {
            return findNearestFromPredictions(
                    level, predictions, center, assignmentTracker, attemptedTracker, p -> true, centerFilter);
        }

        for (double strictness : BRANCH_DIRECTION_STRICTNESS_TIERS) {
            PredictedVillage result = findNearestFromPredictions(
                    level, predictions, center, assignmentTracker, attemptedTracker,
                    p -> DirectionUtil.isDominantInDirection(center, p.approximateCenter, branchDirection, strictness),
                    centerFilter);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Find the best predicted structure that is reachable from the head's position.
     */
    public static PredictedVillage findNearestPredictedVillageNotBehind(
            ServerLevel level,
            BlockPos center,
            int maxRadius,
            @Nullable Direction headDirection,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            @Nullable Predicate<BlockPos> centerFilter) {
        return findBestScoredPrediction(
                level, center, maxRadius, headDirection, assignmentTracker, attemptedTracker, centerFilter);
    }

    /**
     * Finds the best village using weighted scoring (distance + alignment). Eligible
     * predictions are ranked by score, then confirmed against VillageLayoutPredictor
     * (which runs Structure.generate() on real biome/jigsaw state - no chunk loading,
     * layout cache reuses results across calls). The first prediction that confirms
     * wins.
     */
    private static PredictedVillage findBestScoredPrediction(
            ServerLevel level, BlockPos center, int maxRadius,
            @Nullable Direction headDirection,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            @Nullable Predicate<BlockPos> centerFilter) {
        List<PredictedVillage> predictions = predictVillagesInRadius(level, center, maxRadius);

        List<ScoredPrediction> scored = new ArrayList<>();

        for (PredictedVillage prediction : predictions) {
            VillageInfo villageInfo = createVillageInfoFromPrediction(prediction);
            VillageConfig.SkipReason skipReason = VillageConfig.shouldSkipVillage(
                    villageInfo.villageId, assignmentTracker, attemptedTracker);
            if (skipReason != VillageConfig.SkipReason.NOT_SKIPPED) {
                continue;
            }

            if (centerFilter != null && !centerFilter.test(prediction.approximateCenter)) {
                continue;
            }

            // Filter: reject villages behind the head (>120° off-axis)
            if (headDirection != null) {
                double angle = DirectionUtil.angleBetween(center, prediction.approximateCenter, headDirection);
                if (angle > 120.0) continue;
            }

            int distance = prediction.approximateCenter.distManhattan(center);
            if (distance <= 0) continue;

            // Score: (1/distance) * alignmentBonus
            double alignmentBonus = 1.0;
            if (headDirection != null) {
                double angle = DirectionUtil.angleBetween(center, prediction.approximateCenter, headDirection);
                // cos(angle) gives 1.0 for directly ahead, 0.0 for perpendicular, -1.0 for behind
                alignmentBonus = Math.max(0.3, Math.cos(Math.toRadians(angle)));
            }
            double score = (1.0 / distance) * alignmentBonus;
            scored.add(new ScoredPrediction(prediction, score));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        for (ScoredPrediction entry : scored) {
            if (confirmPrediction(level, entry.prediction)) {
                return entry.prediction;
            }
        }
        return null;
    }

    private record ScoredPrediction(PredictedVillage prediction, double score) {}

    /**
     * Finds the nearest predicted structure from a pre-computed list that passes the filter,
     * confirming each in distance order via VillageLayoutPredictor before returning. The
     * first confirmed prediction wins
     */
    private static PredictedVillage findNearestFromPredictions(
            ServerLevel level,
            List<PredictedVillage> predictions,
            BlockPos center,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            java.util.function.Predicate<PredictedVillage> filter,
            @Nullable Predicate<BlockPos> centerFilter) {

        List<PredictedVillage> eligible = new ArrayList<>();
        for (PredictedVillage prediction : predictions) {
            if (!filter.test(prediction)) {
                continue;
            }

            VillageInfo villageInfo = createVillageInfoFromPrediction(prediction);
            VillageConfig.SkipReason skipReason = VillageConfig.shouldSkipVillage(
                    villageInfo.villageId, assignmentTracker, attemptedTracker);
            if (skipReason != VillageConfig.SkipReason.NOT_SKIPPED) {
                continue;
            }

            if (centerFilter != null && !centerFilter.test(prediction.approximateCenter)) {
                continue;
            }

            eligible.add(prediction);
        }

        eligible.sort(Comparator.comparingInt(p -> p.approximateCenter.distManhattan(center)));

        for (PredictedVillage prediction : eligible) {
            if (confirmPrediction(level, prediction)) {
                return prediction;
            }
        }
        return null;
    }

    /**
     * Confirms that a seed-math prediction corresponds to a village that will actually
     * generate.
     */
    private static boolean confirmPrediction(ServerLevel level, PredictedVillage prediction) {
        try {
            return VillageLayoutPredictor.predict(level, prediction.chunkPos) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Convert a predicted structure into VillageInfo format.
     */
    public static VillageInfo createVillageInfoFromPrediction(PredictedVillage predicted) {
        return new VillageInfo(predicted.approximateCenter, predicted.biomeType,
                predicted.spacing, predicted.structureSetName);
    }
}
