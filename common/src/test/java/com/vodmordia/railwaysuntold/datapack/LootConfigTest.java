package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterizes {@link LootConfig#fromJson} - the parse of a definition's optional "loot" block into the
 * default/by_type/by_tag resolution table. Pins the EMPTY sentinel collapse (missing block, empty block, and
 * an all-blank block all return the same EMPTY instance) and the by_type/by_tag map parsing.
 */
class LootConfigTest {

    private static JsonObject obj() {
        return new JsonObject();
    }

    /** Wraps a "loot" sub-object inside the outer definition object that fromJson expects. */
    private static JsonObject withLoot(JsonObject loot) {
        JsonObject root = obj();
        root.add("loot", loot);
        return root;
    }

    @Nested
    class EmptyCollapse {
        @Test
        void missingLootBlockReturnsTheEmptySentinel() {
            assertSame(LootConfig.EMPTY, LootConfig.fromJson(obj()));
        }

        @Test
        void emptyLootBlockReturnsTheEmptySentinel() {
            assertSame(LootConfig.EMPTY, LootConfig.fromJson(withLoot(obj())));
        }

        @Test
        void lootBlockWithEmptyMapsReturnsTheEmptySentinel() {
            JsonObject loot = obj();
            loot.add("by_type", obj());
            loot.add("by_tag", obj());
            assertSame(LootConfig.EMPTY, LootConfig.fromJson(withLoot(loot)));
        }

        @Test
        void theEmptySentinelReportsItself() {
            assertTrue(LootConfig.EMPTY.isEmpty());
            assertNull(LootConfig.EMPTY.defaultTable());
            assertTrue(LootConfig.EMPTY.byType().isEmpty());
            assertTrue(LootConfig.EMPTY.byTag().isEmpty());
        }
    }

    @Nested
    class Parsing {
        @Test
        void defaultTableAloneIsParsed() {
            JsonObject loot = obj();
            loot.addProperty("default", "railwaysuntold:fallback");

            LootConfig config = LootConfig.fromJson(withLoot(loot));

            assertEquals("railwaysuntold:fallback", config.defaultTable().toString());
            assertTrue(config.byType().isEmpty());
            assertTrue(config.byTag().isEmpty());
            assertFalse(config.isEmpty());
        }

        @Test
        void byTypeEntriesAreParsedIntoTheTypeMap() {
            JsonObject byType = obj();
            byType.addProperty("minecraft:chest", "railwaysuntold:chest_loot");
            byType.addProperty("minecraft:barrel", "railwaysuntold:barrel_loot");
            JsonObject loot = obj();
            loot.add("by_type", byType);

            LootConfig config = LootConfig.fromJson(withLoot(loot));

            assertEquals(2, config.byType().size());
            assertEquals("railwaysuntold:chest_loot", config.byType().get("minecraft:chest").toString());
            assertEquals("railwaysuntold:barrel_loot", config.byType().get("minecraft:barrel").toString());
            assertNull(config.defaultTable());
        }

        @Test
        void byTagEntriesAreParsedIntoTheTagMap() {
            JsonObject byTag = obj();
            byTag.addProperty("treasure", "railwaysuntold:treasure_loot");
            JsonObject loot = obj();
            loot.add("by_tag", byTag);

            LootConfig config = LootConfig.fromJson(withLoot(loot));

            assertEquals("railwaysuntold:treasure_loot", config.byTag().get("treasure").toString());
        }

        @Test
        void allThreeSectionsCoexist() {
            JsonObject byType = obj();
            byType.addProperty("minecraft:chest", "railwaysuntold:chest_loot");
            JsonObject byTag = obj();
            byTag.addProperty("treasure", "railwaysuntold:treasure_loot");
            JsonObject loot = obj();
            loot.addProperty("default", "railwaysuntold:fallback");
            loot.add("by_type", byType);
            loot.add("by_tag", byTag);

            LootConfig config = LootConfig.fromJson(withLoot(loot));

            assertEquals("railwaysuntold:fallback", config.defaultTable().toString());
            assertEquals(1, config.byType().size());
            assertEquals(1, config.byTag().size());
            assertFalse(config.isEmpty());
        }
    }

    @Nested
    class MapImmutability {
        @Test
        void parsedMapsAreUnmodifiable() {
            JsonObject byType = obj();
            byType.addProperty("minecraft:chest", "railwaysuntold:chest_loot");
            JsonObject loot = obj();
            loot.add("by_type", byType);

            LootConfig config = LootConfig.fromJson(withLoot(loot));

            assertThrows(UnsupportedOperationException.class,
                    () -> config.byType().put("minecraft:barrel", config.byType().get("minecraft:chest")));
        }
    }
}
