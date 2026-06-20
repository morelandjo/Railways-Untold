package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver.ResolvedStructureTarget;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Pre-computes village layouts by calling Minecraft's jigsaw generation algorithm.
 */
public class VillageLayoutPredictor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ServerLevel, Long2ObjectOpenHashMap<PredictedVillageLayout>> layoutCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Predicts the layout of a village at the given chunk position.
     *
     * @param level    The server level
     * @param chunkPos The chunk position where the village structure start is expected
     * @return The predicted layout, or null if generation fails or no village generates here
     */
    @Nullable
    public static PredictedVillageLayout predict(ServerLevel level, ChunkPos chunkPos) {
        Long2ObjectOpenHashMap<PredictedVillageLayout> cache = layoutCache.computeIfAbsent(
                level, k -> new Long2ObjectOpenHashMap<>());

        long key = chunkPos.toLong();
        PredictedVillageLayout cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        PredictedVillageLayout layout = computeLayout(level, chunkPos);
        if (layout != null) {
            cache.put(key, layout);
        }
        return layout;
    }

    @Nullable
    private static PredictedVillageLayout computeLayout(ServerLevel level, ChunkPos chunkPos) {
        try {
            Holder<StructureSet> villageSetHolder = findTargetStructureSet(level);
            if (villageSetHolder == null) {
                LOGGER.debug("[LAYOUT-PREDICT] No target structure set found");
                return null;
            }

            StructureSet villageSet = villageSetHolder.value();
            List<StructureSet.StructureSelectionEntry> entries = villageSet.structures();

            if (entries.isEmpty()) {
                return null;
            }

            ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
            BiomeSource biomeSource = chunkGenerator.getBiomeSource();
            RandomState randomState = level.getChunkSource().randomState();
            StructureTemplateManager templateManager = level.getStructureManager();
            long seed = level.getSeed();

            // Replicate ChunkGenerator.createStructures() weighted random selection
            // Uses the same RNG seeding as vanilla to get the exact same structure variant
            StructureStart start;
            String selectedName;

            if (entries.size() == 1) {
                // Single structure - try it directly with biome validation
                StructureSet.StructureSelectionEntry entry = entries.get(0);
                Structure structure = entry.structure().value();
                HolderSet<Biome> validBiomes = structure.biomes();

                start = structure.generate(
                        level.registryAccess(), chunkGenerator, biomeSource, randomState,
                        templateManager, seed, chunkPos, 0, level,
                        validBiomes::contains);

                selectedName = getStructureName(entry.structure());
            } else {
                // Multiple structures - weighted random selection matching vanilla algorithm
                ArrayList<StructureSet.StructureSelectionEntry> candidates = new ArrayList<>(entries);
                WorldgenRandom rng = new WorldgenRandom(new LegacyRandomSource(0L));
                rng.setLargeFeatureSeed(seed, chunkPos.x, chunkPos.z);

                int totalWeight = 0;
                for (StructureSet.StructureSelectionEntry entry : candidates) {
                    totalWeight += entry.weight();
                }

                start = null;
                selectedName = "unknown";

                while (!candidates.isEmpty()) {
                    int roll = rng.nextInt(totalWeight);
                    int idx = 0;

                    for (StructureSet.StructureSelectionEntry entry : candidates) {
                        roll -= entry.weight();
                        if (roll < 0) {
                            break;
                        }
                        idx++;
                    }

                    // Clamp index (safety)
                    idx = Math.min(idx, candidates.size() - 1);

                    StructureSet.StructureSelectionEntry selected = candidates.get(idx);
                    Structure structure = selected.structure().value();
                    HolderSet<Biome> validBiomes = structure.biomes();

                    start = structure.generate(
                            level.registryAccess(), chunkGenerator, biomeSource, randomState,
                            templateManager, seed, chunkPos, 0, level,
                            validBiomes::contains);

                    selectedName = getStructureName(selected.structure());

                    if (start != null && start.isValid()) {
                        break;  // Found a valid structure, just like vanilla
                    }

                    // Remove failed entry and reduce weight, try next
                    totalWeight -= selected.weight();
                    candidates.remove(idx);
                    start = null;
                }
            }

            if (start == null || !start.isValid()) {
                LOGGER.debug("[LAYOUT-PREDICT] No village variant matched biome at chunk {}", chunkPos);
                return null;
            }

            BoundingBox totalBounds = start.getBoundingBox();
            List<BoundingBox> pieceBounds = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                pieceBounds.add(piece.getBoundingBox());
            }

            PredictedVillageLayout layout = PredictedVillageLayout.create(chunkPos, totalBounds, pieceBounds);


            return layout;
        } catch (Exception e) {
            LOGGER.warn("[LAYOUT-PREDICT] Failed to predict village layout at chunk {}: {}", chunkPos, e.getMessage());
            return null;
        }
    }

    /**
     * Finds a target StructureSet from the resolved structure targets.
     * Returns the first resolved target's structure set, or tries all possible sets.
     */
    @Nullable
    private static Holder<StructureSet> findTargetStructureSet(ServerLevel level) {
        List<ResolvedStructureTarget> targets = StructureTagResolver.resolve(level);
        if (!targets.isEmpty()) {
            return targets.get(0).structureSet();
        }
        return null;
    }

    private static String getStructureName(Holder<Structure> holder) {
        return holder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
    }

    /**
     * Clears all cached layouts. Called on level unload.
     */
    public static void clearCache() {
        layoutCache.clear();
    }

}
