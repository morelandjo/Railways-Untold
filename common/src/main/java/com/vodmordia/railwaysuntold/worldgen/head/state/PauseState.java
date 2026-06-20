package com.vodmordia.railwaysuntold.worldgen.head.state;

/**
 * Tracks pause state for a track expansion head.
 */
public class PauseState {

    private boolean paused;
    private long pausedAtTime;

    public PauseState() {
        resume();
    }

    public boolean isPaused() {
        return paused;
    }

    public long getPausedAtTime() {
        return pausedAtTime;
    }

    public void pause() {
        if (!this.paused) {
            this.paused = true;
            this.pausedAtTime = System.currentTimeMillis();
        }
    }

    public void resume() {
        this.paused = false;
        this.pausedAtTime = 0;
    }

    public void restore(boolean isPaused, long pausedTime) {
        this.paused = isPaused;
        this.pausedAtTime = pausedTime;
    }
}
