package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.core.ThreadingUtil;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages head scheduling state machine and task scheduling.
 * Tracks scheduling states to prevent duplicate task scheduling and handle deferrals.
 */
public class HeadScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int MIN_EXPANSION_DELAY_TICKS = PlacementConstants.MIN_EXPANSION_DELAY_TICKS;

    /** While autoload is draining its chunk backlog, held heads re-check this often (ticks) before running. */
    private static final int DRAIN_RECHECK_DELAY_TICKS = 20;

    /** Without autoload, a head only expands within this many blocks (horizontal) of a player - track
     *  grows where the player explores rather than marching to distant villages. Beyond it, the head
     *  holds and re-checks every {@link #PROXIMITY_RECHECK_DELAY_TICKS}. ~10 chunks, within view distance. */
    private static final int EXPANSION_PLAYER_RANGE = 160;
    private static final long EXPANSION_PLAYER_RANGE_SQ = (long) EXPANSION_PLAYER_RANGE * EXPANSION_PLAYER_RANGE;
    private static final int PROXIMITY_RECHECK_DELAY_TICKS = 40;

    /**
     * Explicit states for head scheduling state machine.
     */
    public enum HeadSchedulingState {
        IDLE,       // No task scheduled, head is waiting
        PENDING,    // Task is scheduled, waiting to execute
        EXECUTING,  // Task is currently running
        DEFERRED    // Waiting for chunks, will be resumed by ChunkLoadTrackExpander
    }

    /**
     * Identifies who is requesting a schedule operation.
     */
    public enum ScheduleCaller {
        EXTERNAL,       // ChunkLoadTrackExpander, resume from pause, etc.
        CONTINUATION,   // Internal continuation from current execution
        INITIAL         // First schedule when head is created/restored
    }

    /**
     * State machine record for head scheduling.
     */
    public record HeadState(
            HeadSchedulingState state,
            long timestamp,
            @Nullable Set<ChunkPos> waitingChunks
    ) {
        static HeadState idle() {
            return new HeadState(HeadSchedulingState.IDLE, System.currentTimeMillis(), null);
        }

        static HeadState pending() {
            return new HeadState(HeadSchedulingState.PENDING, System.currentTimeMillis(), null);
        }

        static HeadState executing() {
            return new HeadState(HeadSchedulingState.EXECUTING, System.currentTimeMillis(), null);
        }

        static HeadState deferred(Set<ChunkPos> chunks) {
            return new HeadState(HeadSchedulingState.DEFERRED, System.currentTimeMillis(), chunks);
        }

        boolean canScheduleExternally() {
            return state == HeadSchedulingState.IDLE || state == HeadSchedulingState.DEFERRED;
        }
    }

    private final ServerLevel level;
    private final int placementDelayTicks;
    private final Map<UUID, HeadState> headStates = new ConcurrentHashMap<>();

    public HeadScheduler(ServerLevel level, int placementDelayTicks) {
        this.level = level;
        this.placementDelayTicks = placementDelayTicks;
    }

    /**
     * Schedules a head for expansion after the specified delay.
     *
     * @param head          The head to schedule
     * @param delayTicks    Delay before execution
     * @param caller        Who is requesting the schedule
     * @param expansionTask The task to run when executed
     * @return true if scheduling succeeded, false if rejected
     */
    public boolean scheduleExpansion(TrackExpansionHead head, int delayTicks, ScheduleCaller caller,
                                     Consumer<TrackExpansionHead> expansionTask) {
        UUID headId = head.getHeadId();
        HeadState current = headStates.get(headId);

        if (!validateStateTransition(current, caller)) {
            LOGGER.warn("[SCHEDULER] Head {} schedule REJECTED: caller={}, currentState={}, pos={}",
                    head.getHeadNumber(), caller,
                    current != null ? current.state() : "null",
                    head.getPosition());
            return false;
        }

        transitionState(headId, HeadState.pending());

        Runnable task = createExpansionTask(head, headId, expansionTask);

        int effectiveDelay = Math.max(delayTicks, MIN_EXPANSION_DELAY_TICKS);
        ThreadingUtil.scheduleDelayed(level, task, effectiveDelay);
        return true;
    }

    /**
     * Schedules a head for expansion with default placement delay.
     */
    public boolean scheduleContinuation(TrackExpansionHead head, Consumer<TrackExpansionHead> expansionTask) {
        return scheduleExpansion(head, placementDelayTicks, ScheduleCaller.CONTINUATION, expansionTask);
    }

    /**
     * Schedules a head for initial expansion with minimum delay.
     */
    public boolean scheduleInitial(TrackExpansionHead head, Consumer<TrackExpansionHead> expansionTask) {
        return scheduleExpansion(head, MIN_EXPANSION_DELAY_TICKS, ScheduleCaller.INITIAL, expansionTask);
    }

    private boolean validateStateTransition(HeadState current, ScheduleCaller caller) {
        return switch (caller) {
            case EXTERNAL -> current == null || current.canScheduleExternally();
            case CONTINUATION -> true;
            case INITIAL -> current == null || current.state() == HeadSchedulingState.IDLE;
        };
    }

    private Runnable createExpansionTask(TrackExpansionHead head, UUID headId,
                                         Consumer<TrackExpansionHead> expansionTask) {
        final BlockPos scheduledPosition = head.getPosition();
        final Runnable[] self = new Runnable[1];

        self[0] = () -> {
            if (!head.getPosition().equals(scheduledPosition)) {
                // Transition from PENDING to IDLE so the head can be rescheduled
                // by other systems (checkDeferredHeadsForLoadedChunks, chunk load events, etc.)
                HeadState current = headStates.get(headId);
                if (current != null && current.state() == HeadSchedulingState.PENDING) {
                    transitionState(headId, HeadState.idle());
                }
                return;
            }

            // Autoload gate: hold this head's work while it's parked - either the whole system is
            // draining the chunk backlog, or this head is outside the current round-robin window.
            // Heads run on their own self-rescheduling tasks independent of autoload, so without this
            // they keep placing/ticketing and the drain/round-robin never bounds the footprint.
            // Re-post unchanged (state stays PENDING, the position guard above is still valid) and
            // re-check soon. No-op outside autoload: mayHeadRun() is always true when autoload is off.
            if (!AutoLoadController.mayHeadRun(headId)) {
                ThreadingUtil.scheduleDelayed(level, self[0], DRAIN_RECHECK_DELAY_TICKS);
                return;
            }

            // Proximity bound (no autoload): in normal play, expand only NEAR a player rather than
            // marching a self-loaded chunk corridor across the world to distant village targets. Beyond
            // range, hold and re-check - expansion resumes when a player approaches. Autoload is the
            // hands-off far-generation mode and bypasses this gate. Only applies when players are present
            // in this level, so a gametest level (no players, drives heads via stepHead) is unaffected.
            if (!AutoLoadController.isActive() && !level.players().isEmpty()
                    && noPlayerWithinExpansionRange(head.getPosition())) {
                ThreadingUtil.scheduleDelayed(level, self[0], PROXIMITY_RECHECK_DELAY_TICKS);
                return;
            }

            HeadState state = headStates.get(headId);
            if (state == null) {
                // State was cleaned up by another task's PENDING->IDLE transition.
                // Position matched, so this task is still valid - proceed with execution.
            } else if (state.state() != HeadSchedulingState.PENDING) {
                return;
            }

            transitionState(headId, HeadState.executing());

            try {
                expansionTask.accept(head);
            } finally {
                finalizeExecution(headId);
            }
        };
        return self[0];
    }

    /**
     * Finalizes execution by transitioning to IDLE if still in EXECUTING state.
     * Called after expansion completes (unless deferred).
     */
    private void finalizeExecution(UUID headId) {
        HeadState finalState = headStates.get(headId);
        if (finalState != null && finalState.state() == HeadSchedulingState.EXECUTING) {
            LOGGER.warn("[SCHEDULER] Head {} finalized as IDLE - no continuation or defer was scheduled during execution",
                    headId.toString().substring(0, 8));
            transitionState(headId, HeadState.idle());
        }
    }

    /**
     * Transitions a head to DEFERRED state for chunk waiting.
     */
    public void transitionToDeferred(UUID headId, Set<ChunkPos> chunks) {
        transitionState(headId, HeadState.deferred(chunks));
    }

    /**
     * Drops any stale PENDING/EXECUTING/DEFERRED state so an EXTERNAL reschedule is accepted again.
     * Used by the stall watchdog to re-kick a head whose scheduled task was lost (e.g. a continuation
     * never fired) and which therefore sits IDLE-but-untracked, making no progress and never failing.
     */
    public void forceIdle(UUID headId) {
        transitionState(headId, HeadState.idle());
    }

    /** True when NO player is within {@link #EXPANSION_PLAYER_RANGE} (horizontal) of {@code pos}. */
    private boolean noPlayerWithinExpansionRange(BlockPos pos) {
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            long dx = player.getBlockX() - pos.getX();
            long dz = player.getBlockZ() - pos.getZ();
            if (dx * dx + dz * dz <= EXPANSION_PLAYER_RANGE_SQ) {
                return false;
            }
        }
        return true;
    }

    private void transitionState(UUID headId, HeadState newState) {
        if (newState.state() == HeadSchedulingState.IDLE) {
            headStates.remove(headId);
        } else {
            headStates.put(headId, newState);
        }
    }
}
