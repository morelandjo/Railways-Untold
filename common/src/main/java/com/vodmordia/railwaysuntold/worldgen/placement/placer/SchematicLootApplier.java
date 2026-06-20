package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import javax.annotation.Nullable;

public class SchematicLootApplier {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String LOOT_TABLE_TAG = "LootTable";
    private static final String LOOT_TABLE_SEED_TAG = "LootTableSeed";

    public static boolean applyLoot(CompoundTag blockEntityNbt,
                                    LootConfig config,
                                    String blockTypeId,
                                    long seed) {
        String customName = extractCustomName(blockEntityNbt);

        ResourceLocation lootTableId = resolveLootTable(config, blockTypeId, customName);
        if (lootTableId == null) {
            return false;
        }

        blockEntityNbt.putString(LOOT_TABLE_TAG, lootTableId.toString());
        blockEntityNbt.putLong(LOOT_TABLE_SEED_TAG, seed);
        blockEntityNbt.remove("Items");

        if (customName != null && config.byTag().containsKey(customName)) {
            blockEntityNbt.remove("CustomName");
        }

        return true;
    }

    @Nullable
    static ResourceLocation resolveLootTable(LootConfig config,
                                             String blockTypeId,
                                             @Nullable String customName) {
        if (customName != null && config.byTag().containsKey(customName)) {
            return config.byTag().get(customName);
        }
        if (config.byType().containsKey(blockTypeId)) {
            return config.byType().get(blockTypeId);
        }
        return config.defaultTable();
    }

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
