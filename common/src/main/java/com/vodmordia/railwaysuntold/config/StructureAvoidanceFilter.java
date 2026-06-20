package com.vodmordia.railwaysuntold.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks whether a structure set should be ignored during avoidance based on the
 * configured blacklist. Supports full structure set names (e.g. "wythers:baobab_savanna")
 * and namespace prefixes ending with a colon (e.g. "wythers:" matches all from that mod).
 */
public final class StructureAvoidanceFilter {

    private StructureAvoidanceFilter() {}

    private static List<String> cachedBlacklist;
    private static List<String> cachedNamespaces;
    private static List<String> cachedExactNames;

    /**
     * Returns true if the given structure set name is blacklisted and should NOT
     * trigger avoidance detours.
     *
     * @param structureSetName fully qualified name, e.g. "wythers:baobab_savanna"
     */
    public static boolean isBlacklisted(String structureSetName) {
        List<String> blacklist = com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader.INSTANCE
                .resolveStructureSettings().avoidanceBlacklist();
        if (blacklist.isEmpty()) {
            return false;
        }

        rebuildCacheIfNeeded(blacklist);

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

    private static void rebuildCacheIfNeeded(List<String> blacklist) {
        if (blacklist == cachedBlacklist) {
            return;
        }
        cachedBlacklist = blacklist;
        cachedNamespaces = new ArrayList<>();
        cachedExactNames = new ArrayList<>();

        for (String entry : blacklist) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) continue;

            if (trimmed.endsWith(":")) {
                // Namespace prefix - e.g. "wythers:" matches "wythers:anything"
                cachedNamespaces.add(trimmed);
            } else {
                cachedExactNames.add(trimmed);
            }
        }
    }
}
