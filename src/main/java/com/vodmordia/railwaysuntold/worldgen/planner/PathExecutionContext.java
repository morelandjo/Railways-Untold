package com.vodmordia.railwaysuntold.worldgen.planner;

import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Context object passed to segment execution methods.
 * Contains all dependencies needed for track placement decisions.
 */
public record PathExecutionContext(
        PathExecutionState state,
        BlockPos currentPos,
        Direction currentDir,
        TerrainScanner.TerrainScan scan,
        ServerLevel level
) {}
