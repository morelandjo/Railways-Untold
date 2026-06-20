package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.util.core.ThreadingUtil;
import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateStationPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.companion.CompanionTrackPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.*;
import com.vodmordia.railwaysuntold.worldgen.placement.support.BridgeSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import com.vodmordia.railwaysuntold.worldgen.village.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

/**
 * World-init setup for the track expansion system: places the initial track,
 * starting train, starting station, and companion track, then seeds the expansion heads.
 */
final class InitialPlacementBootstrapper {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;
    private final RailwaysUntoldConfig config;
    private final Direction initialTrackDirection;
    private final BlockPos worldSpawnPos;
    private final TrackPlacementSavedData savedData;
    private final ExpansionHeadManager headManager;
    private final HeadScheduler scheduler;
    private final Consumer<TrackExpansionHead> performExpansion;

    InitialPlacementBootstrapper(ServerLevel level, RailwaysUntoldConfig config,
                                  Direction initialTrackDirection, BlockPos worldSpawnPos,
                                  TrackPlacementSavedData savedData, ExpansionHeadManager headManager,
                                  HeadScheduler scheduler, Consumer<TrackExpansionHead> performExpansion) {
        this.level = level;
        this.config = config;
        this.initialTrackDirection = initialTrackDirection;
        this.worldSpawnPos = worldSpawnPos;
        this.savedData = savedData;
        this.headManager = headManager;
        this.scheduler = scheduler;
        this.performExpansion = performExpansion;
    }

    void expandFromInitialChunk(ChunkPos initialChunk) {
        if (headManager.hasRestoredState()) {
            List<TrackExpansionHead> activeHeads = headManager.getActiveHeads();
            for (TrackExpansionHead head : activeHeads) {
                if (!head.isComplete()) {
                    scheduler.scheduleInitial(head, performExpansion);
                }
            }
            return;
        }

        ThreadingUtil.scheduleAfterChunkStabilization(level, () -> {
            // Force the initial chunk to finish generating before reading its height. A nudged
            // origin can land in a chunk that is loaded but not yet at FULL status, whose heightmap
            // still reads sea level - placing the track there puts it below the real surface, where
            // terrain generation then overwrites it with stone and no track graph ever forms.
            level.getChunk(initialChunk.x, initialChunk.z);

            BlockPos initialTrackPos = StartingTrainPlacer.getInitialTrackPosition(level, initialChunk, config,
                    worldSpawnPos, initialTrackDirection);

            // Select station early so we can raise the initial track to match the
            // station's track Y level.
            SelectedStation earlyStation = null;
            if (RailwaysUntoldConfig.isStationAtStartEnabled() && !RailwaysUntoldConfig.isSideBySideEnabled()) {
                earlyStation = StationSchematicCache.selectStation(level, initialTrackPos, new Random(level.getSeed()));
                if (earlyStation != null && earlyStation.validation().trackY > 0) {
                    initialTrackPos = initialTrackPos.above(earlyStation.validation().trackY);
                }
            }

            StartingTrainPlacer.placeInitialTrack(level, initialTrackPos, initialTrackDirection);

            final SelectedStation selectedStation = earlyStation;
            final BlockPos trackPos = initialTrackPos;
            TrackGraphReadyListener.waitForGraphReady(level, trackPos, initialTrackDirection, () -> {
                continueAfterGraphReady(trackPos, selectedStation);
            });
        });
    }

