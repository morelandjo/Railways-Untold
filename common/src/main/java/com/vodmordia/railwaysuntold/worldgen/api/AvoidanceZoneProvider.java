package com.vodmordia.railwaysuntold.worldgen.api;

import net.minecraft.server.level.ServerLevel;

import java.util.Collection;

/**
 * Public API: supplies the planner with zones to route around for a given level.
 * Register with {@link TrackAvoidanceApi#registerProvider(AvoidanceZoneProvider)} during mod init.
 * Called on the server thread during path planning.
 */
@FunctionalInterface
public interface AvoidanceZoneProvider {
    Collection<AvoidanceZone> getZones(ServerLevel level);
}
