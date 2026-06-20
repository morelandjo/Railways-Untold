package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementLogContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Parameter object for bezier placement requests.
 *
 */
public class BezierPlacementRequest {

    /** Starting track position */
    public final BlockPos start;

    /** Ending track position */
    public final BlockPos end;

    /** Direction at start of bezier */
    public final Direction startDirection;

    /** Direction at end of bezier (same as start for straight, different for curves) */
    public final Direction endDirection;

    /** Elevation change for sloped curves (positive = up, negative = down, 0 = flat) */
    public final int elevationChange;

    /** If true, clear blocks along the bezier path (false for station track) */
    public final boolean performClearing;

    /** Configuration for clearing settings (null to use defaults) */
    public final RailwaysUntoldConfig config;

    /** Minimum corner of clearing exclusion zone (nullable) - blocks inside this box won't be cleared */
    public final BlockPos clearingExclusionMin;

    /** Maximum corner of clearing exclusion zone (nullable) - blocks inside this box won't be cleared */
    public final BlockPos clearingExclusionMax;

    /** Torch placement offset for continuous torch spacing across tunnel segments */
    public final int torchOffset;

    /** Logging context for consolidated track placement logging (nullable) */
    public final PlacementLogContext logContext;

    /** UUID of the head placing this segment (for ownership tracking) */
    public final UUID headId;

    /**
     * Callback invoked when deferred terrain clearing completes, with the underground segment count.
     * May be null if no callback is needed.
     */
    public final IntConsumer onClearingComplete;

    /**
     * Slope at the start endpoint (rise/run ratio from the incoming segment).
     * Used to tilt the start normal for smooth slope transitions.
     * 0.0 = flat incoming, positive = arriving uphill, negative = arriving downhill.
     */
    public final double incomingSlope;

    /**
     * Slope at the end endpoint (rise/run ratio for the outgoing segment).
     * Used to tilt the end normal for smooth slope transitions.
     * 0.0 = flat outgoing, positive = leaving uphill, negative = leaving downhill.
     */
    public final double outgoingSlope;

    private BezierPlacementRequest(Builder builder) {
        this.start = builder.start;
        this.end = builder.end;
        this.startDirection = builder.startDirection;
        this.endDirection = builder.endDirection;
        this.elevationChange = builder.elevationChange;
        this.performClearing = builder.performClearing;
        this.config = builder.config;
        this.clearingExclusionMin = builder.clearingExclusionMin;
        this.clearingExclusionMax = builder.clearingExclusionMax;
        this.torchOffset = builder.torchOffset;
        this.logContext = builder.logContext;
        this.headId = builder.headId;
        this.onClearingComplete = builder.onClearingComplete;
        this.incomingSlope = builder.incomingSlope;
        this.outgoingSlope = builder.outgoingSlope;
    }

    /**
     * Creates a builder for a straight bezier (same direction at both ends).
     *
     * @param start Starting position
     * @param end Ending position
     * @param direction Direction of travel (used for both start and end)
     * @return Builder instance
     */
    public static Builder builder(BlockPos start, BlockPos end, Direction direction) {
        return new Builder(start, end, direction, direction);
    }

    /**
     * Creates a builder for a curved bezier (different directions at start/end).
     *
     * @param start Starting position
     * @param end Ending position
     * @param startDirection Direction at start
     * @param endDirection Direction at end
     * @return Builder instance
     */
    public static Builder builder(BlockPos start, BlockPos end, Direction startDirection, Direction endDirection) {
        return new Builder(start, end, startDirection, endDirection);
    }

    /**
     * Convenience method: calculates Manhattan distance between start and end.
     */
    public int getDistance() {
        return start.distManhattan(end);
    }

    @Override
    public String toString() {
        return String.format("BezierPlacementRequest[%s -> %s, dir=%s->%s, elev=%d, clear=%b]",
            start, end, startDirection, endDirection, elevationChange, performClearing);
    }

    /**
     * Builder for BezierPlacementRequest.
     */
    public static class Builder {
        private final BlockPos start;
        private final BlockPos end;
        private final Direction startDirection;
        private final Direction endDirection;

        // Defaults
        private int elevationChange = 0;
        private boolean performClearing = true;
        private RailwaysUntoldConfig config = null;
        private BlockPos clearingExclusionMin = null;
        private BlockPos clearingExclusionMax = null;
        private int torchOffset = 0;
        private PlacementLogContext logContext = null;
        private UUID headId = null;
        private IntConsumer onClearingComplete = null;
        private double incomingSlope = 0.0;
        private double outgoingSlope = 0.0;

        private Builder(BlockPos start, BlockPos end, Direction startDirection, Direction endDirection) {
            this.start = start;
            this.end = end;
            this.startDirection = startDirection;
            this.endDirection = endDirection;
        }

        /**
         * Sets elevation change for sloped beziers.
         *
         * @param elevation Positive for up, negative for down, 0 for flat
         */
        public Builder elevationChange(int elevation) {
            this.elevationChange = elevation;
            return this;
        }

        /**
         * Controls whether to clear blocks along the bezier path.
         * Default is true. Set to false for station track.
         */
        public Builder performClearing(boolean clear) {
            this.performClearing = clear;
            return this;
        }

        /**
         * Sets configuration for clearing settings.
         * If null, default config will be used for clearing.
         */
        public Builder config(RailwaysUntoldConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Sets a bounding box where clearing should be skipped.
         * Any segment position within (min, max) inclusive will not be cleared.
         *
         * @param min Minimum corner of the exclusion box (smallest X, Y, Z)
         * @param max Maximum corner of the exclusion box (largest X, Y, Z)
         */
        public Builder clearingExclusionBox(BlockPos min, BlockPos max) {
            this.clearingExclusionMin = min;
            this.clearingExclusionMax = max;
            return this;
        }

        /**
         * Sets the torch placement offset for continuous torch spacing across tunnel segments.
         * The offset is added to the segment index when determining torch placement.
         *
         * @param offset Starting offset for torch interval calculation
         */
        public Builder torchOffset(int offset) {
            this.torchOffset = offset;
            return this;
        }

        /**
         * Sets the logging context for track placement logging.
         *
         * @param context The logging context (nullable)
         */
        public Builder logContext(PlacementLogContext context) {
            this.logContext = context;
            return this;
        }

        /**
         * Sets the head ID for ownership tracking.
         *
         * @param headId UUID of the head placing this segment (nullable)
         */
        public Builder headId(UUID headId) {
            this.headId = headId;
            return this;
        }

        /**
         * Sets a callback invoked when deferred terrain clearing completes.
         *
         * @param callback Receives the underground segment count when clearing finishes
         */
        public Builder onClearingComplete(IntConsumer callback) {
            this.onClearingComplete = callback;
            return this;
        }

        /**
         * Sets the incoming slope (from the previous segment) for smooth normal matching.
         */
        public Builder incomingSlope(double slope) {
            this.incomingSlope = slope;
            return this;
        }

        /**
         * Sets the outgoing slope (toward the next segment) for smooth normal matching.
         */
        public Builder outgoingSlope(double slope) {
            this.outgoingSlope = slope;
            return this;
        }

        /**
         * Builds the request object.
         */
        public BezierPlacementRequest build() {
            return new BezierPlacementRequest(this);
        }
    }
}
