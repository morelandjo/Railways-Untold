package com.vodmordia.railwaysuntold.datapack;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks how many times each event/station definition has been placed in the world.
 * Used to enforce {@code max_appearances} limits on datapack definitions.
 * Persisted as world-level saved data.
 */
public class AppearanceTracker extends SavedData {

    private static final String DATA_NAME = "railwaysuntold_appearances";

    private final Map<String, Integer> counts = new HashMap<>();

    public AppearanceTracker() {
    }

    private AppearanceTracker(CompoundTag tag, HolderLookup.Provider provider) {
        for (String key : tag.getAllKeys()) {
            counts.put(key, tag.getInt(key));
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            tag.putInt(entry.getKey(), entry.getValue());
        }
        return tag;
    }

    /**
     * Returns the number of times the given definition has been placed.
     */
    public int getCount(ResourceLocation id) {
        return counts.getOrDefault(id.toString(), 0);
    }

    /**
     * Increments the placement count for the given definition.
     */
    public void increment(ResourceLocation id) {
        String key = id.toString();
        counts.put(key, counts.getOrDefault(key, 0) + 1);
        setDirty();
    }

    /**
     * Returns true if the definition has not exceeded its max appearances.
     * A maxAppearances of -1 means unlimited.
     */
    public boolean canAppear(ResourceLocation id, int maxAppearances) {
        if (maxAppearances < 0) return true;
        return getCount(id) < maxAppearances;
    }

    /**
     * Retrieves the AppearanceTracker for the given level.
     */
    public static AppearanceTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AppearanceTracker::new, AppearanceTracker::new),
                DATA_NAME
        );
    }
}
