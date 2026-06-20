package com.vodmordia.railwaysuntold.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Platform abstraction for Create mod API access.
 * Each loader (Forge, Fabric) provides its own implementation.
 */
public interface CreateHelper {

    /**
     * Checks if the Create mod is available.
     * @return true if Create mod is loaded and accessible
     */
    boolean isCreateAvailable();

    // Track Block Operations

    /**
     * Gets a straight track block state oriented in the given direction.
     * @param direction The direction (NORTH/SOUTH for ZO, EAST/WEST for XO)
     * @param hasBlockEntity Whether the track should have HAS_BE=true
     * @return The configured track block state
     */
    BlockState getStraightTrackState(Direction direction, boolean hasBlockEntity);

    /**
     * Gets a straight track block state with HAS_BE=true.
     * @param direction The direction the track faces
     * @return The configured track block state
     */
    default BlockState getStraightTrackState(Direction direction) {
        return getStraightTrackState(direction, true);
    }

    /**
     * Checks if a block state is a Create track block (implements ITrackBlock).
     * @param state The block state to check
     * @return true if the block is a track
     */
    boolean isTrackBlock(BlockState state);

    /**
     * Gets the track block instance.
     * @return The Create TRACK block, or empty if not available
     */
    Optional<Block> getTrackBlock();

    // Girder Block Operations

    /**
     * Gets a girder block state with the specified configuration.
     * @param axis The axis the girder should be oriented along
     * @param top Whether the girder has a top connection
     * @param bottom Whether the girder has a bottom connection
     * @return The configured girder block state
     */
    BlockState getGirderState(Direction.Axis axis, boolean top, boolean bottom);

    /**
     * Checks if a block state is a Create girder block.
     * @param state The block state to check
     * @return true if the block is a girder
     */
    boolean isGirderBlock(BlockState state);

    /**
     * Gets the girder block instance.
     * @return The Create METAL_GIRDER block, or empty if not available
     */
    Optional<Block> getGirderBlock();

    /**
     * Places girders under a straight track section using chunk-safe operations.
     * @param level The server level
     * @param trackPos Position of the track block
     * @param direction Track direction vector
     * @param visited Set to track already-placed positions
     */
    void placeGirdersUnderStraightTrack(ServerLevel level, BlockPos trackPos, Vec3 direction, Set<BlockPos> visited);

    // Track Block Entity Operations

    /**
     * Checks if a block entity is a TrackBlockEntity.
     * @param blockEntity The block entity to check
     * @return true if it's a TrackBlockEntity
     */
    boolean isTrackBlockEntity(BlockEntity blockEntity);

    /**
     * Gets the bezier connections map from a TrackBlockEntity.
     * @param trackBlockEntity The track block entity
     * @return Map of BlockPos to BezierConnection objects
     */
    Map<BlockPos, Object> getBezierConnections(BlockEntity trackBlockEntity);

    /**
     * Validates that both block entities are TrackBlockEntity instances.
     * @param first First block entity
     * @param second Second block entity
     * @throws IllegalStateException if either is not a TrackBlockEntity
     */
    void validateTrackBlockEntities(BlockEntity first, BlockEntity second);

    // ITrackBlock Interface Operations

    /**
     * Gets the nearest track axis using Create's ITrackBlock.getNearestTrackAxis.
     * @param level The level
     * @param pos Block position
     * @param state Block state
     * @param lookVec Look direction vector
     * @return TrackAxisResult containing axis vector and direction
     */
    TrackAxisResult getNearestTrackAxis(Level level, BlockPos pos, BlockState state, Vec3 lookVec);

    /**
     * Gets the up normal vector from a track block.
     * @param level The level
     * @param pos Block position
     * @param state Block state
     * @return The normal vector (usually pointing up)
     */
    Vec3 getTrackNormal(Level level, BlockPos pos, BlockState state);

    /**
     * Gets the curve start position for a track block.
     * @param level The level
     * @param pos Block position
     * @param state Block state
     * @param axis The track axis vector
     * @return The curve start position
     */
    Vec3 getTrackCurveStart(Level level, BlockPos pos, BlockState state, Vec3 axis);

    // BezierConnection Operations

    /**
     * Extracts segment data from a BezierConnection object.
     * @param bezierConnection The BezierConnection object
     * @return List of segment data
     */
    List<BezierSegmentData> extractBezierSegments(Object bezierConnection);

    /**
     * Checks if any bezier segment passes near the given position.
     * @param bezierConnection The BezierConnection object
     * @param originPos The origin position (track block position)
     * @param checkX X coordinate to check
     * @param checkY Y coordinate to check
     * @param checkZ Z coordinate to check
     * @param tolerance How close a segment must be
     * @return true if any segment is within tolerance
     */
    boolean bezierPassesNear(Object bezierConnection, BlockPos originPos,
                            int checkX, int checkY, int checkZ, double tolerance);

    // Platform-Specific Operations

    /**
     * Gets the Couple class for the current platform.
     * Both Fabric and Forge (Create 6.0+) use: net.createmod.catnip.data.Couple
     * @return The Couple class
     * @throws ClassNotFoundException if the class cannot be found
     */
    Class<?> getCoupleClass() throws ClassNotFoundException;

    /**
     * Sends a train packet to all connected clients using platform-specific networking.
     * @param packet The packet to send
     * @return true if the packet was sent successfully
     */
    boolean sendTrainPacketToAllClients(Object packet);

    /**
     * Gets the ResourceLocation ID from a BogeyStyle object.
     * Handles the field name difference between platforms (id vs name).
     * @param bogeyStyle The BogeyStyle object
     * @return The ResourceLocation ID, or null if not accessible
     */
    ResourceLocation getBogeyStyleId(Object bogeyStyle);

    // Data Classes

    /**
     * Result from getNearestTrackAxis containing axis and direction info.
     */
    record TrackAxisResult(Vec3 axis, boolean isPositive) {
        /**
         * Gets the axis with proper scaling based on direction.
         * Positive direction means axis should be scaled by -1 (from Create's tryConnect).
         */
        public Vec3 getScaledAxis() {
            return isPositive ? axis.scale(-1) : axis;
        }
    }

    /**
     * Data extracted from a BezierConnection Segment.
     */
    record BezierSegmentData(
        int index,
        Vec3 position,
        Vec3 derivative,
        Vec3 faceNormal,
        Vec3 normal
    ) {}
}
