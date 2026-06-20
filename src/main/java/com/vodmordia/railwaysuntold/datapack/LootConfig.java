package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loot table configuration parsed from a definition's "loot" JSON block.
 *
 * Resolution order for a container:
 *   1. {@code by_tag} - if the container's CustomName matches a tag key
 *   2. {@code by_type} - if the container's block type matches (e.g. "minecraft:chest")
 *   3. {@code defaultTable} - fallback loot table
 *   4. {@code null} - no loot assigned, keep schematic contents
 *
 * @param defaultTable Fallback loot table for any container
 * @param byType       Block registry ID (e.g. "minecraft:chest") to loot table
 * @param byTag        Container CustomName tag to loot table
 */
public record LootConfig(
        @Nullable ResourceLocation defaultTable,
        Map<String, ResourceLocation> byType,
        Map<String, ResourceLocation> byTag
) {
    public static final LootConfig EMPTY = new LootConfig(null, Map.of(), Map.of());

    public boolean isEmpty() {
        return defaultTable == null && byType.isEmpty() && byTag.isEmpty();
    }

    public static LootConfig fromJson(JsonObject json) {
        if (!json.has("loot")) {
            return EMPTY;
        }
        JsonObject lootJson = json.getAsJsonObject("loot");

        ResourceLocation defaultTable = lootJson.has("default")
                ? ResourceLocation.parse(lootJson.get("default").getAsString())
                : null;

        Map<String, ResourceLocation> byType = parseStringMap(lootJson, "by_type");
        Map<String, ResourceLocation> byTag = parseStringMap(lootJson, "by_tag");

        if (defaultTable == null && byType.isEmpty() && byTag.isEmpty()) {
            return EMPTY;
        }

        return new LootConfig(defaultTable, byType, byTag);
    }

    private static Map<String, ResourceLocation> parseStringMap(JsonObject parent, String key) {
        if (!parent.has(key)) {
            return Collections.emptyMap();
        }
        JsonObject obj = parent.getAsJsonObject(key);
        Map<String, ResourceLocation> map = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            map.put(entry.getKey(), ResourceLocation.parse(entry.getValue().getAsString()));
        }
        return Collections.unmodifiableMap(map);
    }
}
