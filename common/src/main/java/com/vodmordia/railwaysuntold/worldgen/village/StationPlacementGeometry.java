package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Pure track-position geometry for station placement: where the station footprint and its track
 * endpoints land in world space for a given runway pose, the entry/exit offsets, the positive-end
 * tests, and the village-piece collision check. World/config-free.
 */
public final class StationPlacementGeometry {

    private StationPlacementGeometry() {
    }

    /**
     * Result of track endpoint calculation.
     */
    public record TrackEndpoints(BlockPos trackStart, BlockPos trackEnd, Direction trackDirection) {
        /**
         * Returns the entry point (whichever endpoint is closer to the approach position).
         */
        public BlockPos getEntryPoint(BlockPos approachPos) {
            if (approachPos.distSqr(trackStart) < approachPos.distSqr(trackEnd)) {
                return trackStart;
            }
            return trackEnd;
        }

        /**
         * Computes entry and exit points offset 1 block outside the station track,
         * so bezier approach curves don't overlap with the station's straight track.
         *
         * @param approachPos Position of the approaching track head (determines which end is entry)
         * @return record with offsetEntry, offsetExit, and internalDir
         */
        public EntryExitPoints getOffsetEntryExit(BlockPos approachPos) {
            boolean trackAlongX = trackDirection == Direction.EAST || trackDirection == Direction.WEST;
            Direction internalDir;
            if (trackAlongX) {
                internalDir = trackEnd.getX() > trackStart.getX() ? Direction.EAST : Direction.WEST;
            } else {
                internalDir = trackEnd.getZ() > trackStart.getZ() ? Direction.SOUTH : Direction.NORTH;
            }

            BlockPos offsetEntry, offsetExit;
            if (approachPos.distSqr(trackStart) < approachPos.distSqr(trackEnd)) {
                offsetEntry = trackStart.relative(internalDir.getOpposite());
                offsetExit = trackEnd.relative(internalDir);
            } else {
                offsetEntry = trackEnd.relative(internalDir);
                offsetExit = trackStart.relative(internalDir.getOpposite());
            }
            return new EntryExitPoints(offsetEntry, offsetExit, internalDir);
        }
    }

    public record EntryExitPoints(BlockPos entry, BlockPos exit, Direction internalDir) {}

    /**
     * Calculates where the station track endpoints would be if a station were placed at the given position.
     *
     * @param station         The selected station to place
     * @param runwayPosition  Position along the runway where station would be placed
     * @param runwayDirection Direction the runway/track runs
     * @return TrackEndpoints with start/end positions
     */
    @Nullable
    public static TrackEndpoints calculateTrackEndpoints(SelectedStation station, BlockPos runwayPosition, Direction runwayDirection) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();

        Direction schematicTrackDir = validation.trackDirection;
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(schematicTrackDir, runwayDirection);
        BlockPos placementPos = calculateStationPlacementPosition(runwayPosition, rotation, validation, schematic);

        Direction origTrackDir = validation.trackDirection;
        int trackY = validation.trackY;
        int perpOffset = validation.trackPerpOffset;

        BlockPos localTrackStart;
        BlockPos localTrackEnd;

        if (origTrackDir == Direction.SOUTH || origTrackDir == Direction.NORTH) {
            localTrackStart = new BlockPos(perpOffset, trackY, 0);
            localTrackEnd = new BlockPos(perpOffset, trackY, schematic.getLength() - 1);
        } else {
            localTrackStart = new BlockPos(0, trackY, perpOffset);
            localTrackEnd = new BlockPos(schematic.getWidth() - 1, trackY, perpOffset);
        }

        BlockPos trackStart = RotationHelper.transformPosition(localTrackStart, schematic.getSize(), placementPos, rotation);
        BlockPos trackEnd = RotationHelper.transformPosition(localTrackEnd, schematic.getSize(), placementPos, rotation);
        Direction trackDir = RotationHelper.rotateDirection(origTrackDir, rotation);

