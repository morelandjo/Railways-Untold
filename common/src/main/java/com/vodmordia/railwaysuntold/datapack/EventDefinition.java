package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Data-pack-driven event definition parsed from JSON.
 * Supports two event types: NBT (single schematic) and JIGSAW (procedural structure).
 *
 * @param id                The resource location ID of this definition (derived from JSON file path)
 * @param eventType         NBT for single schematic, JIGSAW for procedural jigsaw structure
 * @param schematic         ResourceLocation of the NBT schematic (NBT type only)
 * @param structure         ResourceLocation of the worldgen structure (JIGSAW type only)
 * @param weight            Relative weight for weighted random selection (higher = more likely)
 * @param biomeWhitelist    If non-empty, event only spawns in these biomes/tags
 * @param biomeBlacklist    Event is excluded from these biomes/tags
 * @param estimatedFootprint Estimated footprint length for ground-level checks (JIGSAW type only)
 */
public record EventDefinition(
        ResourceLocation id,
        EventType eventType,
        @Nullable ResourceLocation schematic,
        @Nullable ResourceLocation structure,
        int weight,
        List<BiomeFilter> biomeWhitelist,
        List<BiomeFilter> biomeBlacklist,
        LootConfig loot,
        List<EventTrigger> triggers,
        int maxAppearances,
        int estimatedFootprint
) {
    public static final int DEFAULT_WEIGHT = 10;
    public static final int UNLIMITED = -1;
    public static final int DEFAULT_JIGSAW_FOOTPRINT = 48;

    public enum EventType { NBT, JIGSAW }
}
