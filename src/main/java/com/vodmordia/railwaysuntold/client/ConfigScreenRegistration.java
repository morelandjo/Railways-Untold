package com.vodmordia.railwaysuntold.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only helper to register the config screen.
 * Isolated in its own class so that client-only classes are never loaded on dedicated servers.
 */
public final class ConfigScreenRegistration {

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(
                IConfigScreenFactory.class,
                (container, modListScreen) -> new ConfigurationScreen(container, modListScreen)
        );
    }

    private ConfigScreenRegistration() {}
}
