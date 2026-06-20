package com.vodmordia.railwaysuntold.worldgen.integration.deferred;

import net.minecraft.server.level.ServerLevel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Base class for deferred processing managers
 *
 */
public abstract class DeferredProcessingManager {

    /**
     * Last tick we processed (thread-safe).
     */
    private final AtomicLong lastProcessTick = new AtomicLong(0);

    /**
     * Get the interval in ticks between processing batches.
     * @return Number of ticks to wait between processing
     */
    protected abstract int getProcessIntervalTicks();

    /**
     * Check if enough time has passed since the last processing
     *
     * @param currentTick The current game tick
     * @return true if processing should occur, false if it should be skipped
     */
    protected boolean shouldProcess(long currentTick) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return false;
        }

        long lastTick = lastProcessTick.get();
        if (currentTick - lastTick < getProcessIntervalTicks()) {
            return false;
        }
        // Atomically update - only one thread will succeed if multiple try simultaneously
        return lastProcessTick.compareAndSet(lastTick, currentTick);
    }

    /**
     * Reset the last process tick to allow immediate processing.
     */
    protected void resetProcessTick() {
        lastProcessTick.set(0);
    }

    /**
     * Check if the pending level is from a stale/previous world.
     *
     * @param pendingLevel The level from the pending item
     * @param currentLevel The current level being processed
     * @return true if the pending level is stale
     */
    protected boolean isStaleLevel(ServerLevel pendingLevel, ServerLevel currentLevel) {
        return pendingLevel.getServer() != currentLevel.getServer();
    }

    /**
     * Check if two levels are the same dimension.
     *
     * @param level1 First level
     * @param level2 Second level
     * @return true if they are the same dimension
     */
    protected boolean isSameDimension(ServerLevel level1, ServerLevel level2) {
        String dim1 = level1.dimension().location().toString();
        String dim2 = level2.dimension().location().toString();
        return dim1.equals(dim2);
    }

    /**
     * Check if a pending item should be skipped for the given world.
     *
     * @param pendingLevel The level from the pending item
     * @param currentLevel The current level being processed
     * @return true if the item should be skipped (wrong dimension or stale world)
     */
    protected boolean shouldSkipForWorld(ServerLevel pendingLevel, ServerLevel currentLevel) {
        if (!isSameDimension(pendingLevel, currentLevel)) {
            return true;
        }
        if (isStaleLevel(pendingLevel, currentLevel)) {
            return true;
        }
        return false;
    }
}
