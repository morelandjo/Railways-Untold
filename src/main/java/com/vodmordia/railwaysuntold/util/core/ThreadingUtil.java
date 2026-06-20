package com.vodmordia.railwaysuntold.util.core;

import net.minecraft.server.level.ServerLevel;

/**
 * Utility class for threading operations in worldgen.
 */
public final class ThreadingUtil {

    private ThreadingUtil() {
    }

    /**
     * Delay in ticks for chunk stabilization after loading.
     */
    public static final int CHUNK_STABILIZATION_DELAY_TICKS = 5;

    /**
     * Schedules a task to run after chunk stabilization delay.
     *
     * @param level The server level
     * @param task  The task to execute after the delay
     */
    public static void scheduleAfterChunkStabilization(ServerLevel level, Runnable task) {
        // Schedule on the server's main thread after a delay
        scheduleDelayed(level, task, CHUNK_STABILIZATION_DELAY_TICKS);
    }

    /**
     * Schedules a task to run on the next tick.
     *
     * @param task The task to execute on the next tick
     */
    public static void scheduleNextTick(Runnable task) {
        // Use 1 tick delay to ensure it runs on next tick, not immediately
        com.vodmordia.railwaysuntold.ServerTickHandler.scheduleDelayed(task, 1);
    }

    /**
     * Schedules a task to run after a specified number of ticks.
     *
     * @param level The server level
     * @param task  The task to execute after the delay
     * @param ticks Number of ticks to wait before executing (20 ticks = 1 second)
     */
    public static void scheduleDelayed(ServerLevel level, Runnable task, int ticks) {
        if (ticks <= 0) {
            // Execute immediately if no delay
            level.getServer().execute(task);
            return;
        }

        // Delegate to ServerTickHandler for proper tick-based delays
        com.vodmordia.railwaysuntold.ServerTickHandler.scheduleDelayed(task, ticks);
    }
}
