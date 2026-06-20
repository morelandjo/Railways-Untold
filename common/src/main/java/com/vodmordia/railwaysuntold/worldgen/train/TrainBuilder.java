package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.CarriageContraptionData;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Builds a Create Train programmatically from schematic data.
 * Mirrors Create's StationBlockEntity.assemble() pipeline:
 * 1. Compute global bogey locations (normalized so first bogey = 0)
 * 2. Walk the track graph from station node to place TravellingPoints at exact offsets
 * 3. Group bogeys into carriages, create Train, attach contraptions
 */
public class TrainBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static class BuildResult {
        public final boolean success;
        public final String failureReason;

        private BuildResult(boolean success, String failureReason) {
            this.success = success;
            this.failureReason = failureReason;
        }

        public static BuildResult success() {
            return new BuildResult(true, null);
        }

        public static BuildResult failure(String reason) {
            return new BuildResult(false, reason);
        }
    }

    // Main pipeline

    /**
     * Builds a train from validated schematic data and positions it on the track.
     * Mirrors Create's StationBlockEntity.assemble() pipeline.
     *
     * @param level              The server level
     * @param validation         The validation result from TrainSchematicValidator
     * @param trackPos           The position of the track (station location)
     * @param assemblyDirection  The direction the train extends from the station
     * @param ownerUUID          Optional owner UUID (can be null)
     * @param lootConfig         Loot configuration for container blocks
     * @return BuildResult indicating success or failure
     */
    public static BuildResult buildTrain(ServerLevel level, ValidationResult validation,
                                         BlockPos trackPos, Direction assemblyDirection, @Nullable UUID ownerUUID,
                                         LootConfig lootConfig) {

        if (!validation.valid) {
            return BuildResult.failure("Invalid schematic validation: " + validation.errorMessage);
        }

        // Step 1: Find track graph and station node
        Vec3 trackCenter = CreateTrainUtils.getTrackNodePosition(trackPos, assemblyDirection);
        TrackGraph graph = CreateTrainUtils.getGraph(level, trackCenter);
        if (graph == null) {
            return BuildResult.failure("Track graph not found at position");
        }

        TrackNode stationNode = CreateTrainUtils.locateNode(graph, trackCenter, level);
        if (stationNode == null) {
            return BuildResult.failure("Track node not found at position");
        }


        // Virtual assembly path: derive ALL bogey layout from AssemblyResult
        if (validation.schematicNbt != null) {
            return buildTrainViaVirtualAssembly(level, validation, trackPos,
                    assemblyDirection, ownerUUID, graph, lootConfig);
        }

        // Step 2: Determine if the train needs to be flipped end-to-end.
        // The "front" is the carriage with controls. If it's at the far end of the
        // schematic, reverse ALL input data so the engine ends up nearest the station.
        List<BogeyInfo> allBogeys = new ArrayList<>(validation.bogeyLayout.bogeys());
        List<Integer> bogeysPerCarriage = new ArrayList<>(validation.bogeyLayout.bogeysPerCarriage());
        List<Integer> interCarriageSpacings = new ArrayList<>(validation.bogeyLayout.interCarriageSpacings());
        List<CarriageContraptionData> contraptionOrder = new ArrayList<>(validation.carriageContraptions);
        boolean trainReversed = false;


        TrainOrientationAnalyzer.OrientationResult orientation = TrainOrientationAnalyzer.analyzeTrainOrientation(
                validation.carriageContraptions, validation.assemblyDirection);

        // Two separate reversal concerns:
        int cwSteps = SchematicRotator.getClockwiseSteps(validation.assemblyDirection, assemblyDirection);

        if (orientation.shouldReverse()) {

            // Reverse at CARRIAGE level AND reverse within-carriage bogey order.
            // After reversal the train direction flips, so what was the "first" (leading)
            // bogey of each carriage becomes the "last" (trailing), and vice versa.
            // We also swap the relativePos values so positions stay ascending (first < second).
            // This ensures the correct bogey faces the adjacent cars for coupling visibility
            // (e.g. a large bogey faces the freight cars, not an invisible one).
            List<BogeyInfo> reversedBogeys = new ArrayList<>();
            int idx = allBogeys.size();
            for (int c = bogeysPerCarriage.size() - 1; c >= 0; c--) {
                int count = bogeysPerCarriage.get(c);
                idx -= count;

                // Collect original relativePos values for this carriage
                List<BlockPos> originalPositions = new ArrayList<>();
                for (int b = 0; b < count; b++) {
                    originalPositions.add(allBogeys.get(idx + b).relativePos);
                }

                // Add bogeys in reversed within-carriage order, assigning the original
                // positions in forward order so ascending position invariant is maintained
                for (int b = count - 1; b >= 0; b--) {
                    BogeyInfo original = allBogeys.get(idx + b);
                    BlockPos newPos = originalPositions.get(count - 1 - b);
                    reversedBogeys.add(new BogeyInfo(newPos, original.bogeyType,
                            original.bogeyData, original.upsideDown));
                }
            }
            allBogeys = reversedBogeys;

            Collections.reverse(bogeysPerCarriage);
            Collections.reverse(interCarriageSpacings);
            Collections.reverse(contraptionOrder);
            trainReversed = true;
        }

        // Note: rotation-induced visual flipping (headstocks facing inward) is handled
        // by mirrorContraptionAlongAxis in createContraptionFromNbt, not by swapping
        // contraption order.

        // Step 3: Compute global bogey locations (normalized, first bogey = 0)
        int[] bogeyLocations = BogeyLocationCalculator.computeBogeyLocations(allBogeys, validation.assemblyDirection,
                bogeysPerCarriage, interCarriageSpacings);
        if (bogeyLocations == null) {
            return BuildResult.failure("No bogey information in validation result");
        }


        // Step 4: Resolve default bogey type for gauge
        AbstractBogeyBlock<?> defaultBogeyType = RailwayModCompat.getDefaultBogeyBlockForGauge();
        if (defaultBogeyType == null) {
            return BuildResult.failure("Could not resolve bogey block for configured gauge");
        }

        // Step 5: Compute point offsets (mirrors Create's StationBlockEntity.assemble())
        List<Double> pointOffsets = new ArrayList<>();
        for (int i = 0; i < allBogeys.size(); i++) {
            AbstractBogeyBlock<?> bogeyType = resolveBogeyBlock(allBogeys.get(i));
            if (bogeyType == null) bogeyType = defaultBogeyType;
            double bogeySize = bogeyType.getWheelPointSpacing();
            pointOffsets.add((double) bogeyLocations[i] + 0.5 - bogeySize / 2);
            pointOffsets.add((double) bogeyLocations[i] + 0.5 + bogeySize / 2);
        }

        // Step 6: Create TravellingPoints by scanning in 0.5-block increments (Create's exact method)
        Vec3 assemblyDirectionVec = Vec3.atLowerCornerOf(assemblyDirection.getNormal());
        List<TravellingPoint> points = createTravellingPointsByScan(
                level, graph, trackCenter, pointOffsets, assemblyDirectionVec, bogeyLocations);
        if (points == null || points.size() != pointOffsets.size()) {
            return BuildResult.failure("Could not create all TravellingPoints - track may be too short");
        }

        // Step 7: Create CarriageBogeys from point pairs
        List<CarriageBogey> carriageBogeys = new ArrayList<>();
        for (int i = 0; i < allBogeys.size(); i++) {
            BogeyInfo bogeyInfo = allBogeys.get(i);

            AbstractBogeyBlock<?> bogeyType = resolveBogeyBlock(bogeyInfo);
            boolean usedSchematicBogey = (bogeyType != null);
            if (bogeyType == null) {
                bogeyType = defaultBogeyType;
            }

            CompoundTag bogeyData = bogeyInfo.bogeyData != null ? bogeyInfo.bogeyData.copy() : new CompoundTag();
            if (!usedSchematicBogey) {
                ResourceLocation styleId = RailwayModCompat.getDefaultBogeyStyleForGauge();
                if (styleId != null) {
                    bogeyData.putString("BogeyStyle", styleId.toString());
                }
            }

            // When the track direction differs from the schematic direction, the bogey's
            // forward orientation relative to the track changes. The rotation+mirror transform
            // reverses the bogey's facing, so flip IsForwards to compensate.
            if (cwSteps != 0 && bogeyData.contains("IsForwards")) {
                bogeyData.putBoolean("IsForwards", !bogeyData.getBoolean("IsForwards"));
            }

            int pointIndex = i * 2;
            CarriageBogey bogey = new CarriageBogey(bogeyType, bogeyInfo.upsideDown, bogeyData,
                    points.get(pointIndex), points.get(pointIndex + 1));
            carriageBogeys.add(bogey);
        }

        // Step 8: Group bogeys into carriages
        List<Carriage> carriages = new ArrayList<>();
        int bogeyOffset = 0;
        for (int c = 0; c < bogeysPerCarriage.size(); c++) {
            int count = bogeysPerCarriage.get(c);

            CarriageBogey firstBogey = carriageBogeys.get(bogeyOffset);
            CarriageBogey secondBogey = (count >= 2) ? carriageBogeys.get(bogeyOffset + 1) : null;

            int internalSpacing = 0;
            if (count >= 2) {
                internalSpacing = bogeyLocations[bogeyOffset + 1] - bogeyLocations[bogeyOffset];
            }

            carriages.add(new Carriage(firstBogey, secondBogey, internalSpacing));
            bogeyOffset += count;
        }

        // Step 9: Assemble and register the train
        return assembleAndRegisterTrain(level, validation, graph, carriages,
                interCarriageSpacings, contraptionOrder, trainReversed,
                assemblyDirection, ownerUUID);
    }

    // Virtual assembly pipeline

    /**
     * Builds a train using the virtual world assembly pipeline.
     * Derives ALL bogey layout from AssemblyResult rather than ValidationResult's
     * schematic-space entity positions. This ensures bogey positions, spacings,
     * and carriage grouping match what Create's native assemble() computed.
     */
    private static BuildResult buildTrainViaVirtualAssembly(
            ServerLevel level, ValidationResult validation, BlockPos trackPos,
            Direction assemblyDirection, @Nullable UUID ownerUUID,
            TrackGraph graph, LootConfig lootConfig) {


        // 1. Assemble via virtual world
        VirtualTrainAssembler.AssemblyResult asmResult =
                VirtualTrainAssembler.assembleFromSchematic(
                        level, validation.schematicNbt, assemblyDirection, lootConfig);

        if (!asmResult.success) {
            return BuildResult.failure("Virtual assembly failed: " + asmResult.errorMessage);
        }

        List<VirtualTrainAssembler.BogeyInWorld> bogeys = asmResult.bogeys;
        List<List<Integer>> carriageGroups = asmResult.carriageGroups;
        List<CarriageContraption> contraptions = new ArrayList<>(asmResult.contraptions);

        int cwSteps = SchematicRotator.getClockwiseSteps(validation.assemblyDirection, assemblyDirection);

        // Reverse train so engine is nearest the station
        TrainOrientationAnalyzer.OrientationResult orientation = TrainOrientationAnalyzer.analyzeTrainOrientation(
                validation.carriageContraptions, validation.assemblyDirection);

        boolean trainReversed = false;
        if (orientation.shouldReverse() && carriageGroups.size() > 1) {

            List<VirtualTrainAssembler.BogeyInWorld> newBogeys = new ArrayList<>();
            List<List<Integer>> newGroups = new ArrayList<>();
            List<CarriageContraption> newContraptions = new ArrayList<>();
            int idx = 0;
            for (int g = carriageGroups.size() - 1; g >= 0; g--) {
                List<Integer> oldGroup = carriageGroups.get(g);
                List<Integer> newGroup = new ArrayList<>();
                // Reverse within-carriage bogey order to match new leading/trailing positions
                for (int b = oldGroup.size() - 1; b >= 0; b--) {
                    newBogeys.add(bogeys.get(oldGroup.get(b)));
                    newGroup.add(idx++);
                }
                newGroups.add(newGroup);
                newContraptions.add(contraptions.get(g));
            }
            bogeys = newBogeys;
            carriageGroups = newGroups;
            contraptions = newContraptions;
            trainReversed = true;
        }

        // 3. Compute bogeyLocations like Create does: offsets from station along the track.
        // Within-carriage spacing comes from virtual world bogey positions (reliable,
        // same axis as track AD). Inter-carriage spacing comes from the validator
        // (entity-to-entity distance minus within-carriage span).
        int[] withinCarriageSpacing = new int[carriageGroups.size()];
        for (int c = 0; c < carriageGroups.size(); c++) {
            List<Integer> group = carriageGroups.get(c);
            if (group.size() >= 2) {
                int first = DirectionUtil.getSignedPositionAlongAxis(
                        bogeys.get(group.get(0)).worldPos, assemblyDirection);
                int last = DirectionUtil.getSignedPositionAlongAxis(
                        bogeys.get(group.get(group.size() - 1)).worldPos, assemblyDirection);
                withinCarriageSpacing[c] = Math.abs(last - first);
            }
        }

        List<Integer> interCarriageSpacings = new ArrayList<>(validation.bogeyLayout.interCarriageSpacings());
        if (trainReversed) {
            Collections.reverse(interCarriageSpacings);
        }

        // Build bogey locations: walk from station (offset 0) outward.
        // Each carriage's first bogey is at the current offset, second bogey (if any)
        // is withinSpacing further. Then advance by the inter-carriage gap.
        int[] bogeyLocations = new int[bogeys.size()];
        int offset = 0;
        for (int c = 0; c < carriageGroups.size(); c++) {
            List<Integer> group = carriageGroups.get(c);
            bogeyLocations[group.get(0)] = offset;
            if (group.size() >= 2) {
                bogeyLocations[group.get(group.size() - 1)] = offset + withinCarriageSpacing[c];
                offset += withinCarriageSpacing[c];
            }
            if (c < interCarriageSpacings.size()) {
                offset += interCarriageSpacings.get(c);
            }
        }


        // 4. Resolve default bogey type
        AbstractBogeyBlock<?> defaultBogeyType = RailwayModCompat.getDefaultBogeyBlockForGauge();
        if (defaultBogeyType == null) {
            return BuildResult.failure("Could not resolve bogey block for configured gauge");
        }

        // 5. Compute point offsets (mirrors Create's StationBlockEntity.assemble())
        List<Double> pointOffsets = new ArrayList<>();
        for (int i = 0; i < bogeys.size(); i++) {
            AbstractBogeyBlock<?> bogeyType = CreateTrainUtils.getBogeyBlockByName(bogeys.get(i).bogeyType);
            if (bogeyType == null) bogeyType = defaultBogeyType;
            double wheelSpacing = bogeyType.getWheelPointSpacing();
            pointOffsets.add((double) bogeyLocations[i] + 0.5 - wheelSpacing / 2);
            pointOffsets.add((double) bogeyLocations[i] + 0.5 + wheelSpacing / 2);
        }

        // 6. Create TravellingPoints
        Vec3 trackCenter = CreateTrainUtils.getTrackNodePosition(trackPos, assemblyDirection);
        Vec3 directionVec = Vec3.atLowerCornerOf(assemblyDirection.getNormal());
        List<TravellingPoint> points = createTravellingPointsByScan(
                level, graph, trackCenter, pointOffsets, directionVec, bogeyLocations);
        if (points == null || points.size() != pointOffsets.size()) {
            return BuildResult.failure("Could not create all TravellingPoints - track may be too short");
        }

        // 7. Create CarriageBogeys
        List<CarriageBogey> carriageBogeys = new ArrayList<>();
        for (int i = 0; i < bogeys.size(); i++) {
            VirtualTrainAssembler.BogeyInWorld bogey = bogeys.get(i);
            AbstractBogeyBlock<?> bogeyType = CreateTrainUtils.getBogeyBlockByName(bogey.bogeyType);
            if (bogeyType == null) bogeyType = defaultBogeyType;
            CompoundTag bogeyData = bogey.bogeyData != null ? bogey.bogeyData.copy() : new CompoundTag();

            if (cwSteps != 0 && bogeyData.contains("IsForwards")) {
                bogeyData.putBoolean("IsForwards", !bogeyData.getBoolean("IsForwards"));
            }

            int pointIndex = i * 2;
            carriageBogeys.add(new CarriageBogey(bogeyType, bogey.upsideDown, bogeyData,
                    points.get(pointIndex), points.get(pointIndex + 1)));
        }

        // 8. Group bogeys into Carriages
        List<Carriage> carriages = new ArrayList<>();
        for (int c = 0; c < carriageGroups.size(); c++) {
            List<Integer> group = carriageGroups.get(c);
            CarriageBogey first = carriageBogeys.get(group.get(0));
            CarriageBogey second = group.size() >= 2 ? carriageBogeys.get(group.get(1)) : null;
            int internalSpacing = group.size() >= 2
                    ? bogeyLocations[group.get(1)] - bogeyLocations[group.get(0)] : 0;
            carriages.add(new Carriage(first, second, internalSpacing));
        }

        // 9. Compute inter-carriage spacings from bogeyLocations
        List<Integer> trainSpacings = new ArrayList<>();
        for (int c = 0; c < carriageGroups.size() - 1; c++) {
            List<Integer> thisGroup = carriageGroups.get(c);
            List<Integer> nextGroup = carriageGroups.get(c + 1);
            int lastOfThis = thisGroup.get(thisGroup.size() - 1);
            int firstOfNext = nextGroup.get(0);
            trainSpacings.add(bogeyLocations[firstOfNext] - bogeyLocations[lastOfThis]);
        }


        // 10. Create and register Train
        boolean doubleEnded = asmResult.hasForwardControls && asmResult.hasBackwardControls;
        Train train = new Train(UUID.randomUUID(), ownerUUID, graph,
                carriages, trainSpacings, doubleEnded);
        train.name = Component.translatable("entity.railwaysuntold.starting_train");

        if (contraptions.size() != carriages.size()) {
            return BuildResult.failure("Contraption/carriage count mismatch: "
                    + contraptions.size() + " vs " + carriages.size());
        }

        try {
            for (int i = 0; i < carriages.size(); i++) {
                CarriageContraption cc = contraptions.get(i);

                // Inject proper face-to-face superglue into the contraption.
                // Create's assemble() stores SuperGlueEntity bounds from the virtual
                // world in world-space - replace with contraption-relative AABBs.
                ContraptionSuperglue.injectSuperglueIfEmpty(cc);

                // Transform copycat block entity data (Material, material_data) to match
                // the block state rotation. The virtual assembler rotates block states
                // via StructureTransform but block entity data is stored as raw NBT,
                // so material face keys and material BlockStates need manual rotation.
                if (cwSteps != 0) {
                    CopycatContraptionHandler.transformBlockEntities(cc, ContraptionPostProcessor.getContraptionUpdateTags(cc), cwSteps, level);
                }

                // Ensure block entity nbt has correct id for BlockEntity.loadStatic()
                ContraptionPostProcessor.ensureBlockEntityIds(cc);

                // Populate updateTags with full block entity data so that both
                // the Data and UpdateTag fields in the serialized contraption carry
                // material_data.
                ContraptionPostProcessor.populateUpdateTags(cc);

                // Diagnostic: verify copycat block material data after full pipeline
                CopycatContraptionHandler.logMaterialDiagnostics(cc, ContraptionPostProcessor.getContraptionUpdateTags(cc), i);

                carriages.get(i).setContraption(level, cc);
            }
        } catch (RuntimeException e) {
            return BuildResult.failure("Error setting virtual contraption: " + e.getMessage());
        }

        train.collectInitiallyOccupiedSignalBlocks();
        CreateTrainUtils.registerAndSyncTrain(train);

        return BuildResult.success();
    }


    // TravellingPoint creation (graph walk from station node)

    /**
     * Creates TravellingPoints by scanning in 0.5-block increments along the assembly direction.
     */
    @Nullable
    private static List<TravellingPoint> createTravellingPointsByScan(
            ServerLevel level, TrackGraph graph, Vec3 stationPos,
            List<Double> pointOffsets, Vec3 directionVec, int[] bogeyLocations) {

        List<TravellingPoint> points = new ArrayList<>();
        TrackNode secondNode = null;

        // Start scanning from the station node position
        com.simibubi.create.content.trains.graph.TrackNodeLocation location =
                new com.simibubi.create.content.trains.graph.TrackNodeLocation(stationPos).in(level.dimension());

        int maxBogeyLoc = bogeyLocations.length > 0 ? bogeyLocations[bogeyLocations.length - 1] : 0;
        int maxIterations = (maxBogeyLoc + 20) * 2 + 40;

        for (int j = 0; j < maxIterations; j++) {
            double i = j / 2.0;
            if (points.size() == pointOffsets.size()) break;

            com.simibubi.create.content.trains.graph.TrackNodeLocation currentLocation = location;
            location = new com.simibubi.create.content.trains.graph.TrackNodeLocation(
                    location.getLocation().add(directionVec.scale(0.5))).in(location.dimension);

            TrackNode node = graph.locateNode(currentLocation);
            if (node == null) continue;

            Map<TrackNode, TrackEdge> connectionsFromNode = graph.getConnectionsFrom(node);

            for (int pointIndex = points.size(); pointIndex < pointOffsets.size(); pointIndex++) {
                double offset = pointOffsets.get(pointIndex);
                if (offset > i) break;
                double positionOnEdge = i - offset;

                // Find secondNode: edge with direction opposite to assembly direction (dot=-1)
                // Also re-search if the cached secondNode is not connected to the current node
                // (can happen when a bezier skips over intermediate track block nodes)
                if (secondNode == null || connectionsFromNode.get(secondNode) == null) {
                    secondNode = null;
                    for (Map.Entry<TrackNode, TrackEdge> entry : connectionsFromNode.entrySet()) {
                        TrackEdge edge = entry.getValue();
                        if (edge.isTurn()) continue;
                        Vec3 edgeDirection = edge.getDirection(true);
                        if (Mth.equal(edgeDirection.normalize().dot(directionVec), -1d)) {
                            secondNode = entry.getKey();
                        }
                    }
                }

                if (secondNode == null) {
                    LOGGER.error("[TRAIN-BUILD] No valid secondNode found at scan i={}", i);
                    return null;
                }

                TrackEdge edge = connectionsFromNode.get(secondNode);
                if (edge == null) {
                    LOGGER.error("[TRAIN-BUILD] Missing edge from node {} to secondNode {} at scan i={}",
                            node.getLocation(), secondNode.getLocation(), i);
                    return null;
                }

                points.add(new TravellingPoint(node, secondNode, edge, positionOnEdge, false));
            }

            secondNode = node;
        }

        if (points.size() != pointOffsets.size()) {
            LOGGER.error("[TRAIN-BUILD] Not all points created: {}/{}", points.size(), pointOffsets.size());
            return null;
        }

        return points;
    }


    // Train assembly and registration

    private static BuildResult assembleAndRegisterTrain(
            ServerLevel level, ValidationResult validation, TrackGraph graph,
            List<Carriage> carriages, List<Integer> interCarriageSpacings,
            List<CarriageContraptionData> contraptionOrder, boolean trainReversed,
            Direction assemblyDirection, @Nullable UUID ownerUUID) {

        boolean doubleEnded = validation.controls.forward() && validation.controls.backward();

        Train train = new Train(
                UUID.randomUUID(),
                ownerUUID,
                graph,
                carriages,
                interCarriageSpacings,
                doubleEnded
        );
        train.name = Component.translatable("entity.railwaysuntold.starting_train");

        AbstractBogeyBlock<?> gaugeBogeyType = RailwayModCompat.getDefaultBogeyBlockForGauge();

        try {
            for (int i = 0; i < carriages.size(); i++) {
                Carriage carriage = carriages.get(i);

                CarriageContraptionData cData = i < contraptionOrder.size()
                        ? contraptionOrder.get(i)
                        : contraptionOrder.get(0);

                CompoundTag nbt = cData.nbt().copy();


                if (gaugeBogeyType != null) {
                    ContraptionNbtPreparer.replaceBogeyBlocksInContraptionNbt(nbt, gaugeBogeyType);
                }

                CarriageContraption contraption = createContraptionFromNbt(
                        level, nbt, assemblyDirection, trainReversed, cData.glueBoxes());
                if (contraption == null) {
                    return BuildResult.failure("Failed to create contraption for carriage " + i);
                }

                carriage.setContraption(level, contraption);
            }
        } catch (RuntimeException e) {
            return BuildResult.failure("Error creating contraption: " + e.getMessage());
        }

        train.collectInitiallyOccupiedSignalBlocks();
        CreateTrainUtils.registerAndSyncTrain(train);

        return BuildResult.success();
    }

    // Contraption creation from NBT

    /**
     * Creates a CarriageContraption from saved NBT data.
     * Rotates block positions in NBT to match the actual track direction,
     * then updates the AssemblyDirection tag so the rendering system is consistent.
     */
    @Nullable
    private static CarriageContraption createContraptionFromNbt(ServerLevel level, CompoundTag contraptionNbt,
                                                                 Direction assemblyDirection, boolean trainReversed,
                                                                 List<AABB> glueBoxes) {
        try {
            ContraptionNbtPreparer.convertIntArrayPosToCompoundTag(contraptionNbt);
            CopycatContraptionHandler.convertMaterialData(contraptionNbt);

            // Rotation is a simple direction mismatch: rotate the whole train from
            // schematic AD to track AD. For 180° rotations, the carriage order must also
            // be reversed in buildTrain (handled before this method is called) so that
            // each end car's contraption ends up at the correct end after rotation.
            Direction schematicAD = ContraptionNbtPreparer.extractAssemblyDirectionFromNbt(contraptionNbt);
            int cwSteps = SchematicRotator.getClockwiseSteps(schematicAD, assemblyDirection);

            if (cwSteps != 0) {
                ContraptionNbtRotator.rotateContraptionNbt(contraptionNbt, cwSteps);

                // After rotation, the block positions flip to the opposite side of the bogey,
                // causing headstocks/buffers to face inward. Mirror along the track AD axis
                // to put positions back on the correct side while also fixing directional
                // block facings (headstocks face outward after mirror).
                ContraptionNbtMirror.mirrorContraptionAlongAxis(contraptionNbt, assemblyDirection);
            }
            if (schematicAD != assemblyDirection || trainReversed) {
                contraptionNbt.putString(ContraptionNbtKeys.ASSEMBLY_DIRECTION,
                        assemblyDirection.name());
            }

            // Shift leading bogey to origin AFTER rotation so the correct bogey ends up at (0,0,0).
            // Skip when mirrored (cwSteps != 0): the mirror preserves the original bogey at
            // origin, but creates a second bogey at a negative position that the shift would
            // incorrectly identify as the new "leading" bogey.
            if (cwSteps == 0) {
                ContraptionNbtPreparer.shiftContraptionToLeadingBogey(contraptionNbt);
            }

            // Schematics store world-space anchor from capture; block positions are already relative
            contraptionNbt.put(ContraptionNbtKeys.ANCHOR, net.minecraft.nbt.NbtUtils.writeBlockPos(BlockPos.ZERO));

            // Must write superglue BEFORE readNBT so Create deserializes it normally
            if (!contraptionNbt.contains("Superglue", Tag.TAG_LIST)
                    || contraptionNbt.getList("Superglue", Tag.TAG_COMPOUND).isEmpty()) {
                List<AABB> glueToWrite = (glueBoxes != null && !glueBoxes.isEmpty())
                        ? glueBoxes : ContraptionSuperglue.generateGlueBoxesFromNbt(contraptionNbt);
                if (!glueToWrite.isEmpty()) {
                    ListTag superglueNbt = new ListTag();
                    for (AABB box : glueToWrite) {
                        CompoundTag glueTag = new CompoundTag();
                        glueTag.put(ContraptionNbtKeys.FROM, ContraptionSuperglue.writeVec3(box.minX, box.minY, box.minZ));
                        glueTag.put(ContraptionNbtKeys.TO, ContraptionSuperglue.writeVec3(box.maxX, box.maxY, box.maxZ));
                        superglueNbt.add(glueTag);
                    }
                    contraptionNbt.put("Superglue", superglueNbt);
                    LOGGER.debug("[CONTRAPTION] Wrote {} superglue entries to NBT", superglueNbt.size());
                }
            }

            CarriageContraption contraption = new CarriageContraption(assemblyDirection);
            contraption.readNBT(level, contraptionNbt, false);

            LOGGER.debug("[CONTRAPTION] Contraption blocks count: {}", contraption.getBlocks().size());

            ContraptionPostProcessor.populateInteractorsAndControls(contraption);
            ContraptionPostProcessor.recalculateConductorSeats(contraption);
            ContraptionPostProcessor.populateUpdateTags(contraption);

            return contraption;
        } catch (RuntimeException e) {
            LOGGER.error("[CONTRAPTION] Failed to create: {}", e.getMessage(), e);
            return null;
        }
    }

    @Nullable
    private static AbstractBogeyBlock<?> resolveBogeyBlock(BogeyInfo bogeyInfo) {
        if (bogeyInfo.bogeyType == null || bogeyInfo.bogeyType.isEmpty()) {
            return null;
        }
        return CreateTrainUtils.getBogeyBlockByName(bogeyInfo.bogeyType);
    }
}
