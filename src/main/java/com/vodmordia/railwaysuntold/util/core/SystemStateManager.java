package com.vodmordia.railwaysuntold.util.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Centralized manager for clearing all system state during server lifecycle events.
 */
public final class SystemStateManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<ClearableEntry> registeredComponents = new CopyOnWriteArrayList<>();
    private static volatile boolean shuttingDown = false;

    private SystemStateManager() {
    }

    /**
     * Register a component that needs to be cleared during server start/stop.
     *
     * @param name      Human-readable name for logging
     * @param clearable The component to clear
     */
    public static void register(String name, Clearable clearable) {
        registeredComponents.add(new ClearableEntry(name, clearable));
    }

    /**
     * Returns true if the server is in the process of shutting down.
     * Used to prevent event handlers from re-creating state after it has been cleared.
     */
    public static boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Marks the system as shutting down. Called at the start of server stop processing.
     */
    public static void markShuttingDown() {
        shuttingDown = true;
    }

    public static void clearAll() {

        for (ClearableEntry entry : registeredComponents) {
            try {
                entry.clearable.clear();
            } catch (RuntimeException e) {
                LOGGER.error("[SYSTEM-STATE] Error clearing {}: {}", entry.name, e.getMessage(), e);
            }
        }

        shuttingDown = false;
    }

    private record ClearableEntry(String name, Clearable clearable) {
    }
}
