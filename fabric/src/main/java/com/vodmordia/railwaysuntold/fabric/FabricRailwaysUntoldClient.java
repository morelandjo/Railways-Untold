package com.vodmordia.railwaysuntold.fabric;

import com.vodmordia.railwaysuntold.client.ClientSetup;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client-specific entry point for Railways Untold mod.
 */
public class FabricRailwaysUntoldClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Initialize client-side components
        ClientSetup.initialize();
    }
}
