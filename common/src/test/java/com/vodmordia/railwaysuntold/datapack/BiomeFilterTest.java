package com.vodmordia.railwaysuntold.datapack;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import net.minecraft.core.registries.Registries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Characterizes {@link BiomeFilter#parse} - the '#'-prefix dispatch that turns a JSON string into either a
 * {@link BiomeFilter.Tag} (biome tag) or a {@link BiomeFilter.Direct} (a single biome id). The whitelist/
 * blacklist matching itself is registry-bound and covered by gametests, not here.
 */
class BiomeFilterTest {

    @BeforeAll
    static void setUp() {
        // The tag path builds a TagKey via Registries.BIOME, whose class init needs the registry bootstrap.
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void aPlainStringBecomesADirectBiomeId() {
        BiomeFilter filter = BiomeFilter.parse("minecraft:plains");

        BiomeFilter.Direct direct = assertInstanceOf(BiomeFilter.Direct.class, filter);
        assertEquals("minecraft:plains", direct.biomeId().toString());
    }

    @Test
    void aHashPrefixedStringBecomesATag() {
        BiomeFilter filter = BiomeFilter.parse("#minecraft:is_forest");

        BiomeFilter.Tag tag = assertInstanceOf(BiomeFilter.Tag.class, filter);
        assertEquals("minecraft:is_forest", tag.tag().location().toString());
        assertEquals(Registries.BIOME, tag.tag().registry());
    }

    @Test
    void onlyTheLeadingHashIsStripped() {
        // The '#' marks the value as a tag; the remainder is parsed verbatim as the tag id.
        BiomeFilter.Tag tag = assertInstanceOf(BiomeFilter.Tag.class, BiomeFilter.parse("#railwaysuntold:custom"));
        assertEquals("railwaysuntold:custom", tag.tag().location().toString());
    }

    @Test
    void modNamespacedDirectIdsRoundTrip() {
        BiomeFilter.Direct direct = assertInstanceOf(BiomeFilter.Direct.class, BiomeFilter.parse("railwaysuntold:depot"));
        assertEquals("railwaysuntold:depot", direct.biomeId().toString());
    }
}
