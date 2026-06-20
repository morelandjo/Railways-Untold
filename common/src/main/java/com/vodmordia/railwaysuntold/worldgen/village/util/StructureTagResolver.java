package com.vodmordia.railwaysuntold.worldgen.village.util;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Resolves config structure tag strings into structure sets with placement parameters.
 * Cached per-level and cleared on level unload.
 */
public final class StructureTagResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ServerLevel, List<ResolvedStructureTarget>> cache =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * A resolved structure target with its placement parameters.
     */
    public record ResolvedStructureTarget(
            TagKey<Structure> tag,
            Holder<StructureSet> structureSet,
            int spacing,
            int separation,
            int salt,
            String setName
    ) {}

    private StructureTagResolver() {}

    /**
     * Resolves configured structure tags for the given level.
     * Results are cached per level.
     */
    public static List<ResolvedStructureTarget> resolve(ServerLevel level) {
        return cache.computeIfAbsent(level, StructureTagResolver::resolveForLevel);
    }

    private static List<ResolvedStructureTarget> resolveForLevel(ServerLevel level) {
        List<String> tagStrings = BiomeSettingsLoader.INSTANCE.resolveStructureSettings().targetTags();
        if (tagStrings.isEmpty()) {
            return Collections.emptyList();
        }

        List<ResolvedStructureTarget> results = new ArrayList<>();
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        ChunkGeneratorStructureState genState = level.getChunkSource().getGeneratorState();
        List<Holder<StructureSet>> possibleSets = genState.possibleStructureSets();

        Set<String> processedSets = new HashSet<>();

        for (String tagString : tagStrings) {
            ResourceLocation tagLoc = ResourceLocation.tryParse(tagString);
            if (tagLoc == null) {
                LOGGER.warn("[STRUCTURE-RESOLVER] Invalid tag name: '{}'", tagString);
                continue;
            }

            TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, tagLoc);

            // Find all structures with this tag
            Set<ResourceKey<Structure>> taggedKeys = new HashSet<>();
            structureRegistry.getTag(tag).ifPresent(holderSet ->
                    holderSet.forEach(holder -> holder.unwrapKey().ifPresent(taggedKeys::add))
            );

            if (taggedKeys.isEmpty()) {
                LOGGER.warn("[STRUCTURE-RESOLVER] No structures found for tag '{}'", tagString);
                continue;
            }

            // Find structure sets containing any of these structures
            for (Holder<StructureSet> setHolder : possibleSets) {
                String setName = setHolder.unwrapKey()
                        .map(key -> key.location().toString())
                        .orElse("");

                if (processedSets.contains(setName)) {
                    continue;
                }

                StructureSet set = setHolder.value();
                boolean containsTaggedStructure = set.structures().stream()
                        .anyMatch(entry -> entry.structure().unwrapKey()
                                .map(taggedKeys::contains).orElse(false));

                if (!containsTaggedStructure) {
                    continue;
                }

                if (set.placement() instanceof RandomSpreadStructurePlacement randomSpread) {
                    int salt = getSalt(randomSpread);
                    results.add(new ResolvedStructureTarget(
                            tag, setHolder,
                            randomSpread.spacing(), randomSpread.separation(), salt,
                            setName));
                    processedSets.add(setName);

                } else {
                }
            }
        }

        return Collections.unmodifiableList(results);
    }

    /**
     * Access the protected salt() method from StructurePlacement via reflection.
     */
    private static int getSalt(StructurePlacement placement) {
        try {
            Method saltMethod = StructurePlacement.class.getDeclaredMethod("salt");
            saltMethod.setAccessible(true);
            return (int) saltMethod.invoke(placement);
        } catch (Exception e) {
            LOGGER.warn("[STRUCTURE-RESOLVER] Failed to access salt via reflection: {}", e.getMessage(), e);
            return 0;
        }
    }

    public static void clearCache() {
        cache.clear();
    }

}
