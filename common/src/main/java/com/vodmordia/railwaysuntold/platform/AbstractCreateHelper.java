package com.vodmordia.railwaysuntold.platform;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Abstract base class for CreateHelper implementations.
 * Contains all shared reflection logic for accessing Create mod internals.
 * Platform-specific classes extend this and provide the platform name.
 */
public abstract class AbstractCreateHelper implements CreateHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Cached class references
    protected static boolean createAvailable;
    protected static Class<?> trackBlockClass;
    protected static Class<?> trackShapeClass;
    protected static Class<?> iTrackBlockClass;
    protected static Class<?> trackBlockEntityClass;
    protected static Class<?> bezierConnectionClass;
    protected static Class<?> girderBlockClass;

    // Cached block instances
    protected static Block trackBlock;
    protected static Block girderBlock;

    // Initialization flag
    private static boolean initialized = false;

    /**
     * Returns the platform name for logging purposes.
     * @return "Fabric" or "Forge"
     */
    protected abstract String getPlatformName();

    /**
     * Initializes Create mod integration via reflection.
     * Should be called during mod initialization.
     */
    protected synchronized void initializeCreateIntegration() {
        if (initialized) {
            return;
        }

        try {
            // Load Create classes
            trackBlockClass = Class.forName("com.simibubi.create.content.trains.track.TrackBlock");
            trackShapeClass = Class.forName("com.simibubi.create.content.trains.track.TrackShape");
            iTrackBlockClass = Class.forName("com.simibubi.create.content.trains.track.ITrackBlock");
            trackBlockEntityClass = Class.forName("com.simibubi.create.content.trains.track.TrackBlockEntity");
            bezierConnectionClass = Class.forName("com.simibubi.create.content.trains.track.BezierConnection");
            girderBlockClass = Class.forName("com.simibubi.create.content.decoration.girder.GirderBlock");

            // Get block instances from AllBlocks
            Class<?> allBlocksClass = Class.forName("com.simibubi.create.AllBlocks");

            var trackField = allBlocksClass.getField("TRACK");
            var trackRegistryObject = trackField.get(null);
            var getMethod = trackRegistryObject.getClass().getMethod("get");
            trackBlock = (Block) getMethod.invoke(trackRegistryObject);

            var girderField = allBlocksClass.getField("METAL_GIRDER");
            var girderRegistryObject = girderField.get(null);
            girderBlock = (Block) getMethod.invoke(girderRegistryObject);

            createAvailable = true;
            LOGGER.info("[RailwaysUntold] Create {} mod integration initialized successfully", getPlatformName());
        } catch (Exception e) {
            createAvailable = false;
            LOGGER.warn("[RailwaysUntold] Create {} mod not available: {}", getPlatformName(), e.getMessage());
        }

        initialized = true;
    }

    @Override
    public boolean isCreateAvailable() {
        return createAvailable;
    }

    // Track Block Operations

    @Override
    public BlockState getStraightTrackState(Direction direction, boolean hasBlockEntity) {
        if (!createAvailable || trackBlock == null) {
            throw new IllegalStateException("Create mod is required but not available!");
        }

        try {
            BlockState defaultState = trackBlock.defaultBlockState();

            // Use raw Property type to avoid generic bounds issues with reflection
            @SuppressWarnings("rawtypes")
            Property shapeProperty = (Property) trackBlockClass.getField("SHAPE").get(null);
            @SuppressWarnings("unchecked")
            Property<Boolean> hasBEProperty = (Property<Boolean>) trackBlockClass.getField("HAS_BE").get(null);

            // ZO = North-South track, XO = East-West track
            String shapeName = (direction == Direction.NORTH || direction == Direction.SOUTH) ? "ZO" : "XO";
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object shapeValue = Enum.valueOf((Class<Enum>) trackShapeClass, shapeName);

            @SuppressWarnings("unchecked")
            BlockState shapedState = defaultState.setValue(shapeProperty, (Comparable) shapeValue);
            return shapedState.setValue(hasBEProperty, hasBlockEntity);

        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Error getting Create track state: {}", e.getMessage());
            throw new RuntimeException("Failed to create track state", e);
        }
    }

    @Override
    public boolean isTrackBlock(BlockState state) {
        return createAvailable && iTrackBlockClass != null && iTrackBlockClass.isInstance(state.getBlock());
    }

    @Override
    public Optional<Block> getTrackBlock() {
        return Optional.ofNullable(trackBlock);
    }

    // Girder Block Operations

    @Override
    public BlockState getGirderState(Direction.Axis axis, boolean top, boolean bottom) {
        if (!createAvailable || girderBlock == null) {
            throw new IllegalStateException("Create mod is required but not available!");
        }

        try {
            BlockState state = girderBlock.defaultBlockState();

            @SuppressWarnings("unchecked")
            Property<Boolean> topProp = (Property<Boolean>) girderBlockClass.getField("TOP").get(null);
            @SuppressWarnings("unchecked")
            Property<Boolean> bottomProp = (Property<Boolean>) girderBlockClass.getField("BOTTOM").get(null);
            @SuppressWarnings("unchecked")
            Property<Direction.Axis> axisProp = (Property<Direction.Axis>) girderBlockClass.getField("AXIS").get(null);

            state = state.setValue(axisProp, axis)
                         .setValue(topProp, top)
                         .setValue(bottomProp, bottom);

            // Set axis-specific property (X or Z)
            @SuppressWarnings("unchecked")
            Property<Boolean> axisProperty = (Property<Boolean>)
                (axis == Direction.Axis.X
                    ? girderBlockClass.getField("X").get(null)
                    : girderBlockClass.getField("Z").get(null));
            state = state.setValue(axisProperty, true);

            return state;
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Error getting girder state: {}", e.getMessage());
            return girderBlock.defaultBlockState();
        }
    }

    @Override
    public boolean isGirderBlock(BlockState state) {
        return createAvailable && girderBlock != null && state.getBlock() == girderBlock;
    }

    @Override
    public Optional<Block> getGirderBlock() {
        return Optional.ofNullable(girderBlock);
    }

    @Override
    public void placeGirdersUnderStraightTrack(ServerLevel level, BlockPos trackPos, Vec3 direction, Set<BlockPos> visited) {
        if (!createAvailable || girderBlock == null) {
            LOGGER.warn("[RailwaysUntold] Cannot place girders - Create mod not available");
            return;
        }

        try {
            // Determine axis from direction
            Direction.Axis axis = Math.abs(direction.x) > Math.abs(direction.z) ? Direction.Axis.X : Direction.Axis.Z;
            BlockState girderState = getGirderState(axis, false, false);

            BlockPos belowTrack = trackPos.below();
            Vec3 mainNormal = direction.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 center = new Vec3(belowTrack.getX() + 0.5, belowTrack.getY() + 0.5, belowTrack.getZ() + 0.5);

            // Place girders on both sides
            BlockPos leftPos = BlockPos.containing(center.add(mainNormal));
            BlockPos rightPos = BlockPos.containing(center.subtract(mainNormal));

            for (BlockPos pos : new BlockPos[]{leftPos, rightPos}) {
                if (visited.add(pos)) {
                    BlockState stateAtPos = level.getBlockState(pos);
                    if (stateAtPos.canBeReplaced()) {
                        level.setBlock(pos, girderState, 3);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[RailwaysUntold] Could not place girders at {}: {}", trackPos, e.getMessage());
        }
    }

    // Track Block Entity Operations

    @Override
    public boolean isTrackBlockEntity(BlockEntity blockEntity) {
        return trackBlockEntityClass != null && trackBlockEntityClass.isInstance(blockEntity);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<BlockPos, Object> getBezierConnections(BlockEntity trackBlockEntity) {
        if (!createAvailable || trackBlockEntityClass == null) {
            return Collections.emptyMap();
        }

        try {
            var getConnectionsMethod = trackBlockEntityClass.getMethod("getConnections");
            Object result = getConnectionsMethod.invoke(trackBlockEntity);
            if (result instanceof Map<?, ?> map) {
                return (Map<BlockPos, Object>) map;
            }
        } catch (Exception e) {
            LOGGER.warn("[RailwaysUntold] Failed to get bezier connections: {}", e.getMessage());
        }

        return Collections.emptyMap();
    }

    @Override
    public void validateTrackBlockEntities(BlockEntity first, BlockEntity second) {
        if (trackBlockEntityClass == null) {
            throw new IllegalStateException("TrackBlockEntity class not available - Create mod may not be loaded");
        }
        if (!trackBlockEntityClass.isInstance(first) || !trackBlockEntityClass.isInstance(second)) {
            throw new IllegalStateException("One or both block entities are not TrackBlockEntity instances");
        }
    }

    // ITrackBlock Interface Operations

    @Override
    public TrackAxisResult getNearestTrackAxis(Level level, BlockPos pos, BlockState state, Vec3 lookVec) {
        if (!createAvailable || iTrackBlockClass == null) {
            throw new IllegalStateException("Create mod is required but not available!");
        }

        try {
            Object trackBlock = state.getBlock();
            Object pair = iTrackBlockClass.getMethod("getNearestTrackAxis",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    BlockState.class,
                    Vec3.class
            ).invoke(trackBlock, level, pos, state, lookVec);

            Vec3 axis = (Vec3) pair.getClass().getMethod("getFirst").invoke(pair);
            Object axisDirection = pair.getClass().getMethod("getSecond").invoke(pair);
            boolean isPositive = isAxisDirectionPositive(axisDirection);

            return new TrackAxisResult(axis, isPositive);
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Error getting nearest track axis: {}", e.getMessage());
            throw new RuntimeException("Failed to get track axis", e);
        }
    }

    private boolean isAxisDirectionPositive(Object axisDirection) {
        if (axisDirection == null) return false;
        try {
            if (axisDirection instanceof Enum<?> enumValue) {
                return "POSITIVE".equals(enumValue.name());
            }
        } catch (Exception ignored) {}
        String representation = axisDirection.toString();
        return representation.contains("POSITIVE");
    }

    @Override
    public Vec3 getTrackNormal(Level level, BlockPos pos, BlockState state) {
        if (!createAvailable || iTrackBlockClass == null) {
            throw new IllegalStateException("Create mod is required but not available!");
        }

        try {
            Object trackBlock = state.getBlock();
            return (Vec3) iTrackBlockClass.getMethod("getUpNormal",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    BlockState.class
            ).invoke(trackBlock, level, pos, state);
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Error getting track normal: {}", e.getMessage());
            return new Vec3(0, 1, 0); // Default up vector
        }
    }

    @Override
    public Vec3 getTrackCurveStart(Level level, BlockPos pos, BlockState state, Vec3 axis) {
        if (!createAvailable || iTrackBlockClass == null) {
            throw new IllegalStateException("Create mod is required but not available!");
        }

        try {
            Object trackBlock = state.getBlock();
            return (Vec3) iTrackBlockClass.getMethod("getCurveStart",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    BlockState.class,
                    Vec3.class
            ).invoke(trackBlock, level, pos, state, axis);
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Error getting track curve start: {}", e.getMessage());
            throw new RuntimeException("Failed to get curve start", e);
        }
    }

    // BezierConnection Operations

    @Override
    public List<BezierSegmentData> extractBezierSegments(Object bezierConnection) {
        if (bezierConnection == null || !createAvailable) {
            return Collections.emptyList();
        }

        try {
            List<BezierSegmentData> segments = new ArrayList<>();
            Iterable<?> segmentIterator = (Iterable<?>) bezierConnection;

            for (Object segmentObj : segmentIterator) {
                Class<?> segmentClass = segmentObj.getClass();

                int index = segmentClass.getField("index").getInt(segmentObj);
                Vec3 position = (Vec3) segmentClass.getField("position").get(segmentObj);
                Vec3 derivative = (Vec3) segmentClass.getField("derivative").get(segmentObj);
                Vec3 faceNormal = (Vec3) segmentClass.getField("faceNormal").get(segmentObj);
                Vec3 normal = (Vec3) segmentClass.getField("normal").get(segmentObj);

                segments.add(new BezierSegmentData(index, position, derivative, faceNormal, normal));
            }

            return Collections.unmodifiableList(segments);
        } catch (Exception e) {
            LOGGER.error("[RailwaysUntold] Failed to extract bezier segments: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @Override
    public boolean bezierPassesNear(Object bezierConnection, BlockPos originPos,
                                    int checkX, int checkY, int checkZ, double tolerance) {
        if (bezierConnection == null || !createAvailable) {
            return false;
        }

        try {
            if (!(bezierConnection instanceof Iterable<?> iterable)) {
                return false;
            }

            double originX = originPos.getX() + 0.5;
            double originY = originPos.getY();
            double originZ = originPos.getZ() + 0.5;
            double toleranceSq = tolerance * tolerance;

            for (Object segment : iterable) {
                var positionField = segment.getClass().getField("position");
                Vec3 segmentPos = (Vec3) positionField.get(segment);

                double worldX = originX + segmentPos.x;
                double worldY = originY + segmentPos.y;
                double worldZ = originZ + segmentPos.z;

                double dx = worldX - (checkX + 0.5);
                double dz = worldZ - (checkZ + 0.5);
                double horizontalDistSq = dx * dx + dz * dz;

                if (horizontalDistSq <= toleranceSq && Math.abs(worldY - checkY) < 1.5) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[RailwaysUntold] Failed to check bezier proximity: {}", e.getMessage());
        }

        return false;
    }

    // Platform-Specific Operations

    /**
     * Returns the class path for the Couple class on this platform.
     * Override in platform-specific implementations.
     * @return The fully qualified class name for Couple
     */
    protected abstract String getCoupleClassName();

    @Override
    public Class<?> getCoupleClass() throws ClassNotFoundException {
        return Class.forName(getCoupleClassName());
    }

    /**
     * Sends a packet to all connected clients.
     * Must be implemented by platform-specific classes due to different networking APIs.
     */
    @Override
    public abstract boolean sendTrainPacketToAllClients(Object packet);

    /**
     * Returns the field name used for BogeyStyle ID on this platform.
     * Override in platform-specific implementations.
     * @return "id" for Forge, "name" for Fabric
     */
    protected abstract String getBogeyStyleIdFieldName();

    @Override
    public ResourceLocation getBogeyStyleId(Object bogeyStyle) {
        if (bogeyStyle == null) {
            return null;
        }

        try {
            Field idField = bogeyStyle.getClass().getDeclaredField(getBogeyStyleIdFieldName());
            idField.setAccessible(true);
            return (ResourceLocation) idField.get(bogeyStyle);
        } catch (NoSuchFieldException e) {
            // Try the alternative field name
            String altFieldName = getBogeyStyleIdFieldName().equals("id") ? "name" : "id";
            try {
                Field altField = bogeyStyle.getClass().getDeclaredField(altFieldName);
                altField.setAccessible(true);
                return (ResourceLocation) altField.get(bogeyStyle);
            } catch (Exception e2) {
                LOGGER.warn("[RailwaysUntold] Could not get BogeyStyle ID: {}", e2.getMessage());
                return new ResourceLocation("create", "standard");
            }
        } catch (Exception e) {
            LOGGER.warn("[RailwaysUntold] Could not get BogeyStyle ID: {}", e.getMessage());
            return new ResourceLocation("create", "standard");
        }
    }

    // Static Utility Accessors

    /**
     * Gets the BezierConnection class for reflection operations.
     * @return The BezierConnection class, or null if Create is not available
     */
    public static Class<?> getBezierConnectionClass() {
        return bezierConnectionClass;
    }

    /**
     * Gets the ITrackBlock class for reflection operations.
     * @return The ITrackBlock class, or null if Create is not available
     */
    public static Class<?> getITrackBlockClass() {
        return iTrackBlockClass;
    }

    /**
     * Gets the TrackBlockEntity class for reflection operations.
     * @return The TrackBlockEntity class, or null if Create is not available
     */
    public static Class<?> getTrackBlockEntityClass() {
        return trackBlockEntityClass;
    }
}
