package com.vodmordia.railwaysuntold.worldgen.placement.handler;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.AppearanceTracker;
import com.vodmordia.railwaysuntold.datapack.EventDefinitionLoader;
import com.vodmordia.railwaysuntold.datapack.TriggerContext;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateStationPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.JigsawEventPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * Executor for EVENT placement decisions.
 * Places a random event schematic along the track and continues generation through it.
 */
public class EventPlacementExecutor extends AbstractPlacementExecutor {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Blocks the head travels before re-attempting an event after a failed placement.
    private static final int EVENT_RETRY_COOLDOWN_BLOCKS = 24;

    @Override
    public PlacementDecision.Type getType() {
        return PlacementDecision.Type.EVENT;
    }

    @Override
    public HandlerResult handle(PlacementContext ctx) {
        var biome = ctx.level().getBiome(ctx.currentPos());
        TriggerContext triggerCtx = TriggerContext.create(ctx.level(), ctx.currentPos(), ctx.headManager());
        EventDefinitionLoader.ValidatedEventEntry event = EventDefinitionLoader.INSTANCE.getWeightedRandomEvent(ctx.random(), biome, triggerCtx);
        if (event == null) {
            LOGGER.warn("[EVENT-HANDLER] No valid events available");
            return HandlerResult.failed("No valid events available");
        }

        return switch (event) {
            case EventDefinitionLoader.ValidatedEventEntry.NbtEntry nbt -> handleNbtEvent(ctx, nbt);
            case EventDefinitionLoader.ValidatedEventEntry.JigsawEntry jigsaw -> handleJigsawEvent(ctx, jigsaw);
        };
    }

    private HandlerResult handleNbtEvent(PlacementContext ctx, EventDefinitionLoader.ValidatedEventEntry.NbtEntry event) {
        NbtSchematicLoader.LoadedSchematic schematic = event.schematic();
        SchematicValidator.SchematicValidationResult validation = event.validation();

        Direction schematicTrackDir = validation.trackDirection;
        Direction targetDir = ctx.expandDir();
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(schematicTrackDir, targetDir);

        BlockPos schematicAnchor = ctx.currentPos().relative(ctx.expandDir(), 1);
        BlockPos placementPos = calculatePlacementPosition(schematicAnchor, rotation, validation, schematic);
        placementPos = adjustForTravelDirection(placementPos, rotation, validation, schematic, ctx.expandDir(), schematicAnchor);

        SchematicPlacer.SchematicPlacementResult result = SchematicPlacer.place(
                ctx.level(), schematic, validation, placementPos, rotation,
                RailwaysUntoldConfig.getDefault(), false, event.definition().loot()
        );

        if (!result.success) {
            if (result.failureReason != null && result.failureReason.contains("chunks loaded")) {
                return deferForBoundingBox(placementPos,
                        placementPos.offset(schematic.getWidth(), schematic.getHeight(), schematic.getLength()),
                        "EventPlacement");
            }
            LOGGER.warn("[EVENT-HANDLER] Failed to place NBT event '{}': {}", event.definition().id(), result.failureReason);
            return HandlerResult.failed("Event placement failed: " + result.failureReason);
        }

        AppearanceTracker.get(ctx.level()).increment(event.definition().id());
        renameDefaultStationBlocks(ctx.level(), schematic, placementPos, rotation);

        return connectTrackAndUpdateHead(ctx, event.definition(), result.trackStart, result.trackEnd,
                result.trackDirection, result.isDeadEnd, result.hasStart, result.startDirection, placementPos);
    }

    private HandlerResult handleJigsawEvent(PlacementContext ctx, EventDefinitionLoader.ValidatedEventEntry.JigsawEntry event) {
        BlockPos anchor = ctx.currentPos().relative(ctx.expandDir(), 1);

        JigsawEventPlacer.JigsawPlacementResult result = JigsawEventPlacer.generateAndPlace(
                ctx.level(), event.definition().structure(), anchor, ctx.expandDir()
        );

        if (!result.success) {
            LOGGER.warn("[EVENT-HANDLER] Failed to place jigsaw event '{}': {}", event.definition().id(), result.failureReason);
            // Retry soon at a fresh anchor instead of re-attempting this same one every
            // tick (the rotation seeds are anchor-derived, so a wedged anchor would
            // otherwise retry forever) - but without paying a full separation interval.
            ctx.head().deferEventRetry(ctx.config().EVENT_SEPARATION_MIN_DISTANCE, EVENT_RETRY_COOLDOWN_BLOCKS);
            return HandlerResult.failed("Jigsaw event placement failed: " + result.failureReason);
        }

        AppearanceTracker.get(ctx.level()).increment(event.definition().id());

        return connectTrackAndUpdateHead(ctx, event.definition(), result.trackStart, result.trackEnd,
                result.trackDirection, result.isDeadEnd, result.hasStart, result.startDirection, anchor);
    }

