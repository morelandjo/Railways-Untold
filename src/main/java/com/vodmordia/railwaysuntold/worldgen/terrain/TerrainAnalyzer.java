package com.vodmordia.railwaysuntold.worldgen.terrain;

import net.minecraft.core.BlockPos;

/**
 * Analyzes terrain scan data to classify scenarios for placement strategies.
 */
public class TerrainAnalyzer {

    /**
     * Terrain scenario classification for strategic placement decisions.
     */
    public enum TerrainScenario {
        MOUNTAIN,    // Solid blocks ahead - tunnel or curve strategies
        RAVINE,      // Open air ahead - bridge or curve strategies
        MIXED        // Normal terrain - use standard placement logic
    }

    /**
     * Result of terrain analysis
     */
    public static class TerrainAnalysis {
        private final TerrainScenario scenario;

        public TerrainAnalysis(TerrainScenario scenario) {
            this.scenario = scenario;
        }

        public boolean isMountain() {
            return scenario == TerrainScenario.MOUNTAIN;
        }

        public boolean isRavine() {
            return scenario == TerrainScenario.RAVINE;
        }
    }

    /**
     * Analyzes terrain scan to determine scenario.
     *
     * @param scan     The terrain scan result
     * @param trackPos Current track position
     * @return TerrainAnalysis with scenario classification
     */
    public static TerrainAnalysis analyzeTerrain(TerrainScanner.TerrainScan scan, BlockPos trackPos) {
        if (scan == null || !scan.canSeeFullRange()) {
            return new TerrainAnalysis(TerrainScenario.MIXED);
        }

        int[] heightProfile = scan.getHeightProfile();
        int trackHeight = trackPos.getY();

        // Analyze positions within configured range
        final int END_INDEX = Math.min(TerrainConstants.ANALYSIS_END_INDEX, heightProfile.length - 1);
        final int ANALYSIS_RANGE = END_INDEX - TerrainConstants.ANALYSIS_START_INDEX + 1;

        int solidCount = 0;
        int airCount = 0;

        for (int i = TerrainConstants.ANALYSIS_START_INDEX; i <= END_INDEX; i++) {
            int terrainHeight = heightProfile[i];

            // Check if terrain is significantly above track (solid blocks in path)
            if (terrainHeight > trackHeight + TerrainConstants.SOLID_HEIGHT_OFFSET) {
                solidCount++;
            }
            // Check if terrain is significantly below track (air/drop-off)
            else if (terrainHeight < trackHeight + TerrainConstants.AIR_HEIGHT_OFFSET) {
                airCount++;
            }
        }

        double solidPercentage = (double) solidCount / ANALYSIS_RANGE;
        double airPercentage = (double) airCount / ANALYSIS_RANGE;

        TerrainScenario scenario;
        if (solidPercentage >= TerrainConstants.SCENARIO_THRESHOLD) {
            scenario = TerrainScenario.MOUNTAIN;
        } else if (airPercentage >= TerrainConstants.SCENARIO_THRESHOLD) {
            scenario = TerrainScenario.RAVINE;
        } else {
            scenario = TerrainScenario.MIXED;
        }

        return new TerrainAnalysis(scenario);
    }
}