        return new TrackEndpoints(trackStart, trackEnd, trackDir);
    }

    /**
     * Calculates whether the travel direction (from entry to exit) is positive along the track axis.
     *
     * @param entryPoint Where the train enters the station
     * @param exitPoint  Where the train exits the station
     * @param trackDir   The direction the track runs
     * @return true if traveling in positive axis direction, false if negative
     */
    public static boolean calculateTravelDirectionPositive(BlockPos entryPoint, BlockPos exitPoint, Direction trackDir) {
        if (trackDir == Direction.EAST || trackDir == Direction.WEST) {
            return exitPoint.getX() > entryPoint.getX();
        } else {
            return exitPoint.getZ() > entryPoint.getZ();
        }
    }

    /**
     * Checks if a station placement would collide with any village piece bounds.
     *
     * @param runwayPosition  Position where station would be placed
     * @param runwayDirection Direction the runway/track runs
     * @param station         The selected station
     * @param pieceBounds     Individual village building bounding boxes
     * @param margin          Safety margin in blocks around station footprint
     * @return true if the station footprint (with margin) intersects any village piece
     */
    public static boolean wouldCollideWithVillagePieces(BlockPos runwayPosition, Direction runwayDirection,
                                                         SelectedStation station,
                                                         List<BoundingBox> pieceBounds,
                                                         int margin) {
        if (pieceBounds == null || pieceBounds.isEmpty()) {
            return false;
        }

        FootprintXZ footprint = computeFootprintXZ(station, runwayPosition, runwayDirection, margin);

        for (BoundingBox piece : pieceBounds) {
            // 2D intersection check (ignore Y for structure protection)
            if (footprint.minX() <= piece.maxX() && footprint.maxX() >= piece.minX() &&
                footprint.minZ() <= piece.maxZ() && footprint.maxZ() >= piece.minZ()) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal world-space extent of a station footprint for a given runway pose. */
    public record FootprintXZ(int minX, int maxX, int minZ, int maxZ) {}

    /**
     * Computes the horizontal world-space footprint of the station for a given runway pose,
     * expanded by {@code margin} blocks on each side.
     *
     * @param station         The selected station to place
     * @param runwayPosition  Position along the runway where the station would be placed
     * @param runwayDirection Direction the runway/track runs
     * @param margin          Blocks to expand the footprint on each horizontal side
     */
    public static FootprintXZ computeFootprintXZ(SelectedStation station, BlockPos runwayPosition,
                                                 Direction runwayDirection, int margin) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(validation.trackDirection, runwayDirection);
        BlockPos placementPos = calculateStationPlacementPosition(runwayPosition, rotation, validation, schematic);

        Vec3i rotatedSize = RotationHelper.getRotatedSize(schematic.getSize(), rotation);
        int worldWidth = Math.abs(rotatedSize.getX());
        int worldLength = Math.abs(rotatedSize.getZ());
        return new FootprintXZ(
                placementPos.getX() - margin,
                placementPos.getX() + worldWidth - 1 + margin,
                placementPos.getZ() - margin,
                placementPos.getZ() + worldLength - 1 + margin);
    }

    /**
     * Footprint for a head arriving at {@code headPos} heading {@code headDir}, computed the way
     * {@link com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer#inverseEntryTransform}
     * lands the station at commit time - the station's entry block one ahead of the head. This is the
     * entry-aligned footprint, which differs from {@link #computeFootprintXZ}'s runway-centered one by
     * roughly half the station length, so it is the accurate predictor for a known arrival pose.
     */
    public static FootprintXZ computeEntryAlignedFootprintXZ(SelectedStation station, BlockPos headPos,
                                                             Direction headDir, int margin) {
        NbtSchematicLoader.LoadedSchematic schematic = station.schematic();
        SchematicValidator.SchematicValidationResult validation = station.validation();
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(validation.trackDirection, headDir);
        BlockPos entry = headPos.relative(headDir);
        BlockPos origin = SchematicPlacer.inverseEntryTransform(entry, headDir, schematic, validation, rotation);

        Vec3i rotatedSize = RotationHelper.getRotatedSize(schematic.getSize(), rotation);
        int worldWidth = Math.abs(rotatedSize.getX());
        int worldLength = Math.abs(rotatedSize.getZ());
        return new FootprintXZ(
                origin.getX() - margin,
                origin.getX() + worldWidth - 1 + margin,
                origin.getZ() - margin,
                origin.getZ() + worldLength - 1 + margin);
    }

    public static BlockPos calculateStationPlacementPosition(BlockPos runwayPos, Rotation rotation,
                                                       SchematicValidator.SchematicValidationResult validation,
                                                       NbtSchematicLoader.LoadedSchematic schematic) {
        return calculateStationPlacementPosition(runwayPos, rotation, validation, schematic, true);
    }

    /**
     * Calculates the placement position for the station schematic.
     *
     * @param runwayPos        The runway candidate position
     * @param rotation         Rotation to apply to the schematic
     * @param validation       Schematic validation result
     * @param schematic        The loaded schematic
     * @param centerOnRunway   If true, centers the schematic along the track axis so the runway
     *                         position is at the midpoint of the station track. If false, the
     *                         schematic origin aligns directly with the runway position.
     * @return The world position to place the schematic at
     */
    public static BlockPos calculateStationPlacementPosition(BlockPos runwayPos, Rotation rotation,
                                                       SchematicValidator.SchematicValidationResult validation,
                                                       NbtSchematicLoader.LoadedSchematic schematic,
                                                       boolean centerOnRunway) {
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

        if (centerOnRunway) {
            int trackLength = isNorthSouth ? schematic.getLength() : schematic.getWidth();
            int halfTrack = (trackLength - 1) / 2;

            boolean trackAlongWorldZ = (isNorthSouth && (rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180))
                    || (!isNorthSouth && (rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90));

            if (trackAlongWorldZ) {
                offsetZ -= halfTrack;
            } else {
                offsetX -= halfTrack;
            }
        }

        return runwayPos.offset(offsetX, 0, offsetZ);
    }

    /**
     * Finds the nearest position on the track line to the given position.
     */
    public static BlockPos findNearestTrackPos(BlockPos pos, BlockPos trackStart, BlockPos trackEnd, Direction trackDir) {
        boolean trackAlongX = trackDir == Direction.EAST || trackDir == Direction.WEST;
        if (trackAlongX) {
            int clampedX = Math.max(
                    Math.min(pos.getX(), Math.max(trackStart.getX(), trackEnd.getX())),
                    Math.min(trackStart.getX(), trackEnd.getX()));
            return new BlockPos(clampedX, trackStart.getY(), trackStart.getZ());
        } else {
            int clampedZ = Math.max(
                    Math.min(pos.getZ(), Math.max(trackStart.getZ(), trackEnd.getZ())),
                    Math.min(trackStart.getZ(), trackEnd.getZ()));
            return new BlockPos(trackStart.getX(), trackStart.getY(), clampedZ);
        }
    }

    /**
     * Checks whether a track position is closer to the positive end of the track line.
     */
    public static boolean isCloserToPositiveEnd(BlockPos trackPos, BlockPos trackStart, BlockPos trackEnd,
                                                Direction trackDir) {
        boolean trackAlongX = trackDir == Direction.EAST || trackDir == Direction.WEST;
        if (trackAlongX) {
            int positiveEnd = Math.max(trackStart.getX(), trackEnd.getX());
            return Math.abs(trackPos.getX() - positiveEnd) < Math.abs(trackPos.getX() - (trackStart.getX() + trackEnd.getX() - positiveEnd));
        } else {
            int positiveEnd = Math.max(trackStart.getZ(), trackEnd.getZ());
            return Math.abs(trackPos.getZ() - positiveEnd) < Math.abs(trackPos.getZ() - (trackStart.getZ() + trackEnd.getZ() - positiveEnd));
        }
    }

    /**
     * Returns the track end where a station block should sit, given travel direction.
     * The Create station block must be at the end where assembly starts.
     */
    public static BlockPos getTrackEndByDirection(BlockPos trackStart, BlockPos trackEnd,
                                                  Direction trackDir, boolean travelDirectionPositive) {
        boolean trackAlongX = trackDir == Direction.EAST || trackDir == Direction.WEST;
        if (trackAlongX) {
            boolean startIsHigher = trackStart.getX() > trackEnd.getX();
            return travelDirectionPositive != startIsHigher ? trackStart : trackEnd;
        } else {
            boolean startIsHigher = trackStart.getZ() > trackEnd.getZ();
            return travelDirectionPositive != startIsHigher ? trackStart : trackEnd;
        }
    }
}
