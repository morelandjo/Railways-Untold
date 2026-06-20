package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgeSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import com.vodmordia.railwaysuntold.worldgen.village.StationPlacementGeometry.EntryExitPoints;
import com.vodmordia.railwaysuntold.worldgen.village.StationPlacementGeometry.TrackEndpoints;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Builds train stations at village edges using WorldEdit .schem files.
 * Delegates schematic loading/caching to {@link StationSchematicCache} and the
 * pure footprint/track geometry to {@link StationPlacementGeometry}.
 */
public class VillageStationBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result of station placement operation.
     */
    public static class StationPlacementResult {
        public final boolean success;
        public final BlockPos stationPosition;
        public final BlockPos trackEntryPoint;   // Where approaching track connects
        public final BlockPos trackExitPoint;    // Where track exits after station
        public final Direction trackDirection;

        private StationPlacementResult(boolean success, BlockPos stationPosition,
                                       BlockPos trackEntryPoint, BlockPos trackExitPoint,
                                       Direction trackDirection) {
            this.success = success;
            this.stationPosition = stationPosition;
            this.trackEntryPoint = trackEntryPoint;
            this.trackExitPoint = trackExitPoint;
            this.trackDirection = trackDirection;
        }

        public static StationPlacementResult success(BlockPos stationPos, BlockPos entry, BlockPos exit, Direction dir) {
            return new StationPlacementResult(true, stationPos, entry, exit, dir);
        }

        /**
         * Deferred placement is in progress - returns a successful result since the
         * placement has been queued and will complete asynchronously.
         */
        public static StationPlacementResult pending(BlockPos stationPos, BlockPos entry, BlockPos exit, Direction dir) {
            return success(stationPos, entry, exit, dir);
        }

        public static StationPlacementResult failure() {
            return new StationPlacementResult(false, null, null, null, null);
        }
    }

    /**
     * Track-first placement: the head has arrived at headPos facing headDir, and the station
     * must be stamped so its track entry block sits one block ahead (headPos.relative(headDir)).
     */
    public static StationPlacementResult placeStationAlignedToHead(ServerLevel level,
                                                                    BlockPos headPos,
                                                                    Direction headDir,
                                                                    SelectedStation station) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();

        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(
                validation.trackDirection, headDir);
        BlockPos desiredWorldTrackEntry = headPos.relative(headDir);
        BlockPos placementPos = SchematicPlacer.inverseEntryTransform(
                desiredWorldTrackEntry, headDir, schematic, validation, rotation);

        SchematicPlacer.SchematicPlacementResult result = SchematicPlacer.place(
                level, schematic, validation, placementPos, rotation,
                RailwaysUntoldConfig.getDefault(), false, station.loot()
        );

        if (!result.success) {
            LOGGER.error("[VILLAGE-STATION] Track-first placement failed at {}: {}",
                    placementPos, result.failureReason);
            return StationPlacementResult.failure();
        }

        StationPlacementResult placed = finishStationPlacement(level, result, headPos, station);
        if (placed.success && !placed.trackEntryPoint.equals(headPos)) {
            LOGGER.error("[VILLAGE-STATION] Track-first alignment invariant violated: head at {} but station entry at {} (origin={}, rotation={}). Forward/inverse transform mismatch.",
                    headPos, placed.trackEntryPoint, placementPos, rotation);
            return StationPlacementResult.failure();
        }
        return placed;
    }

    /**
     * Finishes station placement after schematic blocks are placed: lays the station
     * track, configures station blocks (either schematic-embedded or a freshly
     * placed Create station block), places bridge support underneath, and registers
     * the footprint for protection.
     */
    private static StationPlacementResult finishStationPlacement(ServerLevel level,
                                                                 SchematicPlacer.SchematicPlacementResult result,
                                                                 BlockPos approachPos,
                                                                 SelectedStation station) {
        BlockPos trackStart = result.trackStart;
        BlockPos trackEnd = result.trackEnd;
        Direction trackDir = result.trackDirection;

        TrackEndpoints endpoints = new TrackEndpoints(trackStart, trackEnd, trackDir);
        EntryExitPoints entryExit = endpoints.getOffsetEntryExit(approachPos);
        BlockPos entryPoint = entryExit.entry();
        BlockPos exitPoint = entryExit.exit();

        boolean travelDirectionPositive = StationPlacementGeometry.calculateTravelDirectionPositive(entryPoint, exitPoint, trackDir);

        if (!StationTrackPlacer.placeStationTrack(level, trackStart, trackEnd, trackDir)) {
            LOGGER.error("[VILLAGE-STATION] Failed to place station track");
            return StationPlacementResult.failure();
        }
        if (station.hasStationBlocks()) {
            StationBlockConfigurator.configureSchematicStationBlocks(level, result.stationPosition, result.rotation,
                    trackStart, trackEnd, trackDir, travelDirectionPositive, station);
        } else {
            StationBlockConfigurator.placeCreateStationBlock(level, trackStart, trackEnd, trackDir, travelDirectionPositive, station);
        }

        placeStationBridgeSupport(level, trackStart, trackEnd, trackDir);

        // Use actual track endpoints (not the offset entry/exit) so the station bounds
        StationBoundTracker.registerStationBounds(level, result.stationPosition, trackStart, trackEnd, station);

        return StationPlacementResult.success(result.stationPosition, entryPoint, exitPoint, result.trackDirection);
    }

    /**
     * Places bridge decking and pillars under the station track when the station is over water.
     */
    private static void placeStationBridgeSupport(ServerLevel level, BlockPos trackStart,
                                                   BlockPos trackEnd, Direction trackDir) {
        // Check if the station midpoint is over water
        int midX = (trackStart.getX() + trackEnd.getX()) / 2;
        int midZ = (trackStart.getZ() + trackEnd.getZ()) / 2;
        int waterY = TerrainHeightUtil.getWaterSurfaceY(level, midX, midZ);
        if (waterY < 0 || trackStart.getY() <= waterY + 1) {
            return;
        }

        BridgeSchematicPlacer bridgePlacer = new BridgeSchematicPlacer();
        Vec3 tangent = Vec3.atLowerCornerOf(trackDir.getNormal());

        int dx = Integer.compare(trackEnd.getX() - trackStart.getX(), 0);
        int dz = Integer.compare(trackEnd.getZ() - trackStart.getZ(), 0);
        int distance = Math.max(
                Math.abs(trackEnd.getX() - trackStart.getX()),
                Math.abs(trackEnd.getZ() - trackStart.getZ())
        );

        for (int i = 0; i <= distance; i++) {
            BlockPos pos = trackStart.offset(dx * i, 0, dz * i);
            bridgePlacer.placeDeckingAt(level, pos, tangent);
        }
        // Piers come from the railing-pattern boundaries the placer collected during decking.
        for (net.minecraft.world.phys.Vec3[] pier : bridgePlacer.getPillarBoundaries()) {
            bridgePlacer.placePillarAt(level, pier[0], pier[1]);
        }
        bridgePlacer.updateConnections(level);
    }

    /**
     * Clears the cached schematic (for testing or reload purposes).
     */
    public static void clearCache() {
        StationSchematicCache.clearCache();
    }
}
