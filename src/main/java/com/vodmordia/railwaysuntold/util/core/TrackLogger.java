package com.vodmordia.railwaysuntold.util.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Holds the verbose-logging flag, read by log sites that gate their output on verbosity.
 */
public final class TrackLogger {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Not volatile
    // read on same thread during worldgen.
    private static boolean verboseEnabled = false;

    private TrackLogger() {}

    public static void setVerboseEnabled(boolean enabled) {
        verboseEnabled = enabled;
        LOGGER.info("[RailwaysUntold] Verbose logging {}", enabled ? "ENABLED" : "DISABLED");
    }

    public static boolean isVerboseEnabled() {
        return verboseEnabled;
    }

}
