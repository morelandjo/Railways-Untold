package com.vodmordia.railwaysuntold.platform;

import java.util.ServiceLoader;

/**
 * Service loader for platform-specific implementations.
 * Each platform (Forge, Fabric) registers its implementations via ServiceLoader.
 */
public final class Services {

    private Services() {
    }

    /**
     * The Create mod helper for platform-specific Create API access.
     */
    public static final CreateHelper CREATE = load(CreateHelper.class);

    /**
     * Loads a service implementation using ServiceLoader.
     * @param clazz The service interface class
     * @return The platform-specific implementation
     * @throws RuntimeException if no implementation is found
     */
    private static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz)
            .findFirst()
            .orElseThrow(() -> new RuntimeException(
                "Failed to load service for " + clazz.getName() +
                ". This usually means the platform-specific module is not properly configured."
            ));
    }
}
