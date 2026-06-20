package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Holds all context needed for making placement decisions.
 */
public record DeciderContext(
        TrackExpansionHead head,
        BlockPos start,
        Direction direction,
        TerrainScanner.TerrainScan scan,
        ServerLevel level,
        RailwaysUntoldConfig config,
        int availableLookahead,
        Random random,
        @Nullable ExpansionHeadManager headManager
) {
}
