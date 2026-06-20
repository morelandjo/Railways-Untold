package com.vodmordia.railwaysuntold.util.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * Utility methods for common NBT save/load operations with UUIDs.
 */
public final class NbtHelper {

    private NbtHelper() {
    }

    /**
     * Save a map of UUID to UUID as a ListTag.
     *
     * @param map The map to save
     * @param keyName The NBT key name for the first UUID
     * @param valueName The NBT key name for the second UUID
     * @return ListTag containing the serialized map entries
     */
    public static ListTag saveUuidToUuidMap(Map<UUID, UUID> map, String keyName, String valueName) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, UUID> entry : map.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(keyName, entry.getKey());
            entryTag.putUUID(valueName, entry.getValue());
            list.add(entryTag);
        }
        return list;
    }

    /**
     * Load a map of UUID to UUID from a ListTag.
     *
     * @param tag The parent CompoundTag
     * @param listName The name of the ListTag to read
     * @param keyName The NBT key name for the first UUID
     * @param valueName The NBT key name for the second UUID
     * @param consumer Consumer to process each loaded entry (key, value)
     */
    public static void loadUuidToUuidMap(CompoundTag tag, String listName, String keyName, String valueName,
                                         BiConsumer<UUID, UUID> consumer) {
        if (!tag.contains(listName, Tag.TAG_LIST)) {
            return;
        }

        ListTag list = tag.getList(listName, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (entryTag.hasUUID(keyName) && entryTag.hasUUID(valueName)) {
                UUID key = entryTag.getUUID(keyName);
                UUID value = entryTag.getUUID(valueName);
                consumer.accept(key, value);
            }
        }
    }

    /**
     * Save a map of UUID to String as a ListTag.
     *
     * @param map The map to save
     * @param keyName The NBT key name for the UUID
     * @param valueName The NBT key name for the String
     * @return ListTag containing the serialized map entries
     */
    public static ListTag saveUuidToStringMap(Map<UUID, ?> map, String keyName, String valueName) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, ?> entry : map.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(keyName, entry.getKey());
            entryTag.putString(valueName, entry.getValue().toString());
            list.add(entryTag);
        }
        return list;
    }

    /**
     * Load a map of UUID to String from a ListTag.
     *
     * @param tag The parent CompoundTag
     * @param listName The name of the ListTag to read
     * @param keyName The NBT key name for the UUID
     * @param valueName The NBT key name for the String
     * @param consumer Consumer to process each loaded entry (uuid, string)
     */
    public static void loadUuidToStringMap(CompoundTag tag, String listName, String keyName, String valueName,
                                           BiConsumer<UUID, String> consumer) {
        if (!tag.contains(listName, Tag.TAG_LIST)) {
            return;
        }

        ListTag list = tag.getList(listName, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            if (entryTag.hasUUID(keyName) && entryTag.contains(valueName, Tag.TAG_STRING)) {
                UUID key = entryTag.getUUID(keyName);
                String value = entryTag.getString(valueName);
                consumer.accept(key, value);
            }
        }
    }

    // ===== UUID FORMAT CONVERSION METHODS =====

    /**
     * Default field names commonly used for UUIDs
     */
    public static final Set<String> DEFAULT_UUID_FIELDS = new HashSet<>(Arrays.asList(
        "Id", "UUID", "SelectedGraph", "GraphID", "TrackId", "id"
    ));

    /**
     * Recursively converts string UUIDs to int array format
     *
     * @param tag The NBT tag to process (modified in place)
     */
    public static void convertStringUuidsToIntArrays(CompoundTag tag) {
        convertStringUuidsToIntArrays(tag, DEFAULT_UUID_FIELDS);
    }

    /**
     * Recursively converts string UUIDs to int array format
     *
     * @param tag The NBT tag to process (modified in place)
     * @param uuidFieldNames Set of field names to check for UUID conversion
     */
    public static void convertStringUuidsToIntArrays(CompoundTag tag, Set<String> uuidFieldNames) {
        convertKnownUuidFields(tag, uuidFieldNames);
        processNestedTags(tag, uuidFieldNames);
    }

    private static void convertKnownUuidFields(CompoundTag tag, Set<String> uuidFieldNames) {
        for (String field : uuidFieldNames) {
            if (!tag.contains(field)) continue;
            if (!(tag.get(field) instanceof StringTag)) continue;

            String uuidStr = tag.getString(field);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                tag.putUUID(field, uuid);
            } catch (IllegalArgumentException e) {
                // Not a valid UUID string, leave as-is
            }
        }
    }

    private static void processNestedTags(CompoundTag tag, Set<String> uuidFieldNames) {
        for (String key : tag.getAllKeys()) {
            Tag nested = tag.get(key);
            if (nested instanceof CompoundTag nestedCompound) {
                convertStringUuidsToIntArrays(nestedCompound, uuidFieldNames);
            } else if (nested instanceof ListTag list) {
                processListTag(list, uuidFieldNames);
            }
        }
    }

    private static void processListTag(ListTag list, Set<String> uuidFieldNames) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof CompoundTag nestedInList) {
                convertStringUuidsToIntArrays(nestedInList, uuidFieldNames);
            }
        }
    }

    // ===== ITEMSTACK FORMAT NORMALIZATION =====

    /**
     * Known field names that contain ItemStack NBT data.
     */
    private static final Set<String> ITEM_STACK_FIELDS = new HashSet<>(Arrays.asList(
        "Item", "item"
    ));

    /**
     * Recursively normalizes ItemStack tags from 1.20.5+ format to 1.20.1 format.
     * In 1.20.5+, ItemStacks use lowercase "count" (TAG_INT) and "id".
     * In 1.20.1, ItemStack.of() expects uppercase "Count" (TAG_BYTE) and "id".
     *
     * @param tag The NBT tag to process (modified in place)
     */
    public static void normalizeItemStackTags(CompoundTag tag) {
        for (String field : ITEM_STACK_FIELDS) {
            if (tag.contains(field, Tag.TAG_COMPOUND)) {
                normalizeItemStack(tag.getCompound(field));
            }
        }
        // Recurse into nested compounds
        for (String key : tag.getAllKeys()) {
            Tag nested = tag.get(key);
            if (nested instanceof CompoundTag nestedCompound) {
                normalizeItemStackTags(nestedCompound);
            } else if (nested instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i) instanceof CompoundTag nestedInList) {
                        normalizeItemStackTags(nestedInList);
                    }
                }
            }
        }
    }

    /**
     * Normalizes a single ItemStack compound tag from 1.20.5+ format to 1.20.1 format.
     * Converts lowercase "count" (any numeric type) to uppercase "Count" (TAG_BYTE).
     */
    private static void normalizeItemStack(CompoundTag itemTag) {
        // Fix "count" -> "Count" (1.20.5+ uses lowercase, 1.20.1 expects uppercase)
        if (itemTag.contains("count") && !itemTag.contains("Count")) {
            // getInt handles any numeric tag type; cast to byte for 1.20.1 format
            int count = itemTag.getInt("count");
            itemTag.remove("count");
            itemTag.putByte("Count", (byte) Math.max(1, count));
        }
    }
}
