package com.vodmordia.railwaysuntold.worldgen.terrain;

/**
 * Terrain type classification used by coarse route waypoints and segments.
 */
public class TerrainZoneAnalyzer {

    /**
     * Classification of terrain zone types.
     */
    public enum TerrainType {
        /** Open traversable terrain - suitable for beziers */
        OPEN,
        /** Solid terrain above track level - tunnel or curve strategies */
        MOUNTAIN,
        /** Drop-off below track level - bridge or curve strategies */
        RAVINE,
        /** Significant upward elevation change - cliff face going up */
        CLIFF_UP,
        /** Significant downward elevation change - cliff face going down */
        CLIFF_DOWN
    }
}
