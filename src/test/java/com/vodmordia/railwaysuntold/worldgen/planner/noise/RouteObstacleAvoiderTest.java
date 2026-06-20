package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Box-aware structure avoidance: when a structure carries a footprint, the crossing test measures the
 * corridor against the footprint's true extent perpendicular to the path instead of a bounding circle
 * sized to its longest axis. A long-thin village laid along the corridor is then cleared tightly on its
 * short side, where the circle would over-report a crossing and force a needless detour.
 */
class RouteObstacleAvoiderTest {

    private static final BlockPos START = new BlockPos(0, 72, 0);
    private static final BlockPos TARGET = new BlockPos(384, 72, 0);

    private static PredictedStructure village(BlockPos center) {
        return new PredictedStructure(
                new ChunkPos(center), center, "minecraft:villages", List.of("minecraft:village"), true);
    }

    private static boolean crosses(PredictedStructure s) {
        // level=null: a footprint-less village falls back to the default bounding-radius circle.
        return RouteObstacleAvoider.segmentCrossesAvoidableStructure(START, TARGET, List.of(s), null);
    }

    @Test
    void footprintClearsTheCorridorOnTheShortAxisWhereTheCircleWouldNot() {
        // Village body well to the side of the corridor: 64 wide along X (the path), only 16 deep in Z,
        // centred 28 blocks off the path. Nearest building edge is 20 blocks from the corridor.
        BlockPos center = new BlockPos(192, 72, 28);
        PredictedStructure noFootprint = village(center);
        PredictedStructure withFootprint = noFootprint.withFootprint(
                List.of(new BoundingBox(160, 64, 20, 224, 80, 36)));

        // The default-radius circle (48) reaches the corridor -> reports a crossing and over-avoids.
        assertTrue(crosses(noFootprint), "circle sized to the long axis spuriously reports a crossing");
        // The real footprint is only 8 deep on the perpendicular axis -> the corridor is clear.
        assertFalse(crosses(withFootprint), "box-aware test clears the corridor on the footprint's short side");
    }

    @Test
    void footprintStraddlingTheCorridorStillReportsACrossing() {
        BlockPos center = new BlockPos(192, 72, 0);
        PredictedStructure onPath = village(center).withFootprint(
                List.of(new BoundingBox(160, 64, -8, 224, 80, 8)));
        assertTrue(crosses(onPath), "a footprint sitting on the corridor must still be detected");
    }
}
