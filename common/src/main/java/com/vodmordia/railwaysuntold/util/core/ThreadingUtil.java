package com.vodmordia.railwaysuntold.util.core;

import net.minecraft.server.level.ServerLevel;

public final class ThreadingUtil {

    private ThreadingUtil() {}

    public static final int CHUNK_STABILIZATION_DELAY_TICKS = 5;

    public static void scheduleAfterChunkStabilization(ServerLevel level, Runnable task) {
        scheduleDelayed(level, task, CHUNK_STABILIZATION_DELAY_TICKS);
    }

    public static void scheduleNextTick(Runnable task) {
        com.vodmordia.railwaysuntold.RailwaysUntoldServerTickHandler.scheduleDelayed(task, 1);
    }

    /** 20 ticks = 1 second */
    public static void scheduleDelayed(ServerLevel level, Runnable task, int ticks) {
        if (ticks <= 0) {
            level.getServer().execute(task);
            return;
        }
        com.vodmordia.railwaysuntold.RailwaysUntoldServerTickHandler.scheduleDelayed(task, ticks);
    }
}
