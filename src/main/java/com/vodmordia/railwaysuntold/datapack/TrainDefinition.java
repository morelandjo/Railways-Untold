package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Data-pack-driven train definition parsed from JSON.
 *
 * @param id             The resource location ID of this definition (derived from JSON file path)
 * @param schematic      ResourceLocation of the NBT schematic
 * @param frontFacing    Direction override: "auto", "north", "south", "east", "west"
 * @param weight         Relative weight for weighted random selection
 * @param biomeWhitelist If non-empty, train only selected in these biomes/tags
 * @param biomeBlacklist Train is excluded from these biomes/tags
 */
public record TrainDefinition(
        ResourceLocation id,
        ResourceLocation schematic,
        String frontFacing,
        int weight,
        List<BiomeFilter> biomeWhitelist,
        List<BiomeFilter> biomeBlacklist,
        LootConfig loot
) {
    public static final String DEFAULT_FRONT_FACING = "auto";
    public static final int DEFAULT_WEIGHT = 10;
}
