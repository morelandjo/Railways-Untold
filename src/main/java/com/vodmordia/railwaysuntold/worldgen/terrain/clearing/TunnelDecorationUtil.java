package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionOffsets;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * Core tunnel decoration logic used by both TunnelFinisher and DeferredTunnelFinisher.
 */
public class TunnelDecorationUtil {

    /**
     * Decorates a tunnel segment directly without chunk checking.
     * Caller must ensure all required chunks are loaded.
     *
     * @param level        The server level
     * @param centerPos    Center position of the tunnel segment (track level)
     * @param normal       Normal vector perpendicular to track direction
     * @param segmentIndex Index of the segment (used for torch interval)
     * @param config       Configuration settings
     */
    public static void decorateSegmentDirect(
            ServerLevel level,
            BlockPos centerPos,
            Vec3 normal,
            int segmentIndex,
            RailwaysUntoldConfig config) {

        int horizontalRadius = RailwaysUntoldConfig.getHorizontalExpansion();
        int verticalHeight = RailwaysUntoldConfig.getVerticalExpansion();

        if (config.TUNNEL_FACADE_ENABLED) {
            placeFacade(level, centerPos, normal, horizontalRadius, verticalHeight);
        }

        if (config.TUNNEL_TORCHES_ENABLED && segmentIndex % RailwaysUntoldConfig.getTunnelLightSpacing() == 0) {
            placeTorches(level, centerPos, normal, horizontalRadius, config.TUNNEL_TORCH_HEIGHT);
        }
    }

    /**
     * Places facade blocks immediately outside the cleared tunnel area.
     * Only replaces solid blocks (not air).
     */
    private static void placeFacade(
            ServerLevel level,
            BlockPos centerPos,
            Vec3 normal,
            int horizontalRadius,
            int verticalHeight) {

        DirectionOffsets offsets = DirectionOffsets.fromNormal(normal);
        Vec3 leftOffset = offsets.left();
        Vec3 rightOffset = offsets.right();

        int facadeWallDist = horizontalRadius + 1;
        int facadeCeilingHeight = verticalHeight + 1;

        Set<BlockPos> facadePositions = new HashSet<>();

        // Left wall - extended to Y=-2 for slope handling
        // Sample from -1.0 to +0.5 to handle center shifts at curves/branches
        // The -1.0 extends outward to catch walls when center shifts by a full block
        for (int y = -2; y <= verticalHeight; y++) {
            BlockPos basePos = centerPos.above(y);
            for (double dOffset = -1.0; dOffset <= 0.5; dOffset += 0.25) {
                BlockPos wallPos = DirectionUtil.getOffsetPosition(basePos, leftOffset, facadeWallDist - dOffset);
                addAlongTrack(facadePositions, wallPos, normal);
            }
        }

        // Right wall - same multi-offset sampling for curve coverage
        for (int y = -2; y <= verticalHeight; y++) {
            BlockPos basePos = centerPos.above(y);
            for (double dOffset = -1.0; dOffset <= 0.5; dOffset += 0.25) {
                BlockPos wallPos = DirectionUtil.getOffsetPosition(basePos, rightOffset, facadeWallDist - dOffset);
                addAlongTrack(facadePositions, wallPos, normal);
            }
        }

        // Ceiling
        BlockPos ceilingCenter = centerPos.above(facadeCeilingHeight);
        addWithNeighbors(facadePositions, ceilingCenter);
        for (int d = 1; d <= horizontalRadius; d++) {
            BlockPos leftCeiling = DirectionUtil.getOffsetPosition(ceilingCenter, leftOffset, d);
            BlockPos rightCeiling = DirectionUtil.getOffsetPosition(ceilingCenter, rightOffset, d);
            addWithNeighbors(facadePositions, leftCeiling);
            addWithNeighbors(facadePositions, rightCeiling);
        }

        // Deep floor at Y=-2 (covers full radius for slope handling)
        BlockPos deepFloorCenter = centerPos.below(2);
        addWithNeighbors(facadePositions, deepFloorCenter);
        for (int d = 1; d <= horizontalRadius; d++) {
            BlockPos leftFloor = DirectionUtil.getOffsetPosition(deepFloorCenter, leftOffset, d);
            BlockPos rightFloor = DirectionUtil.getOffsetPosition(deepFloorCenter, rightOffset, d);
            addWithNeighbors(facadePositions, leftFloor);
            addWithNeighbors(facadePositions, rightFloor);
        }

        // Ground at Y=-1 for outer positions
        if (horizontalRadius > 1) {
            BlockPos groundLevel = centerPos.below();
            for (int d = 2; d <= horizontalRadius; d++) {
                BlockPos leftGround = DirectionUtil.getOffsetPosition(groundLevel, leftOffset, d);
                BlockPos rightGround = DirectionUtil.getOffsetPosition(groundLevel, rightOffset, d);
                addWithNeighbors(facadePositions, leftGround);
                addWithNeighbors(facadePositions, rightGround);
            }
        }

        for (BlockPos pos : facadePositions) {
            placeFacadeBlock(level, pos);
        }
    }

    private static void addWithNeighbors(Set<BlockPos> positions, BlockPos pos) {
        positions.add(pos);
        positions.add(pos.north());
        positions.add(pos.south());
        positions.add(pos.east());
        positions.add(pos.west());
    }

    private static void addAlongTrack(Set<BlockPos> positions, BlockPos pos, Vec3 normal) {
        positions.add(pos);
        // Track direction is perpendicular to normal (90 degrees rotated in horizontal plane)
        Vec3 trackDir = new Vec3(-normal.z, 0, normal.x);
        BlockPos forward = BlockPos.containing(Vec3.atCenterOf(pos).add(trackDir));
        BlockPos backward = BlockPos.containing(Vec3.atCenterOf(pos).subtract(trackDir));
        positions.add(forward);
        positions.add(backward);
    }

