package com.vodmordia.railwaysuntold.worldgen.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateStationPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;

import java.util.List;

/**
 * Makes a placed station's Create station blocks functional: either configuring the blocks the
 * schematic itself shipped, or placing fresh Create station blocks at both track ends (clearing the
 * station side first). Both paths share one generated station name. Track-position geometry is
 * delegated to {@link StationPlacementGeometry}.
 */
final class StationBlockConfigurator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private StationBlockConfigurator() {
    }

    /**
     * Configures station blocks that were placed by the schematic to be functional.
     * Finds each station block in the world using the schematic's local positions,
     * then sets up NBT targeting, track graph registration, and custom name.
     */
    static void configureSchematicStationBlocks(ServerLevel level, BlockPos stationPosition,
                                                Rotation rotation, BlockPos trackStart,
                                                BlockPos trackEnd, Direction trackDir,
                                                boolean travelDirectionPositive,
                                                SelectedStation station) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        List<BlockPos> localPositions = station.getStationBlockPositions();

        if (!CreateStationPlacer.isAvailable() || localPositions.isEmpty()) {
            return;
        }

        // Generate a shared name for all station blocks
        BlockPos midpoint = new BlockPos(
                (trackStart.getX() + trackEnd.getX()) / 2,
                (trackStart.getY() + trackEnd.getY()) / 2,
                (trackStart.getZ() + trackEnd.getZ()) / 2);
        String sharedName = CreateStationPlacer.generateStationName(level, midpoint);

        for (BlockPos localPos : localPositions) {
            BlockPos worldPos = RotationHelper.transformPosition(localPos, schematic.getSize(), stationPosition, rotation);

            if (!CreateStationPlacer.isStationBlock(level.getBlockState(worldPos))) {
                LOGGER.warn("[STATION] Expected station block at {} but found {}", worldPos, level.getBlockState(worldPos));
                continue;
            }

            BlockPos nearestTrack = StationPlacementGeometry.findNearestTrackPos(worldPos, trackStart, trackEnd, trackDir);

            // Determine direction: station blocks in the first half of the track face one way,
            // second half face the other
            boolean isCloserToPositiveEnd = StationPlacementGeometry.isCloserToPositiveEnd(nearestTrack, trackStart, trackEnd, trackDir);
            boolean blockDirection = isCloserToPositiveEnd ? travelDirectionPositive : !travelDirectionPositive;

            CreateStationPlacer.StationPlacementResult result = CreateStationPlacer.configureExistingStation(
                    level, worldPos, nearestTrack, blockDirection, sharedName);
            if (!result.success) {
                LOGGER.warn("[STATION] Failed to configure schematic station block at {}: {}", worldPos, result.errorMessage);
            }
        }
    }

    /**
     * Places Create train station blocks at both ends of the station track, pointing away from each other.
     * Both station blocks share the same generated name.
     *
     * @param level                   The server level
     * @param trackStart              One end of the station track
     * @param trackEnd                Other end of the station track
     * @param trackDir                Direction the track runs
     * @param travelDirectionPositive True if train travels in positive axis direction (sets station arrow)
     */
    static void placeCreateStationBlock(ServerLevel level, BlockPos trackStart, BlockPos trackEnd,
                                        Direction trackDir, boolean travelDirectionPositive,
                                        SelectedStation station) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();

        if (!CreateStationPlacer.isAvailable()) {
            return;
        }

        int perpOffset = validation.trackPerpOffset;
        int perpSize;
        if (validation.trackDirection == Direction.SOUTH || validation.trackDirection == Direction.NORTH) {
            perpSize = schematic.getWidth();
        } else {
            perpSize = schematic.getLength();
        }

        boolean buildingOnPositiveSide = perpOffset < perpSize / 2;
        Direction perpDirection = trackDir.getClockWise();
        Direction stationSide = buildingOnPositiveSide ? perpDirection.getOpposite() : perpDirection;

        clearStationSide(level, trackStart, trackEnd, stationSide);

        // Generate a shared name for both station blocks
        BlockPos midpoint = new BlockPos(
                (trackStart.getX() + trackEnd.getX()) / 2,
                (trackStart.getY() + trackEnd.getY()) / 2,
                (trackStart.getZ() + trackEnd.getZ()) / 2);
        String sharedName = CreateStationPlacer.generateStationName(level, midpoint);

        // Place station block at the end where the train arrives (entry end) - arrow points outward
        BlockPos entryTrackPos = StationPlacementGeometry.getTrackEndByDirection(trackStart, trackEnd, trackDir, travelDirectionPositive);
        boolean entryArrowDirection = travelDirectionPositive;
        CreateStationPlacer.StationPlacementResult entryResult = CreateStationPlacer.placeStationOppositeBuilding(
                level, entryTrackPos, trackDir, perpOffset, perpSize, entryArrowDirection, sharedName);
        if (!entryResult.success) {
            LOGGER.warn("[STATION] Failed to place entry station block: {}", entryResult.errorMessage);
        }

        // Place station block at the opposite end (exit end) - arrow points outward (opposite direction)
        BlockPos exitTrackPos = StationPlacementGeometry.getTrackEndByDirection(trackStart, trackEnd, trackDir, !travelDirectionPositive);
        boolean exitArrowDirection = !travelDirectionPositive;
        CreateStationPlacer.StationPlacementResult exitResult = CreateStationPlacer.placeStationOppositeBuilding(
                level, exitTrackPos, trackDir, perpOffset, perpSize, exitArrowDirection, sharedName);
        if (!exitResult.success) {
            LOGGER.warn("[STATION] Failed to place exit station block: {}", exitResult.errorMessage);
        }
    }

    /**
     * Clears the area on the station side of the track to make room for the Create station block.
     *
     * @param level       The server level
     * @param trackStart  One end of the station track
     * @param trackEnd    Other end of the station track
     * @param stationSide Direction from track center toward where station will be placed
     */
    private static void clearStationSide(ServerLevel level, BlockPos trackStart, BlockPos trackEnd, Direction stationSide) {
        int horizontalClear = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalClear = RailwaysUntoldConfig.getVerticalExpansion();

        int trackMinX = Math.min(trackStart.getX(), trackEnd.getX());
        int trackMaxX = Math.max(trackStart.getX(), trackEnd.getX());
        int trackMinZ = Math.min(trackStart.getZ(), trackEnd.getZ());
        int trackMaxZ = Math.max(trackStart.getZ(), trackEnd.getZ());
        int trackY = trackStart.getY();

        int minX, maxX, minZ, maxZ, minY, maxY;
        minY = trackY;
        maxY = trackY + verticalClear;

        if (stationSide == Direction.EAST || stationSide == Direction.WEST) {
            int trackX = trackStart.getX();
            minZ = trackMinZ;
            maxZ = trackMaxZ;

            if (stationSide == Direction.EAST) {
                minX = trackX + 1;
                maxX = trackX + horizontalClear;
            } else {
                minX = trackX - horizontalClear;
                maxX = trackX - 1;
            }
        } else {
            int trackZ = trackStart.getZ();
            minX = trackMinX;
            maxX = trackMaxX;

            if (stationSide == Direction.SOUTH) {
                minZ = trackZ + 1;
                maxZ = trackZ + horizontalClear;
            } else {
                minZ = trackZ - horizontalClear;
                maxZ = trackZ - 1;
            }
        }

        BlockPos clearMin = new BlockPos(minX, minY, minZ);
        BlockPos clearMax = new BlockPos(maxX, maxY, maxZ);

        RemovalUtil.clearBox(level, clearMin, clearMax);
    }
}
