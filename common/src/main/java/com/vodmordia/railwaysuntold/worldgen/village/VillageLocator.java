package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.village.util.BiomeTypeExtractor;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver.ResolvedStructureTarget;
import com.vodmordia.railwaysuntold.worldgen.village.util.VillageConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Locates targeted structures in the world using the StructureManager API.
 */
public class VillageLocator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MAX_CACHE_SIZE_PER_LEVEL = 500;

    private static final Map<ServerLevel, Map<UUID, VillageInfo>> villageCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Map<UUID, VillageInfo> createLRUCache() {
        return Collections.synchronizedMap(new LinkedHashMap<UUID, VillageInfo>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, VillageInfo> eldest) {
                return size() > MAX_CACHE_SIZE_PER_LEVEL;
            }
        });
    }

    public static List<VillageInfo> findVillagesInRadius(ServerLevel level, BlockPos center, int radius) {
        List<VillageInfo> villages = new ArrayList<>();

        Map<UUID, VillageInfo> cache = villageCache.computeIfAbsent(level, k -> createLRUCache());

        int chunkRadius = (radius / 16) + 1;
        ChunkPos centerChunk = new ChunkPos(center);

        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                ChunkPos searchChunk = new ChunkPos(centerChunk.x + x, centerChunk.z + z);

                BlockPos chunkCenter = searchChunk.getMiddleBlockPosition(center.getY());
                if (chunkCenter.distManhattan(center) > radius) {
                    continue;
                }

                List<VillageInfo> chunkVillages = findVillagesInChunk(level, searchChunk);

                for (VillageInfo village : chunkVillages) {
                    VillageInfo existing = cache.get(village.villageId);
                    if (existing == null || "unknown".equals(existing.villageType)) {
                        cache.put(village.villageId, village);
                    }

                    if (village.center.distManhattan(center) <= radius) {
                        villages.add(village);
                    }
                }
            }
        }

        return villages;
    }

    private static List<VillageInfo> findVillagesInChunk(ServerLevel level, ChunkPos chunk) {
        List<VillageInfo> villages = new ArrayList<>();

        try {
            if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) == null) {
                return villages;
            }

            List<ResolvedStructureTarget> targets = StructureTagResolver.resolve(level);
            List<TagKey<Structure>> targetTags = new ArrayList<>();
            for (ResolvedStructureTarget target : targets) {
                targetTags.add(target.tag());
            }

            level.structureManager().startsForStructure(chunk, structure ->
                    matchesTargetTags(structure, level, targetTags)
            ).forEach(structureStart -> {
                if (structureStart.isValid()) {
                    ResolvedStructureTarget matchedTarget = findMatchingTarget(
                            structureStart, level, targets);
                    VillageInfo village = createVillageInfo(structureStart, matchedTarget, level);
                    if (village != null) {
                        villages.add(village);
                    }
                }
            });
        } catch (IllegalStateException e) {
        } catch (NullPointerException e) {
        }

        return villages;
    }

    /**
     * Returns the ground-truth bounding boxes of every village with a structure start in a loaded
     * chunk within the radius, read straight from chunk structure data - no seed math or biome
     * prediction (which false-negatives villages whose origin-chunk center samples a non-village
     * biome). Every start found in the scanned chunks is returned (deduped); the caller decides
     * which boxes actually sit across the starting corridor. Used by the starting-station origin
     * guard, which runs while the spawn chunks are loaded.
     */
    public static List<BoundingBox> placedVillageFootprintsInRadius(ServerLevel level, BlockPos center, int radius) {
        List<BoundingBox> footprints = new ArrayList<>();

        List<ResolvedStructureTarget> targets = StructureTagResolver.resolve(level);
        List<TagKey<Structure>> tags = new ArrayList<>();
        for (ResolvedStructureTarget target : targets) {
            tags.add(target.tag());
        }
        if (tags.isEmpty()) {
            return footprints;
        }

        int chunkRadius = (radius / 16) + 1;
        ChunkPos centerChunk = new ChunkPos(center);
        Set<String> seen = new HashSet<>();

        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                ChunkPos chunk = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) == null) {
                    continue;
                }
                try {
                    level.structureManager().startsForStructure(chunk, s -> matchesTargetTags(s, level, tags))
                            .forEach(start -> {
                                if (!start.isValid()) {
                                    return;
                                }
                                BoundingBox bb = start.getBoundingBox();
                                // The same start is referenced by every chunk it touches - dedupe by origin.
                                if (seen.add(bb.minX() + ":" + bb.minZ())) {
                                    footprints.add(bb);
                                }
                            });
                } catch (IllegalStateException | NullPointerException e) {
                    // Structure data unavailable for this chunk - skip it.
                }
            }
        }
        return footprints;
    }

    /**
     * Reads the placed village structure(s) in already-loaded region chunks and returns the layout of
     * the start nearest {@code near}. Reads ground truth from the structure manager - exact total bounds
     * and per-piece boxes - the same way {@code findVillagesInChunk} does, deduping the start that every
     * touched chunk references. Returns empty if no target-tagged start is present in the loaded chunks.
     */
    public static Optional<PredictedVillageLayout> readPlacedLayout(
            ServerLevel level, Set<ChunkPos> chunks, BlockPos near) {
        List<ResolvedStructureTarget> targets = StructureTagResolver.resolve(level);
        List<TagKey<Structure>> tags = new ArrayList<>();
        for (ResolvedStructureTarget target : targets) {
            tags.add(target.tag());
        }
        if (tags.isEmpty()) {
            return Optional.empty();
        }

        List<StructureStart> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChunkPos chunk : chunks) {
            if (ChunkCoordinateUtil.getLoadedChunk(level, chunk) == null) {
                continue;
            }
            try {
                level.structureManager().startsForStructure(chunk, s -> matchesTargetTags(s, level, tags))
                        .forEach(start -> {
                            if (!start.isValid()) {
                                return;
                            }
                            BoundingBox bb = start.getBoundingBox();
                            // The same start is referenced by every chunk it touches - dedupe by origin.
                            if (seen.add(bb.minX() + ":" + bb.minZ())) {
                                found.add(start);
                            }
                        });
            } catch (IllegalStateException | NullPointerException e) {
                // Structure data unavailable for this chunk - skip it.
            }
        }
        if (found.isEmpty()) {
            return Optional.empty();
        }

        StructureStart best = null;
        long bestDistSq = Long.MAX_VALUE;
        for (StructureStart start : found) {
            BoundingBox bb = start.getBoundingBox();
            long dx = (bb.minX() + bb.maxX()) / 2L - near.getX();
            long dz = (bb.minZ() + bb.maxZ()) / 2L - near.getZ();
            long distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = start;
            }
        }

        BoundingBox total = best.getBoundingBox();
        List<BoundingBox> pieceBounds = new ArrayList<>();
        for (StructurePiece piece : best.getPieces()) {
            pieceBounds.add(piece.getBoundingBox());
        }
        ChunkPos structureChunk = new ChunkPos(
                ((total.minX() + total.maxX()) / 2) >> 4,
                ((total.minZ() + total.maxZ()) / 2) >> 4);
        return Optional.of(PredictedVillageLayout.create(structureChunk, total, pieceBounds));
    }

    /**
     * Check if a structure matches any of the configured target tags.
     */
    private static boolean matchesTargetTags(Structure structure, ServerLevel level,
                                              List<TagKey<Structure>> targetTags) {
        if (structure == null || level == null || targetTags.isEmpty()) {
            return false;
        }

        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        return registry.getResourceKey(structure)
                .flatMap(registry::getHolder)
                .map(holder -> targetTags.stream().anyMatch(holder::is))
                .orElse(false);
    }

    /**
     * Find which resolved target matches a StructureStart's structure.
     */
    @Nullable
    private static ResolvedStructureTarget findMatchingTarget(
            StructureStart start, ServerLevel level,
            List<ResolvedStructureTarget> targets) {
        try {
            Structure structure = start.getStructure();
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var holderOpt = registry.getResourceKey(structure).flatMap(registry::getHolder);
            if (holderOpt.isEmpty()) {
                return null;
            }
            var holder = holderOpt.get();
            for (ResolvedStructureTarget target : targets) {
                if (holder.is(target.tag())) {
                    return target;
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[VILLAGE-LOCATOR] Failed to match structure to target: {}", e.getMessage());
        }
        return null;
    }

    private static VillageInfo createVillageInfo(StructureStart start,
                                                  @Nullable ResolvedStructureTarget matchedTarget,
                                                  ServerLevel level) {
        try {
            BoundingBox bounds = start.getBoundingBox();

            int centerX = (bounds.minX() + bounds.maxX()) / 2;
            int centerY = (bounds.minY() + bounds.maxY()) / 2;
            int centerZ = (bounds.minZ() + bounds.maxZ()) / 2;
            BlockPos center = new BlockPos(centerX, centerY, centerZ);

            String villageType = extractVillageType(start, level);

            if (matchedTarget != null) {
                return new VillageInfo(center, villageType, matchedTarget.spacing(), matchedTarget.setName());
            }
            return new VillageInfo(center, villageType);
        } catch (RuntimeException e) {
            LOGGER.warn("[VILLAGE-LOCATOR] Failed to create VillageInfo from StructureStart: {}", e.getMessage());
            return null;
        }
    }

    private static String extractVillageType(StructureStart start, ServerLevel level) {
        // Use the structure's registry key (e.g., "minecraft:village_plains") which
        // reliably contains biome keywords.
        try {
            var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
            var key = registry.getKey(start.getStructure());
            if (key != null) {
                String extracted = BiomeTypeExtractor.extractBiomeType(key.toString());
                if (!"unknown".equals(extracted)) {
                    return extracted;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return BiomeTypeExtractor.extractBiomeType(start.toString());
    }

    /**
     * Find the nearest unassigned and unattempted village to a position.
     */
    public static VillageInfo findNearestUnassignedVillage(
            ServerLevel level,
            BlockPos center,
            int maxRadius,
            VillageAssignmentTracker assignmentTracker,
            AttemptedVillageTracker attemptedTracker,
            RailwaysUntoldConfig config) {

        List<VillageInfo> villages = findVillagesInRadius(level, center, maxRadius);

        VillageInfo nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        for (VillageInfo village : villages) {
            VillageConfig.SkipReason skipReason = VillageConfig.shouldSkipVillage(
                    village.villageId, assignmentTracker, attemptedTracker);
            if (skipReason != VillageConfig.SkipReason.NOT_SKIPPED) {
                continue;
            }

            BlockPos spawn = level.getSharedSpawnPos();
            int spawnDistance = village.center.distManhattan(spawn);
            if (spawnDistance < config.VILLAGE_MIN_DISTANCE_FROM_SPAWN) {
                continue;
            }

            int distance = village.center.distManhattan(center);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = village;
            }
        }

        return nearest;
    }

    public static void cachePredictedVillage(ServerLevel level, VillageInfo village) {
        Map<UUID, VillageInfo> cache = villageCache.computeIfAbsent(level, k -> createLRUCache());
        cache.put(village.villageId, village);
    }

    /**
     * Find a predicted village for branch targeting in a specific direction.
     */
    @Nullable
    public static VillageInfo findPredictedVillageForBranch(
            ServerLevel level,
            BlockPos center,
            int searchRadius,
            @Nullable net.minecraft.core.Direction branchDirection,
            VillageAssignmentTracker tracker) {

        AttemptedVillageTracker attemptedTracker = VillageTargetingSavedData.get(level).getAttemptedTracker();

        VillagePredictor.PredictedVillage predicted = VillagePredictor.findNearestPredictedVillageInDirection(
                level, center, searchRadius, branchDirection, tracker, attemptedTracker);

        if (predicted == null) {
            return null;
        }

        VillageInfo predictedVillage = createFromPrediction(predicted);
        cachePredictedVillage(level, predictedVillage);

        return predictedVillage;
    }

    private static VillageInfo createFromPrediction(VillagePredictor.PredictedVillage predicted) {
        return VillagePredictor.createVillageInfoFromPrediction(predicted);
    }

    /**
     * Clears the entire village cache.
     * Called during server shutdown to prevent stale data.
     */
    public static void clearCache() {
        villageCache.clear();
    }
}
