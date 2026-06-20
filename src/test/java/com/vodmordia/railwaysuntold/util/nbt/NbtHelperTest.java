package com.vodmordia.railwaysuntold.util.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link NbtHelper} - the pure NBT map round-trips (UUID->UUID, UUID->String) and the recursive
 * string-UUID -> binary-UUID conversion the saved-data trackers rely on. In-memory NBT only; world/config-free.
 */
class NbtHelperTest {

    private static final String K = "Key", V = "Val", LIST = "Entries";

    @Test
    void uuidToUuidMapRoundTrips() {
        Map<UUID, UUID> original = new LinkedHashMap<>();
        original.put(new UUID(1, 1), new UUID(9, 9));
        original.put(new UUID(2, 2), new UUID(8, 8));

        CompoundTag parent = new CompoundTag();
        parent.put(LIST, NbtHelper.saveUuidToUuidMap(original, K, V));

        Map<UUID, UUID> loaded = new HashMap<>();
        NbtHelper.loadUuidToUuidMap(parent, LIST, K, V, loaded::put);
        assertEquals(original, loaded, "every key/value pair survives the round trip");
    }

    @Test
    void uuidToStringMapRoundTripsViaToString() {
        Map<UUID, String> original = new LinkedHashMap<>();
        original.put(new UUID(1, 1), "STATION_PLACED");
        original.put(new UUID(2, 2), "DOES_NOT_EXIST");

        CompoundTag parent = new CompoundTag();
        parent.put(LIST, NbtHelper.saveUuidToStringMap(original, K, V));

        Map<UUID, String> loaded = new HashMap<>();
        NbtHelper.loadUuidToStringMap(parent, LIST, K, V, loaded::put);
        assertEquals(original, loaded);
    }

    @Test
    void loadIsANoOpWhenTheListIsMissing() {
        Map<UUID, UUID> loaded = new HashMap<>();
        NbtHelper.loadUuidToUuidMap(new CompoundTag(), LIST, K, V, loaded::put);
        assertTrue(loaded.isEmpty(), "no list tag -> nothing loaded, no exception");
    }

    @Test
    void convertStringUuidsRewritesKnownFieldsInPlaceAndLeavesInvalidOnesAlone() {
        UUID id = new UUID(123, 456);
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id.toString());          // a known UUID field, valid
        tag.putString("name", id.toString());        // not a known UUID field - untouched
        tag.putString("UUID", "not-a-uuid");         // known field, but invalid string

        NbtHelper.convertStringUuidsToIntArrays(tag);

        assertTrue(tag.hasUUID("id"), "valid UUID string in a known field becomes a binary UUID");
        assertEquals(id, tag.getUUID("id"));
        assertFalse(tag.hasUUID("name"), "unknown field name is left as a string");
        assertFalse(tag.hasUUID("UUID"), "an unparseable string is left as-is");
    }

    @Test
    void convertStringUuidsRecursesIntoNestedCompoundsAndLists() {
        UUID nestedId = new UUID(7, 7);
        UUID listId = new UUID(5, 5);

        CompoundTag child = new CompoundTag();
        child.putString("GraphID", nestedId.toString());
        CompoundTag root = new CompoundTag();
        root.put("child", child);

        ListTag list = new ListTag();
        CompoundTag listEntry = new CompoundTag();
        listEntry.putString("TrackId", listId.toString());
        list.add(listEntry);
        root.put("items", list);

        NbtHelper.convertStringUuidsToIntArrays(root);

        assertTrue(root.getCompound("child").hasUUID("GraphID"), "nested compound is converted");
        assertEquals(nestedId, root.getCompound("child").getUUID("GraphID"));
        assertTrue(root.getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).hasUUID("TrackId"),
                "compound inside a list is converted");
    }
}
