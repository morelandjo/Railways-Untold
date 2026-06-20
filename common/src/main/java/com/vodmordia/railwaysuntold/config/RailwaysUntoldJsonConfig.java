package com.vodmordia.railwaysuntold.config;

import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class RailwaysUntoldJsonConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(RailwaysUntoldJsonConfig.class);
    private static final String CONFIG_DIR = "railways-untold";

    private static boolean initialized = false;

    public static synchronized void load() {
        if (initialized) {
            return;
        }

        try {
            createDirectoryIfNeeded(getConfigDir());
        } catch (IOException e) {
            LOGGER.error("Failed to create config directory", e);
        }

        // Warn about legacy config directories that are no longer scanned
        checkLegacyDirectory(getConfigDir().resolve("train"), "train");
        checkLegacyDirectory(getConfigDir().resolve("station"), "station");
        checkLegacyDirectory(getConfigDir().resolve("events"), "events");

        initialized = true;
    }

    private static void createDirectoryIfNeeded(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * Warns if a legacy config directory contains .nbt files that are no longer loaded.
     */
    private static void checkLegacyDirectory(Path dir, String type) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.nbt")) {
            if (stream.iterator().hasNext()) {
                LOGGER.warn("[MIGRATION] Found .nbt files in config/railways-untold/{}/. " +
                        "Custom {} are now loaded from data packs instead of the config folder. " +
                        "See the mod documentation for the new data pack JSON format.", type, type);
            }
        } catch (IOException ignored) {
        }
    }


    public static int getBridgeElevationThreshold() {
        return com.vodmordia.railwaysuntold.RailwaysUntoldConfig.getBridgeElevationThreshold();
    }

    public static Path getConfigDir() {
        return Platform.getConfigFolder().resolve(CONFIG_DIR);
    }

    private RailwaysUntoldJsonConfig() {}
}
