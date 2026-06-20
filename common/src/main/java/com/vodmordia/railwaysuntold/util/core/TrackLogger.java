package com.vodmordia.railwaysuntold.util.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Holds the verbose-logging flag (read by log sites that gate on verbosity) plus an error passthrough.
 */
public final class TrackLogger {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Not volatile: only written once during config init on server thread,
    // read on same thread during worldgen. Avoids volatile read overhead in hot loops.
    private static boolean verboseEnabled = false;

    private TrackLogger() {}

    public static void setVerboseEnabled(boolean enabled) {
        verboseEnabled = enabled;
        LOGGER.info("[RailwaysUntold] Verbose logging {}", enabled ? "ENABLED" : "DISABLED");
    }

    public static boolean isVerboseEnabled() {
        return verboseEnabled;
    }

    /** Error log, always prints regardless of config. */
    public static void error(String format, Object... args) {
        LOGGER.error(format, args);
    }

}
