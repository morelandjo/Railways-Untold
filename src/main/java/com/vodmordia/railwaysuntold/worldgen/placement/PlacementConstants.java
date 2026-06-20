package com.vodmordia.railwaysuntold.worldgen.placement;


public final class PlacementConstants {

    private PlacementConstants() {
    } // Prevent instantiation

    /**
     * Standard segment length for straight track placement (one Minecraft chunk width).
     */
    public static final int STANDARD_SEGMENT_LENGTH = 16;

    /**
     * Default straight track length when no specific distance is specified.
     */
    public static final int DEFAULT_STRAIGHT_LENGTH = STANDARD_SEGMENT_LENGTH;

    /**
     * Default Y coordinate used when height cannot be determined.
     */
    public static final int DEFAULT_HEIGHT = 64;

    /**
     * Offset to center of chunk (chunks are 16x16, center is at 8,8).
     */
    public static final int CHUNK_CENTER_OFFSET = 8;

    /**
     * Default range in blocks for directional chunk deferral when placement fails.
     */
    public static final int DEFER_RANGE_BLOCKS = 16;

    /**
     * Minimum delay in ticks before any head expansion.
     * Ensures Create's TrackPropagator has time to process block entity links.
     */
    public static final int MIN_EXPANSION_DELAY_TICKS = 2;

    /**
     * Interval in ticks between resume checks for deferred placement.
     */
    public static final int RESUME_CHECK_INTERVAL_TICKS = 100;

    /**
     * Delay in ticks for deferred block entity creation during worldgen.
     */
    public static final int DEFERRED_BLOCK_ENTITY_TICKS = 10;

    /**
     * Required lookahead distance in blocks. If fewer blocks are visible ahead,
     * placement will defer until chunks load. This is the minimum acceptable visibility.
     */
    public static final int REQUIRED_LOOKAHEAD = 40;

    /**
     * Vertical extent below the center Y for obstacle avoidance bounding boxes.
     */
    public static final int OBSTACLE_BOX_VERTICAL_BELOW = 10;

    /**
     * Vertical extent above the center Y for obstacle avoidance bounding boxes.
     */
    public static final int OBSTACLE_BOX_VERTICAL_ABOVE = 20;

    /**
     * Maximum lookahead distance in blocks. The system attempts to scan this far ahead,
     * then reduces until REQUIRED_LOOKAHEAD if chunks aren't loaded.
     */
    public static final int MAX_LOOKAHEAD = 192;

    /**
     * Step increment for dynamic lookahead calculation (half a chunk).
     */
    public static final int LOOKAHEAD_STEP = 8;

    /**
     * Blocks ahead to check ground level for tunnel escape.
     */
    public static final int TUNNEL_ESCAPE_CHECK_DISTANCE = 8;

    /**
     * Maximum distance to surface that allows tunnel escape.
     */
    public static final int MAX_TUNNEL_ESCAPE_DISTANCE = 5;

}
