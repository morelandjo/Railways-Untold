package com.vodmordia.railwaysuntold.worldgen.terrain;

/**
 * Shared constants for terrain scanning and analysis.
 */
public class TerrainConstants {

    /**
     * Start index for terrain scenario analysis (skip first 8 blocks for decision-making room).
     */
    public static final int ANALYSIS_START_INDEX = 8;

    /**
     * End index for terrain scenario analysis.
     */
    public static final int ANALYSIS_END_INDEX = 30;

    /**
     * Percentage threshold for classifying terrain as MOUNTAIN or RAVINE scenario.
     */
    public static final double SCENARIO_THRESHOLD = 0.80;

    /**
     * Height offset above track for detecting solid blocks (mountain scenario).
     */
    public static final int SOLID_HEIGHT_OFFSET = 2;

    /**
     * Height offset below track for detecting air/drop-offs (ravine scenario).
     */
    public static final int AIR_HEIGHT_OFFSET = -3;

    /**
     * Default fallback height when chunk is not loaded.
     */
    public static final int DEFAULT_FALLBACK_HEIGHT = 64;

    /**
     * Maximum range to scan downward when looking for ground.
     */
    public static final int GROUND_SCAN_RANGE = 30;

    /**
     * Buffer offset above track clearing zone for underground detection.
     */
    public static final int UNDERGROUND_BUFFER_OFFSET = 2;

    private TerrainConstants() {
    }
}
