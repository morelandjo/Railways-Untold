package com.vodmordia.railwaysuntold;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles server tick events for Railways Untold mod.
 * Processes deferred operations that must run on the main server thread.
 */
@EventBusSubscriber(modid = RailwaysUntold.MODID)
public class ServerTickHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int QUEUE_SIZE_WARNING_THRESHOLD = 100;
    private static final int MAX_TASKS_PER_TICK = 20;
    private static final long MAX_TICK_TIME_NS = 5_000_000L; // 5ms time budget per tick
    private static final AtomicLong lastQueueWarningTime = new AtomicLong(0);
    private static final long QUEUE_WARNING_COOLDOWN_MS = 5000; // 5 seconds

    /**
     * Internal class for delayed task execution.
     * Tasks are counted down each tick and executed when ready.
     */
    private static class DelayedTask {
        final Runnable task;
        int ticksRemaining;

        DelayedTask(Runnable task, int ticks) {
            this.task = task;
            this.ticksRemaining = ticks;
        }
    }

    private static final ConcurrentLinkedQueue<DelayedTask> delayedTasks = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger taskCount = new AtomicInteger(0);

    /**
     * Schedules a task to run after a specified number of ticks.
     *
     * @param task  The task to execute
     * @param ticks Number of ticks to wait before executing
     */
    public static void scheduleDelayed(Runnable task, int ticks) {
        delayedTasks.add(new DelayedTask(task, ticks));
        int queueSize = taskCount.incrementAndGet();

        if (queueSize >= QUEUE_SIZE_WARNING_THRESHOLD) {
            long now = System.currentTimeMillis();
            long lastWarning = lastQueueWarningTime.get();
            if (now - lastWarning > QUEUE_WARNING_COOLDOWN_MS) {
                if (lastQueueWarningTime.compareAndSet(lastWarning, now)) {
                    LOGGER.warn("[RailwaysUntold/ServerTickHandler] Delayed task queue size is large: {} entries (threshold: {})",
                            queueSize, QUEUE_SIZE_WARNING_THRESHOLD);
                }
            }
        }
    }

    /**
     * Clears all pending delayed tasks.
     * Called during world unload to prevent tasks from old worlds executing in new worlds.
     */
    public static void clearDelayedTasks() {
        delayedTasks.clear();
        taskCount.set(0);
        lastQueueWarningTime.set(0);
    }

    /**
     * Called at the end of every server tick.
     * Processes delayed tasks and deferred track block entity connections.
     *
     * @param event The server tick event
     */
    @SubscribeEvent
    public static void onServerTickEnd(ServerTickEvent.Post event) {
        if (com.vodmordia.railwaysuntold.util.core.SystemStateManager.isShuttingDown()) {
            return;
        }

        com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.tick(event.getServer());

        List<DelayedTask> readyTasks = new ArrayList<>();
        List<DelayedTask> pendingTasks = new ArrayList<>();

        DelayedTask task;
        while ((task = delayedTasks.poll()) != null) {
            task.ticksRemaining--;
            if (task.ticksRemaining <= 0) {
                readyTasks.add(task);
            } else {
                pendingTasks.add(task);
            }
        }

        delayedTasks.addAll(pendingTasks);

        int executed = 0;
        long tickStart = System.nanoTime();
        for (DelayedTask ready : readyTasks) {
            if (executed >= MAX_TASKS_PER_TICK || (System.nanoTime() - tickStart) >= MAX_TICK_TIME_NS) {
                ready.ticksRemaining = 0;
                delayedTasks.add(ready);
                continue;
            }
            try {
                ready.task.run();
            } catch (RuntimeException e) {
                LOGGER.error("[TICK-HANDLER] Error executing delayed task", e);
            }
            executed++;
            taskCount.decrementAndGet();
        }
    }
}
