package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Data-pack-driven station definition parsed from JSON.
 *
 * @param id              The resource location ID of this definition (derived from JSON file path)
 * @param schematic       ResourceLocation of the NBT schematic
 * @param weight          Relative weight for weighted random selection
 * @param foundationBlock Optional per-station foundation block override (e.g. "minecraft:cobblestone")
 * @param biomeWhitelist  If non-empty, station only placed in these biomes/tags
 * @param biomeBlacklist  Station is excluded from these biomes/tags
 */
public record StationDefinition(
        ResourceLocation id,
        ResourceLocation schematic,
        int weight,
        Optional<String> foundationBlock,
        List<BiomeFilter> biomeWhitelist,
        List<BiomeFilter> biomeBlacklist,
        LootConfig loot,
        int maxAppearances
) {
    public static final int DEFAULT_WEIGHT = 10;
    public static final int UNLIMITED = -1;
}
