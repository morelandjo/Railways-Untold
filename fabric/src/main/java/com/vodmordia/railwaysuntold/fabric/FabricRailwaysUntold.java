package com.vodmordia.railwaysuntold.fabric;

import com.simibubi.create.api.event.TrackGraphMergeEvent;
import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.config.ModConfigHolder;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric-specific entry point for Railways Untold mod.
 */
public class FabricRailwaysUntold implements ModInitializer {

    @Override
    public void onInitialize() {
        // Register config first (before mod initialization)
        ModConfigHolder.register();

        // Initialize the mod directly
        RailwaysUntold.init();

        // Register Create Fabric events using Fabric's native event system
        // This enables event-driven train placement when track graphs merge
        TrackGraphReadyListener.registerCreateEvents(() ->
            TrackGraphMergeEvent.EVENT.register(TrackGraphReadyListener::onTrackGraphMerge));
    }
}