    /**
     * Shared logic for connecting track endpoints and updating head state after placement.
     */
    private HandlerResult connectTrackAndUpdateHead(PlacementContext ctx,
                                                     com.vodmordia.railwaysuntold.datapack.EventDefinition definition,
                                                     BlockPos trackStart, BlockPos trackEnd,
                                                     Direction trackDirection,
                                                     boolean isDeadEnd, boolean hasStart,
                                                     Direction startDirection, BlockPos placementPos) {
        Endpoints ends = nearAndFar(ctx.currentPos(), trackStart, trackEnd);

        if (isDeadEnd) {
            // Head enters at `near` and terminates at `far`. No track on `far` - it's
            // the visual dead end, not a connectable stub.
            placeEdgeTrack(ctx.level(), ends.near(), trackDirection);
            connectStraight(ctx.level(), ends.near(), ends.far(), trackDirection);
            moveHeadTo(ctx, ends.far());
            ctx.head().markComplete();
            return HandlerResult.succeeded();
        }

        if (!hasStart && !endpointsAxisAligned(ends.near(), ends.far(), ctx.expandDir())) {
            LOGGER.warn("[EVENT-HANDLER] Rejecting event '{}' at {}: near={} far={} not axis-aligned along {} " +
                    "(dx={}, dy={}, dz={}) - would introduce a lateral/vertical shift",
                    definition.id(), placementPos, ends.near(), ends.far(), ctx.expandDir(),
                    ends.far().getX() - ends.near().getX(),
                    ends.far().getY() - ends.near().getY(),
                    ends.far().getZ() - ends.near().getZ());
            return HandlerResult.failed("Event endpoints not axis-aligned");
        }

        placeEdgeTrack(ctx.level(), ends.near(), trackDirection);
        placeEdgeTrack(ctx.level(), ends.far(), trackDirection);
        connectStraight(ctx.level(), ends.near(), ends.far(), trackDirection);

        if (hasStart) {
            // Spawn a fresh head at `far`; current head terminates at `near`.
            ctx.headManager().createNewStartingHeads(ends.far(), startDirection, ctx.level(), ctx.config(), null);
            moveHeadTo(ctx, ends.near());
            ctx.head().markComplete();
            return HandlerResult.succeeded();
        }

        // Normal event: head exits at `far` and keeps going.
        moveHeadTo(ctx, ends.far());
        ctx.head().onDirectionChange();
        return HandlerResult.succeeded();
    }

    private static boolean endpointsAxisAligned(BlockPos near, BlockPos far, Direction expandDir) {
        if (near.getY() != far.getY()) return false;
        boolean ew = expandDir.getAxis() == Direction.Axis.X;
        return ew ? near.getZ() == far.getZ() : near.getX() == far.getX();
    }

    /** Pair of track endpoints, sorted by distance from a reference point. */
    private record Endpoints(BlockPos near, BlockPos far) {}

    private static Endpoints nearAndFar(BlockPos from, BlockPos a, BlockPos b) {
        return from.distSqr(a) < from.distSqr(b)
                ? new Endpoints(a, b)
                : new Endpoints(b, a);
    }

    private static void connectStraight(ServerLevel level, BlockPos from, BlockPos to, Direction trackDirection) {
        com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector
                .connectStraightDirect(level, from, to, trackDirection);
    }

    /**
     * Common final step before markComplete / onDirectionChange: teleport the head
     * to newPosition, align it to the travel direction, and reset the event
     * counter so the next event can fire at the configured spacing.
     */
    private static void moveHeadTo(PlacementContext ctx, BlockPos newPosition) {
        ctx.head().setPosition(newPosition);
        ctx.head().setDirection(ctx.expandDir());
        ctx.head().resetEventCounter();
        ctx.head().markHasPlacedSegments();
    }

    /**
     * Renames any Create station blocks in the placed schematic that have a default name.
     */
    private void renameDefaultStationBlocks(ServerLevel level, NbtSchematicLoader.LoadedSchematic schematic,
                                             BlockPos placementPos, Rotation rotation) {
        if (!CreateStationPlacer.isAvailable()) return;

        for (java.util.Map.Entry<BlockPos, net.minecraft.nbt.CompoundTag> entry : schematic.getBlockEntities().entrySet()) {
            BlockPos localPos = entry.getKey();
            BlockPos worldPos = RotationHelper.transformPosition(localPos, schematic.getSize(), placementPos, rotation);

            if (CreateStationPlacer.isStationBlock(level.getBlockState(worldPos))) {
                CreateStationPlacer.renameIfDefault(level, worldPos);
            }
        }
    }

