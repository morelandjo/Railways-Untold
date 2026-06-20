package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.support.TerrainFillPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Lays the straight track between a station's two endpoints: places track + terrain fill along the
 * run, defers positions in unloaded chunks (with bounded retries), and connects the Create track
 * graph. The retry/scheduling state is self-contained.
 */
final class StationTrackPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int DEFERRED_TRACK_RETRY_TICKS = 100; // ~5 seconds
    private static final int DEFERRED_TRACK_MAX_RETRIES = 3;

    private StationTrackPlacer() {
    }

    static boolean placeStationTrack(ServerLevel level, BlockPos trackStart, BlockPos trackEnd, Direction trackDir) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, trackStart);
        BlockState trackState = CreateTrackUtil.getStraightTrack(trackDir);
        Vec3 tangent = Vec3.atLowerCornerOf(trackDir.getNormal());

        int dx = Integer.compare(trackEnd.getX() - trackStart.getX(), 0);
        int dz = Integer.compare(trackEnd.getZ() - trackStart.getZ(), 0);
        int distance = Math.max(
                Math.abs(trackEnd.getX() - trackStart.getX()),
                Math.abs(trackEnd.getZ() - trackStart.getZ())
        );

        int placedCount = 0;
        java.util.List<BlockPos> skippedPositions = new java.util.ArrayList<>();
        for (int i = 0; i <= distance; i++) {
            BlockPos pos = trackStart.offset(dx * i, 0, dz * i);

            var chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
            if (chunk == null) {
                skippedPositions.add(pos.immutable());
                continue;
            }

            ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true);
            TerrainFillPlacer.placeTerrainFill(level, pos, tangent);
            placedCount++;
        }

        if (!skippedPositions.isEmpty()) {
            LOGGER.warn("[STATION-TRACK] {} track positions in unloaded chunks - scheduling deferred placement", skippedPositions.size());
            scheduleDeferredTrackPlacement(level, skippedPositions, trackState, tangent);
        }

        // Use non-destructive graph connection to avoid train stutter
        if (!com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector
                .connectStraightDirect(level, trackStart, trackEnd, trackDir)) {
            if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                triggerTrackPropagator(level, trackStart);
                triggerTrackPropagator(level, trackEnd);
            } else {
                LOGGER.warn("[STATION-TRACK] Direct graph connect failed with trains present at {} -> {}", trackStart, trackEnd);
            }
        }

        return placedCount > 0 || !skippedPositions.isEmpty();
    }

    private static void scheduleDeferredTrackPlacement(ServerLevel level, java.util.List<BlockPos> positions,
                                                        BlockState trackState, Vec3 tangent) {
        scheduleDeferredTrackPlacement(level, positions, trackState, tangent, 0);
    }

    private static void scheduleDeferredTrackPlacement(ServerLevel level, java.util.List<BlockPos> positions,
                                                        BlockState trackState, Vec3 tangent, int attempt) {
        com.vodmordia.railwaysuntold.util.core.ThreadingUtil.scheduleDelayed(level, () -> {
            java.util.List<BlockPos> stillSkipped = new java.util.ArrayList<>();
            for (BlockPos pos : positions) {
                var chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
                if (chunk == null) {
                    stillSkipped.add(pos);
                    continue;
                }
                ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true);
                TerrainFillPlacer.placeTerrainFill(level, pos, tangent);
            }
            if (!stillSkipped.isEmpty() && attempt < DEFERRED_TRACK_MAX_RETRIES) {
                LOGGER.warn("[STATION-TRACK] Deferred placement attempt {} - {} positions still in unloaded chunks",
                        attempt + 1, stillSkipped.size());
                scheduleDeferredTrackPlacement(level, stillSkipped, trackState, tangent, attempt + 1);
            } else if (!stillSkipped.isEmpty()) {
                LOGGER.warn("[STATION-TRACK] Gave up on {} station-track positions still in unloaded chunks after {} attempts; rail may have a gap under the station",
                        stillSkipped.size(), DEFERRED_TRACK_MAX_RETRIES);
            }
        }, DEFERRED_TRACK_RETRY_TICKS);
    }

    private static void triggerTrackPropagator(ServerLevel level, BlockPos trackPos) {
        BlockState trackState = level.getBlockState(trackPos);
        CreateTrackUtil.triggerTrackPropagator(level, trackPos, trackState);
    }
}
