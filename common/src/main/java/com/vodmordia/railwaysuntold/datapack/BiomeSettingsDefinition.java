package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.Optional;

/**
 * A biome settings definition loaded from a data pack at
 * {@code data/<namespace>/railwaysuntold/biome_settings/<name>.json}.
 *
 * All fields except {@code biomeFilter} are optional. Omitted fields inherit
 * from lower-priority matching entries during resolution.
 */
public record BiomeSettingsDefinition(
        ResourceLocation id,
        List<BiomeFilter> biomeFilter,
        int priority,
        Optional<String> tunnelFacadeBlock,
        Optional<String> terrainFillBlock,
        Optional<String> terrainFillBaseBlock,
        Optional<String> stationFoundationBlock,
        Optional<String> lightingBlock,
        Optional<Integer> lightingAttachment,
        Optional<String> trackMaterial,
        Optional<List<String>> structureTargetTags,
        Optional<List<String>> structureAvoidanceBlacklist,
        Optional<List<String>> structureUndergroundList,
        Optional<String> bridgeDeckingNbt,
        Optional<String> bridgeEndNbt,
        Optional<Integer> bridgeHalfWidth,
        Optional<String> bridgeAbutmentNbt,
        Optional<String> bridgePierNbt,
        Optional<Integer> bridgePillarDeckRow,
        Optional<String> bridgeRailingNbt
) {
    public static final int DEFAULT_PRIORITY = 10;

    /**
     * Returns true if this definition matches the given biome.
     * An empty filter matches all biomes.
     */
    public boolean matchesBiome(Holder<Biome> biome) {
        if (biomeFilter.isEmpty()) return true;
        for (BiomeFilter f : biomeFilter) {
            if (f.matches(biome)) return true;
        }
        return false;
    }

    /**
     * Specificity score for tie-breaking: direct biome IDs score higher than tags.
     */
    public int specificityScore() {
        int score = 0;
        for (BiomeFilter f : biomeFilter) {
            if (f instanceof BiomeFilter.Direct) score += 2;
            else if (f instanceof BiomeFilter.Tag) score += 1;
        }
        return score;
    }
}
