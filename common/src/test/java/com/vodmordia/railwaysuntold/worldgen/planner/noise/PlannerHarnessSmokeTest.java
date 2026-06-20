package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Phase 0 gate: proves the test source set runs with Minecraft classes on the
 * classpath and that the config seam resolves production defaults.
 */
class PlannerHarnessSmokeTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void configResolvesProductionDefaults() {
        // slopeRise=1, slopeRun=4 -> 0.25
        assertEquals(0.25, RailwaysUntoldConfig.getMaxSlopeRatio(), 1e-9);
        assertEquals(15, RailwaysUntoldConfig.getMinCurveRadius());
        assertEquals(25, RailwaysUntoldConfig.getMaxCurveRadius());
    }

    @Test
    void minecraftCoreTypesLoad() {
        BlockPos pos = new BlockPos(8, 64, -8);
        assertEquals(8, pos.getX());
        assertEquals(64, pos.getY());
        assertEquals(-8, pos.getZ());
        assertSame(Direction.EAST, Direction.WEST.getOpposite());
    }
}
