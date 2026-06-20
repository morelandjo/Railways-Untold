package com.vodmordia.railwaysuntold.worldgen.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public API for registering exclusion volumes that the track planner must avoid.
 * Other mods (e.g. Railways Untold Additions, which owns the in-world Safety Zoner
 * item) call {@link #registerProvider(AvoidanceZoneProvider)} during their init
 * and supply the zones associated with a given level. The planner queries all
 * registered providers when routing tracks.
 */
public final class TrackAvoidanceApi {

    private static final List<AvoidanceZoneProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private TrackAvoidanceApi() {
    }

    public static void registerProvider(AvoidanceZoneProvider provider) {
        PROVIDERS.add(provider);
    }

    public static List<AvoidanceZone> collectZones(ServerLevel level) {
        if (PROVIDERS.isEmpty()) {
            return List.of();
        }
        List<AvoidanceZone> all = new ArrayList<>();
        for (AvoidanceZoneProvider provider : PROVIDERS) {
            all.addAll(provider.getZones(level));
        }
        return all;
    }

    public static boolean isInsideAnyZone(BlockPos pos, ServerLevel level) {
        for (AvoidanceZoneProvider provider : PROVIDERS) {
            for (AvoidanceZone zone : provider.getZones(level)) {
                if (zone.contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }
}
