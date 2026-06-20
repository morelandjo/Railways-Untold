package com.vodmordia.railwaysuntold.worldgen.placement;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for TrackExpansionOrchestrator instances per level.
 */
public class TrackPlacerRegistry {

    private static final Map<ResourceKey<Level>, TrackExpansionOrchestrator> placers = new ConcurrentHashMap<>();

    public static void register(ServerLevel level, TrackExpansionOrchestrator placer) {
        placers.put(level.dimension(), placer);
    }

    @Nullable
    public static TrackExpansionOrchestrator get(ServerLevel level) {
        return placers.get(level.dimension());
    }

    /**
     * Clears all registered placers.
     * Called when switching worlds to prevent stale data pollution.
     */
    public static void clear() {
        placers.clear();
    }
}
