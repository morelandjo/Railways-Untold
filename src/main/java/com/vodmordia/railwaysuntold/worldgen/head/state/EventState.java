package com.vodmordia.railwaysuntold.worldgen.head.state;

/**
 * Tracks event placement state for a track expansion head.
 */
public class EventState {

    private int blocksSinceLastEvent;

    public EventState() {
        this.blocksSinceLastEvent = 0;
    }

    public int getBlocksSinceLastEvent() {
        return blocksSinceLastEvent;
    }

    public void incrementEventDistance(int blocks) {
        blocksSinceLastEvent += blocks;
    }

    public void resetEventCounter() {
        blocksSinceLastEvent = 0;
    }

    /**
     * Sets the counter so the next event attempt happens after {@code cooldownBlocks}
     * more blocks rather than after a full {@code minDistance} interval. Used after a
     * failed placement to retry soon at a fresh anchor without re-attempting the same
     * one every tick.
     */
    public void deferRetry(int minDistance, int cooldownBlocks) {
        blocksSinceLastEvent = Math.max(0, minDistance - cooldownBlocks);
    }

    public void restore(int blocksSinceLastEvent) {
        this.blocksSinceLastEvent = blocksSinceLastEvent;
    }
}
