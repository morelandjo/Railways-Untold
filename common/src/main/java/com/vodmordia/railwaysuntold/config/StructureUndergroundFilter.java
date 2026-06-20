package com.vodmordia.railwaysuntold.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether a structure set is known to generate underground or underwater
 * and should be skipped during surface avoidance. Supports full structure set names
 * (e.g. "minecraft:shipwrecks") and namespace prefixes ending with a colon
 * (e.g. "terralith:" skips all from that mod).
 *
 */
public final class StructureUndergroundFilter {

    private StructureUndergroundFilter() {}

    private static List<String> cachedList;
    private static List<String> cachedNamespaces;
    private static List<String> cachedExactNames;

    /**
     * Returns true if the given structure set name is a known underground/underwater
     * structure that should NOT trigger avoidance detours.
     *
     * @param structureSetName fully qualified name, e.g. "minecraft:shipwrecks"
     */
    public static boolean isUnderground(String structureSetName) {
        List<String> list = com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader.INSTANCE
                .resolveStructureSettings().undergroundList();
        if (list.isEmpty()) {
            return false;
        }

        rebuildCacheIfNeeded(list);

        for (String ns : cachedNamespaces) {
            if (structureSetName.startsWith(ns)) {
                return true;
            }
        }

        for (String exact : cachedExactNames) {
            if (structureSetName.equals(exact)) {
                return true;
            }
        }

        return false;
    }

    private static void rebuildCacheIfNeeded(List<String> list) {
        if (list == cachedList) {
            return;
        }
        cachedList = list;
        cachedNamespaces = new ArrayList<>();
        cachedExactNames = new ArrayList<>();

        for (String entry : list) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) continue;

            if (trimmed.endsWith(":")) {
                cachedNamespaces.add(trimmed);
            } else {
                cachedExactNames.add(trimmed);
            }
        }
    }
}