    private void continueAfterGraphReady(BlockPos initialTrackPos, @Nullable SelectedStation preSelectedStation) {
        BlockPos head1StartPos = initialTrackPos;
        BlockPos head2StartPos = null;

        if (RailwaysUntoldConfig.isStartingTrainEnabled()) {
            Direction positiveDir = ExpansionHeadManager.getPositiveDirection(initialTrackDirection);
            Direction negativeDir = positiveDir.getOpposite();

            StartingTrainPlacer.TrainPlacementResult trainResult = StartingTrainPlacer.placeTrain(
                    level, initialTrackPos, positiveDir);

            if (trainResult.success) {
                head1StartPos = trainResult.trackExitPoint.relative(positiveDir);
                if (trainResult.rearExitPoint != null) {
                    head2StartPos = trainResult.rearExitPoint.relative(negativeDir);
                }

                // Place station AFTER the train, skipping track placement so it
                // doesn't overwrite the train's bezier-connected track
                if (RailwaysUntoldConfig.isStationAtStartEnabled() && !RailwaysUntoldConfig.isSideBySideEnabled()) {
                    BlockPos[] stationTrackEnds = placeStartingStation(initialTrackPos, positiveDir, trainResult, preSelectedStation);
                    if (stationTrackEnds != null) {
                        // If station track extends past the train exit, advance heads
                        // so they don't start inside the station bounds
                        BlockPos stationExit = stationTrackEnds[1];
                        BlockPos stationEntry = stationTrackEnds[0];
                        int axisSign = positiveDir.getAxis() == Direction.Axis.Z
                                ? positiveDir.getStepZ() : positiveDir.getStepX();
                        boolean isZ = positiveDir.getAxis() == Direction.Axis.Z;

                        int exitCoord = isZ ? stationExit.getZ() : stationExit.getX();
                        int head1Coord = isZ ? head1StartPos.getZ() : head1StartPos.getX();
                        if (exitCoord * axisSign > head1Coord * axisSign) {
                            head1StartPos = stationExit.relative(positiveDir);
                        }

                        if (head2StartPos != null) {
                            int entryCoord = isZ ? stationEntry.getZ() : stationEntry.getX();
                            int head2Coord = isZ ? head2StartPos.getZ() : head2StartPos.getX();
                            if (entryCoord * (-axisSign) > head2Coord * (-axisSign)) {
                                head2StartPos = stationEntry.relative(negativeDir);
                            }
                        }
                    }
                }

                // Place companion track spanning the starting train area so both
                // companion lines connect seamlessly at the origin.
                if (RailwaysUntoldConfig.isSideBySideEnabled()) {
                    placeCompanionForStartingTrain(trainResult, positiveDir);
                }
            } else {
                LOGGER.warn("[ORCHESTRATOR] Failed to place starting train: {} - continuing without train",
                        trainResult.failureReason);
            }
        }

        VillageAssignmentTracker tracker = VillageTargetingSavedData.get(level).getAssignmentTracker();

        if (head2StartPos != null) {
            headManager.initializeHeadsWithBothCustomStarts(head1StartPos, head2StartPos, initialTrackDirection, level, config, tracker);
        } else {
            headManager.initializeHeadsWithCustomStart(initialTrackPos, head1StartPos, initialTrackDirection, level, config, tracker);
        }

        headManager.saveState(savedData);

        List<TrackExpansionHead> initialHeads = headManager.getActiveHeads();
        for (TrackExpansionHead head : initialHeads) {
            scheduler.scheduleInitial(head, performExpansion);
        }
    }

    /**
     * Places a companion straight track segment spanning the starting train area.
     * This fills the gap between where the two companion track lines begin expanding.
     */
    private void placeCompanionForStartingTrain(StartingTrainPlacer.TrainPlacementResult trainResult,
                                                 Direction positiveDir) {
        int separation = CompanionTrackPlacer.getCompanionSeparation();
        Direction offsetDir = CompanionTrackPlacer.getCompanionOffsetDirection(positiveDir);

        // The train spans from rearExitPoint to trackExitPoint.
        // Place companion track covering that same span (plus one block each side to overlap
        // with where the expansion heads will start placing companion segments).
        BlockPos trainRear = trainResult.rearExitPoint;
        BlockPos trainFront = trainResult.trackExitPoint;

        BlockPos companionStart = trainRear.relative(offsetDir, separation);
        BlockPos companionEnd = trainFront.relative(offsetDir, separation);


        StraightTrackPlacer.place(level, companionStart, companionEnd, positiveDir);
    }

