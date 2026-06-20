package com.vodmordia.railwaysuntold.worldgen.placement.support;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Result type for all track placement strategies.
 */
public class SupportResult {

    public final boolean success;
    public final boolean needsRetry;
    public final BlockPos endpoint;
    public final Direction endDirection;
    public final int blocksTraveled;
    public final boolean isEmergencyCurve;
    public final boolean shouldResetCurveCounter;
    public final String failureReason;

    private SupportResult(Builder builder) {
        this.success = builder.success;
        this.needsRetry = builder.needsRetry;
        this.endpoint = builder.endpoint;
        this.endDirection = builder.endDirection;
        this.blocksTraveled = builder.blocksTraveled;
        this.isEmergencyCurve = false;
        this.shouldResetCurveCounter = false;
        this.failureReason = builder.failureReason;
    }

    /**
     * Creates a successful result.
     */
    public static Builder success(BlockPos endpoint, Direction endDirection, int blocksTraveled) {
        return new Builder()
            .success(true)
            .endpoint(endpoint)
            .endDirection(endDirection)
            .blocksTraveled(blocksTraveled);
    }

    /**
     * Creates a failed result.
     */
    public static Builder failure(String reason) {
        return new Builder()
            .success(false)
            .failureReason(reason);
    }

    /**
     * Builder for flexible SupportResult construction.
     */
    public static class Builder {
        private boolean success = false;
        private boolean needsRetry = false;
        private BlockPos endpoint = null;
        private Direction endDirection = null;
        private int blocksTraveled = 0;
        private String failureReason = null;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder needsRetry(boolean needsRetry) {
            this.needsRetry = needsRetry;
            return this;
        }

        public Builder endpoint(BlockPos endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder endDirection(Direction endDirection) {
            this.endDirection = endDirection;
            return this;
        }

        public Builder blocksTraveled(int blocksTraveled) {
            this.blocksTraveled = blocksTraveled;
            return this;
        }

        public Builder failureReason(String failureReason) {
            this.failureReason = failureReason;
            return this;
        }

        public SupportResult build() {
            return new SupportResult(this);
        }
    }
}
