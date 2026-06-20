package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Applies loot tables to container block entities during schematic placement.
 *
 */
public class SchematicLootApplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LOOT_TABLE_TAG = "LootTable";
    private static final String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    /**
     * Modifies block entity NBT to apply loot table configuration.
     * Should be called on the NBT before {@code blockEntity.loadWithComponents()}.
     *
     * If a loot table is resolved, the method:
     *   - Sets {@code LootTable} and {@code LootTableSeed} tags
     *   - Removes existing {@code Items} to prevent conflicts with loot generation
     *   - Strips {@code CustomName} if it was used as a tag match
     *
     * @param blockEntityNbt The block entity NBT to modify (will be mutated)
     * @param config         Loot configuration from the event/station/train definition
     * @param blockTypeId    Registry ID of the block (e.g. "minecraft:chest")
     * @param seed           Seed for loot generation (typically derived from world position)
     * @return true if a loot table was applied, false if no match was found
     */
    public static boolean applyLoot(CompoundTag blockEntityNbt,
                                    LootConfig config,
                                    String blockTypeId,
                                    long seed) {
        String customName = extractCustomName(blockEntityNbt);

        ResourceLocation lootTableId = resolveLootTable(config, blockTypeId, customName);
        if (lootTableId == null) {
            return false;
        }

        // Set loot table tags that RandomizableContainer reads via tryLoadLootTable
        blockEntityNbt.putString(LOOT_TABLE_TAG, lootTableId.toString());
        blockEntityNbt.putLong(LOOT_TABLE_SEED_TAG, seed);

        // Clear existing items so they don't conflict with loot generation
        blockEntityNbt.remove("Items");

        // Strip CustomName if it was a tag match (so players don't see the tag name)
        if (customName != null && config.byTag().containsKey(customName)) {
            blockEntityNbt.remove("CustomName");
        }

        return true;
    }

    /**
     * Resolves which loot table to apply based on the config, block type, and optional tag name.
     *
     * Resolution order: by_tag -> by_type -> default -> null
     */
    @Nullable
    static ResourceLocation resolveLootTable(LootConfig config,
                                             String blockTypeId,
                                             @Nullable String customName) {
        // 1. Check by_tag (most specific)
        if (customName != null && config.byTag().containsKey(customName)) {
            return config.byTag().get(customName);
        }

        // 2. Check by_type
        if (config.byType().containsKey(blockTypeId)) {
            return config.byType().get(blockTypeId);
        }

        // 3. Fallback to default
        return config.defaultTable();
    }

    /**
     * Extracts the plain text content from a CustomName JSON text component stored in NBT.
     * CustomName is stored as a JSON string like {@code '{"text":"rare_loot"}'}.
     *
     * @return The plain text name, or null if no CustomName is present
     */
    @Nullable
    static String extractCustomName(CompoundTag nbt) {
        if (!nbt.contains("CustomName")) {
            return null;
        }
        String jsonStr = nbt.getString("CustomName");
        if (jsonStr.isEmpty()) {
            return null;
        }
        try {
            var jsonElement = JsonParser.parseString(jsonStr);
            if (jsonElement.isJsonObject()) {
                var obj = jsonElement.getAsJsonObject();
                if (obj.has("text")) {
                    String text = obj.get("text").getAsString();
                    return text.isEmpty() ? null : text;
                }
            }
            if (jsonElement.isJsonPrimitive()) {
                String text = jsonElement.getAsString();
                return text.isEmpty() ? null : text;
            }
        } catch (Exception e) {
            LOGGER.warn("[LOOT] Failed to parse CustomName '{}': {}", jsonStr, e.getMessage(), e);
        }
        return null;
    }
}