    /**
     * Places a station at the starting train area.
     *
     *
     * @return [entryPoint, exitPoint] of the station track in world coords, or null if placement failed
     */
    private BlockPos[] placeStartingStation(BlockPos initialTrackPos, Direction positiveDir,
                                       StartingTrainPlacer.TrainPlacementResult trainResult,
                                       @Nullable SelectedStation preSelectedStation) {
        BlockPos front = trainResult.trackExitPoint;
        BlockPos rear = trainResult.rearExitPoint != null ? trainResult.rearExitPoint : initialTrackPos;
        // Position station building at the rear end (near the Create station block)
        // so the train-length track extends past the building rather than creating
        // a gap between the Create station block and the station structure.
        BlockPos stationPosition = rear;

        // Use pre-selected station if available, otherwise select now
        SelectedStation station = preSelectedStation != null
                ? preSelectedStation
                : StationSchematicCache.selectStation(level, initialTrackPos, new Random(level.getSeed()));
        if (station == null) {
            LOGGER.warn("[ORCHESTRATOR] No station available - skipping starting station");
            return null;
        }

        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();
        // Lower station position by trackY so the station bottom sits at ground level
        // while the station's track aligns with the elevated initial track
        stationPosition = stationPosition.below(validation.trackY);
        Direction schematicTrackDir = validation.trackDirection;
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(schematicTrackDir, positiveDir);
        BlockPos placementPos = StationPlacementGeometry.calculateStationPlacementPosition(
                stationPosition, rotation, validation, schematic, false);

        SchematicPlacer.SchematicPlacementResult schematicResult = SchematicPlacer.place(
                level, schematic, validation, placementPos, rotation,
                RailwaysUntoldConfig.getDefault(), true, station.loot());

        if (!schematicResult.success) {
            LOGGER.warn("[ORCHESTRATOR] Failed to place starting station schematic: {}", schematicResult.failureReason);
            return null;
        }


        // Place bridge support (decking + pillars) if station is raised over water
        placeStartingStationBridgeSupport(rear, front, positiveDir);

        // No need to place straight track here - the starting train now places straight track
        // blocks at every position (instead of a bezier), so the track is already suitable
        // for reassembly and station block scanning.

        // Register station bounds
        BlockPos approachPos = rear.relative(positiveDir.getOpposite(), 5);
        BlockPos entryPoint, exitPoint;
        if (approachPos.distSqr(schematicResult.trackStart) < approachPos.distSqr(schematicResult.trackEnd)) {
            entryPoint = schematicResult.trackStart;
            exitPoint = schematicResult.trackEnd;
        } else {
            entryPoint = schematicResult.trackEnd;
            exitPoint = schematicResult.trackStart;
        }
        StationBoundTracker.registerStationBounds(level, placementPos, entryPoint, exitPoint, station);

        // Defer the Create station blocks until the track graph is ready.
        BlockPos rearTrackTarget = entryPoint.relative(positiveDir);
        BlockPos frontTrackTarget = exitPoint;
        int perpSize = schematicTrackDir == Direction.SOUTH || schematicTrackDir == Direction.NORTH
                ? schematic.getWidth() : schematic.getLength();
        int perpOffset = validation.trackPerpOffset;

        // Generate a shared station name from the midpoint
        BlockPos midpoint = new BlockPos(
                (rear.getX() + front.getX()) / 2,
                (rear.getY() + front.getY()) / 2,
                (rear.getZ() + front.getZ()) / 2);
        String sharedStationName = CreateStationPlacer.generateStationName(level, midpoint);

        // Wait for graph at the original rear position (where the graph is established),
        // not the shifted rearTrackTarget which may not have propagated yet.
        TrackGraphReadyListener.waitForGraphReady(level, rear, positiveDir, () -> {
            // Rear station block - arrow points inward (positive direction, toward front)
            CreateStationPlacer.placeStationOppositeBuilding(
                    level, rearTrackTarget, positiveDir, perpOffset, perpSize,
                    true, sharedStationName);

            // Front station block - arrow points inward (negative direction, toward rear)
            CreateStationPlacer.placeStationOppositeBuilding(
                    level, frontTrackTarget, positiveDir, perpOffset, perpSize,
                    false, sharedStationName);
        });

        return new BlockPos[]{entryPoint, exitPoint};
    }

    /**
     * Places bridge support (decking + pillars) under the starting station if it's raised over water.
     */
    private void placeStartingStationBridgeSupport(BlockPos trackStart, BlockPos trackEnd, Direction trackDir) {
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
}
