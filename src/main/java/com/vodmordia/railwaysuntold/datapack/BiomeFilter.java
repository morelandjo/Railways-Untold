package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * Filter that matches biomes by either direct ID or tag reference.
 * Parsed from JSON strings: "minecraft:plains" for direct, "#minecraft:is_forest" for tags.
 */
public sealed interface BiomeFilter {

    boolean matches(Holder<Biome> biome);

    /**
     * Parses a biome filter string. Strings starting with '#' are treated as biome tags.
     */
    static BiomeFilter parse(String value) {
        if (value.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.parse(value.substring(1));
            return new Tag(TagKey.create(Registries.BIOME, tagId));
        }
        return new Direct(ResourceLocation.parse(value));
    }

    /**
     * Returns true if the biome passes all whitelist and blacklist filters.
     * Empty whitelist means all biomes are allowed.
     */
    static boolean matchesFilters(Holder<Biome> biome,
                                   List<BiomeFilter> whitelist,
                                   List<BiomeFilter> blacklist) {
        if (!whitelist.isEmpty()) {
            boolean whitelisted = false;
            for (BiomeFilter filter : whitelist) {
                if (filter.matches(biome)) {
                    whitelisted = true;
                    break;
                }
            }
            if (!whitelisted) return false;
        }

        for (BiomeFilter filter : blacklist) {
            if (filter.matches(biome)) {
                return false;
            }
        }

        return true;
    }

    record Direct(ResourceLocation biomeId) implements BiomeFilter {
        @Override
        public boolean matches(Holder<Biome> biome) {
            return biome.unwrapKey()
                    .map(key -> key.location().equals(biomeId))
                    .orElse(false);
        }
    }

    record Tag(TagKey<Biome> tag) implements BiomeFilter {
        @Override
        public boolean matches(Holder<Biome> biome) {
            return biome.is(tag);
        }
    }
}