    /**
     * Ensures the schematic extends in the travel direction from the anchor.
     */
    private BlockPos adjustForTravelDirection(BlockPos placementPos, Rotation rotation,
                                               SchematicValidator.SchematicValidationResult validation,
                                               NbtSchematicLoader.LoadedSchematic schematic,
                                               Direction expandDir, BlockPos anchor) {
        BlockPos simStart = simulateTrackEndpoint(true, validation, schematic, placementPos, rotation);
        BlockPos simEnd = simulateTrackEndpoint(false, validation, schematic, placementPos, rotation);

        // Determine which endpoint is the entry (closest to anchor)
        BlockPos entry, exit;
        if (anchor.distSqr(simStart) <= anchor.distSqr(simEnd)) {
            entry = simStart;
            exit = simEnd;
        } else {
            entry = simEnd;
            exit = simStart;
        }

        // Check if exit extends in the travel direction from entry
        int dx = exit.getX() - entry.getX();
        int dz = exit.getZ() - entry.getZ();

        boolean extendsCorrectly = switch (expandDir) {
            case NORTH -> dz <= 0;
            case SOUTH -> dz >= 0;
            case EAST -> dx >= 0;
            case WEST -> dx <= 0;
            default -> true;
        };

        if (!extendsCorrectly) {
            // Flip: shift the origin so the track extends in the opposite direction
            return placementPos.offset(-dx, 0, -dz);
        }

        return placementPos;
    }

    private BlockPos simulateTrackEndpoint(boolean isStart,
                                            SchematicValidator.SchematicValidationResult validation,
                                            NbtSchematicLoader.LoadedSchematic schematic,
                                            BlockPos placementPos, Rotation rotation) {
        Direction origTrackDir = validation.trackDirection;
        int trackY = validation.trackY;
        int perpOffset = validation.trackPerpOffset;

        BlockPos local;
        if (origTrackDir == Direction.SOUTH || origTrackDir == Direction.NORTH) {
            local = isStart ? new BlockPos(perpOffset, trackY, 0)
                            : new BlockPos(perpOffset, trackY, schematic.getLength() - 1);
        } else {
            local = isStart ? new BlockPos(0, trackY, perpOffset)
                            : new BlockPos(schematic.getWidth() - 1, trackY, perpOffset);
        }

        return RotationHelper.transformPosition(local, schematic.getSize(), placementPos, rotation);
    }

    /**
     * Calculates the schematic placement position to center it on the track.
     * Uses the same math as StationPlacementGeometry.calculateStationPlacementPosition().
     */
    private BlockPos calculatePlacementPosition(BlockPos trackPos, Rotation rotation,
                                                 SchematicValidator.SchematicValidationResult validation,
                                                 NbtSchematicLoader.LoadedSchematic schematic) {
        int trackY = validation.trackY;
        int perpOffset = validation.trackPerpOffset;
        boolean isNorthSouth = validation.trackDirection == Direction.SOUTH
                || validation.trackDirection == Direction.NORTH;

        int offsetX = 0;
        int offsetZ = 0;

        switch (rotation) {
            case NONE -> {
                if (isNorthSouth) offsetX = -perpOffset;
                else offsetZ = -perpOffset;
            }
            case CLOCKWISE_90 -> {
                if (isNorthSouth) offsetZ = -perpOffset;
                else offsetX = perpOffset - schematic.getLength() + 1;
            }
            case CLOCKWISE_180 -> {
                if (isNorthSouth) offsetX = perpOffset - schematic.getWidth() + 1;
                else offsetZ = perpOffset - schematic.getLength() + 1;
            }
            case COUNTERCLOCKWISE_90 -> {
                if (isNorthSouth) offsetZ = perpOffset - schematic.getWidth() + 1;
                else offsetX = -perpOffset;
            }
        }

        return trackPos.offset(offsetX, -trackY, offsetZ);
    }

    /**
     * Places a single track block at an edge position (entry or exit point).
     */
    private void placeEdgeTrack(ServerLevel level, BlockPos pos, Direction trackDir) {
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.applyMaterialForBiome(level, pos);
        var chunk = ChunkCoordinateUtil.getLoadedChunk(level, pos);
        if (chunk == null) {
            return;
        }
        BlockState trackState = CreateTrackUtil.getStraightTrack(trackDir);
        ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, trackState, true);
        // Use non-destructive graph registration to avoid train stutter
        if (!com.vodmordia.railwaysuntold.worldgen.integration.create.util.DirectGraphConnector
                .ensureTrackInGraph(level, pos, trackDir)) {
            if (com.simibubi.create.Create.RAILWAYS.trains.isEmpty()) {
                CreateTrackUtil.triggerTrackPropagator(level, pos, level.getBlockState(pos));
            } else {
                LOGGER.warn("[EVENT] ensureTrackInGraph failed with trains present at {}", pos);
            }
        }
    }
}