    private static boolean placeFacadeBlock(ServerLevel level, BlockPos pos) {
        BlockState existingState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);

        if (existingState == null) {
            return false;
        }

        boolean isSolid = existingState.isSolid();
        boolean isFluid = BlockTypeUtil.isWaterOrLava(existingState);
        if (!isSolid && !isFluid) {
            return false;
        }

        if (BlockTypeUtil.isChest(existingState)) {
            return false;
        }

        if (SupportConstants.isAnyFacadeBlock(existingState.getBlock())) {
            return false;
        }

        if (existingState.getBlock() == SupportConstants.getTunnelLightingBlock(level, pos).getBlock()) {
            return false;
        }

        if (CreateTrackUtil.isTrackBlock(existingState) || CreateTrackUtil.isGirderBlock(existingState)) {
            return false;
        }

        return ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, SupportConstants.getTunnelFacadeBlock(level, pos), true);
    }

    private static void placeTorches(
            ServerLevel level,
            BlockPos centerPos,
            Vec3 normal,
            int horizontalRadius,
            int torchHeight) {

        DirectionOffsets offsets = DirectionOffsets.fromNormal(normal);
        Vec3 leftOffset = offsets.left();
        Vec3 rightOffset = offsets.right();

        int attachmentType = SupportConstants.getTunnelLightingAttachment(level, centerPos);

        BlockPos lightingLevel = centerPos.above(torchHeight);

        if (attachmentType == 1) {
            int lightingDistance = horizontalRadius;
            BlockPos leftPos = DirectionUtil.getOffsetPosition(lightingLevel, leftOffset, lightingDistance);
            Direction leftFacing = getDirectionFromVector(leftOffset);
            placeWallHangingLight(level, leftPos, leftFacing);

            BlockPos rightPos = DirectionUtil.getOffsetPosition(lightingLevel, rightOffset, lightingDistance);
            Direction rightFacing = getDirectionFromVector(rightOffset);
            placeWallHangingLight(level, rightPos, rightFacing);
        } else {
            int wallDistance = horizontalRadius + 1;
            BlockPos leftPos = DirectionUtil.getOffsetPosition(lightingLevel, leftOffset, wallDistance);
            placeEmbeddedLight(level, leftPos);

            BlockPos rightPos = DirectionUtil.getOffsetPosition(lightingLevel, rightOffset, wallDistance);
            placeEmbeddedLight(level, rightPos);
        }
    }

    private static BlockPos placeWallHangingLight(ServerLevel level, BlockPos basePos, Direction facing) {
        if (ChunkCoordinateUtil.getLoadedChunk(level, basePos) == null) {
            return null;
        }

        if (tryPlaceWallHangingLight(level, basePos, facing)) {
            return basePos;
        }

        BlockPos[] fallbacks = {
                basePos.north(),
                basePos.south(),
                basePos.east(),
                basePos.west()
        };

        for (BlockPos fallback : fallbacks) {
            if (ChunkCoordinateUtil.getLoadedChunk(level, fallback) == null) {
                continue;
            }
            if (tryPlaceWallHangingLight(level, fallback, facing)) {
                return fallback;
            }
        }
        return null;
    }

    private static boolean tryPlaceWallHangingLight(ServerLevel level, BlockPos pos, Direction facing) {
        BlockState existingState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existingState == null || !existingState.isAir()) {
            return false;
        }

        BlockPos mountPos = pos.relative(facing);
        BlockState mountState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, mountPos);
        if (mountState == null || !mountState.isSolid()) {
            return false;
        }

        BlockState lightState = SupportConstants.getTunnelLightingBlock(level, pos);
        lightState = applyFacingIfPresent(lightState, facing.getOpposite());

        return ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, lightState, true);
    }

    private static BlockPos placeEmbeddedLight(ServerLevel level, BlockPos basePos) {
        if (ChunkCoordinateUtil.getLoadedChunk(level, basePos) == null) {
            return null;
        }

        if (tryPlaceEmbeddedLight(level, basePos)) {
            return basePos;
        }

        BlockPos[] fallbacks = {
                basePos.north(),
                basePos.south(),
                basePos.east(),
                basePos.west()
        };

        for (BlockPos fallback : fallbacks) {
            if (ChunkCoordinateUtil.getLoadedChunk(level, fallback) == null) {
                continue;
            }
            if (tryPlaceEmbeddedLight(level, fallback)) {
                return fallback;
            }
        }
        return null;
    }

    private static boolean tryPlaceEmbeddedLight(ServerLevel level, BlockPos pos) {
        BlockState existingState = ChunkSafeBlockAccess.getBlockStateNonBlocking(level, pos);
        if (existingState == null || !existingState.isSolid()) {
            return false;
        }

        BlockState lightState = SupportConstants.getTunnelLightingBlock(level, pos);
        if (existingState.getBlock() == lightState.getBlock()) {
            return false;
        }

        return ChunkSafeBlockAccess.setBlockStateNonBlocking(level, pos, lightState, true);
    }

    @SuppressWarnings("unchecked")
    private static BlockState applyFacingIfPresent(BlockState state, Direction facing) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("facing") && property.getValueClass() == Direction.class) {
                Property<Direction> facingProperty = (Property<Direction>) property;
                if (facingProperty.getPossibleValues().contains(facing)) {
                    return state.setValue(facingProperty, facing);
                }
            }
        }
        return state;
    }

    private static Direction getDirectionFromVector(Vec3 vec) {
        double absX = Math.abs(vec.x);
        double absZ = Math.abs(vec.z);

        if (absX > absZ) {
            return vec.x > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return vec.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
}
