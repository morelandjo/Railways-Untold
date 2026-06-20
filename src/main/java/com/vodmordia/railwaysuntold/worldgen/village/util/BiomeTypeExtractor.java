package com.vodmordia.railwaysuntold.worldgen.village.util;

/**
 * Utility for extracting village biome type from various string sources.
 */
public final class BiomeTypeExtractor {

    private BiomeTypeExtractor() {
    }

    /**
     * Extract village biome type from a string (structure name, biome name, etc.)
     *
     * @param input The string to extract biome type from (case-insensitive)
     * @return The biome type ("plains", "desert", "savanna", "taiga", "snowy") or "unknown"
     */
    public static String extractBiomeType(String input) {
        if (input == null) {
            return "unknown";
        }

        String lower = input.toLowerCase();

        if (lower.contains("plains")) return "plains";
        if (lower.contains("desert")) return "desert";
        if (lower.contains("savanna")) return "savanna";
        if (lower.contains("taiga")) return "taiga";
        if (lower.contains("snowy")) return "snowy";

        return "unknown";
    }

    /**
     * Check if a biome name indicates an ocean biome.
     * Ocean biomes can have ocean ruins that are falsely detected as villages.
     *
     * @param biomeName The full biome name to check (e.g., "minecraft:deep_ocean")
     * @return true if this is an ocean biome
     */
    public static boolean isOceanBiome(String biomeName) {
        if (biomeName == null) {
            return false;
        }
        return biomeName.toLowerCase().contains("ocean");
    }
}
