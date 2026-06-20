package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.ChunkSafeGirderPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateReflectionFields;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

/**
 * Utility class for interfacing with Create mod track blocks.
 */
public final class CreateTrackUtil {

    private CreateTrackUtil() {
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean createAvailable;
    private static Class<?> trackBlockClass = null;
    private static Class<?> trackShapeClass = null;
    private static Object trackBlock = null;
    private static Object girderBlock = null;

    // Cached class references for commonly-used Create mod classes
    private static Class<?> iTrackBlockClass = null;
    private static Class<?> trackBlockEntityClass = null;
    private static Class<?> bezierConnectionClass = null;

    @SuppressWarnings("rawtypes")
    private static Property shapeProperty = null;
    @SuppressWarnings("rawtypes")
    private static Property hasBEProperty = null;
    private static java.lang.reflect.Method onRailAddedMethod = null;

    private static Class<?> coupleClass = null;
    private static java.lang.reflect.Method coupleCreateMethod = null;
    private static java.lang.reflect.Method coupleGetFirstMethod = null;
    private static java.lang.reflect.Method coupleGetSecondMethod = null;

    private static Class<?> trackMaterialClass = null;
    private static Object defaultAndesiteMaterial = null;
    private static Object activeTrackMaterial = null;
    // May be overridden by Railway mod compat
    private static Object activeTrackBlock = null;

    private static java.lang.reflect.Constructor<?> bezierConnectionConstructor = null;

    /** Cached fake_track Block instance for fast identity comparison in hot paths. */
    private static Block fakeTrackBlock = null;

    static {
        try {
            // Try to load Create classes
            trackBlockClass = Class.forName("com.simibubi.create.content.trains.track.TrackBlock");
            trackShapeClass = Class.forName("com.simibubi.create.content.trains.track.TrackShape");
            Class<?> allBlocksClass = Class.forName("com.simibubi.create.AllBlocks");

            // Cache commonly-used classes to avoid repeated lookups
            iTrackBlockClass = Class.forName("com.simibubi.create.content.trains.track.ITrackBlock");
            trackBlockEntityClass = Class.forName("com.simibubi.create.content.trains.track.TrackBlockEntity");
            bezierConnectionClass = Class.forName("com.simibubi.create.content.trains.track.BezierConnection");

            // Get TRACK block from AllBlocks
            var trackField = allBlocksClass.getField(CreateReflectionFields.TRACK);
            var trackRegistryObject = trackField.get(null);
            var getMethod = trackRegistryObject.getClass().getMethod("get");
            trackBlock = getMethod.invoke(trackRegistryObject);

            // Get METAL_GIRDER block from AllBlocks
            var girderField = allBlocksClass.getField(CreateReflectionFields.METAL_GIRDER);
            var girderRegistryObject = girderField.get(null);
            girderBlock = getMethod.invoke(girderRegistryObject);

            createAvailable = true;

            try {
                shapeProperty = (Property) trackBlockClass.getField(CreateReflectionFields.SHAPE).get(null);
                hasBEProperty = (Property) trackBlockClass.getField(CreateReflectionFields.HAS_BE).get(null);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("[CREATE-TRACK] Failed to cache track block properties: {}", e.getMessage());
            }

            try {
                Class<?> trackPropagatorClass = Class.forName("com.simibubi.create.content.trains.track.TrackPropagator");
                onRailAddedMethod = trackPropagatorClass.getMethod("onRailAdded",
                        net.minecraft.world.level.LevelAccessor.class,
                        net.minecraft.core.BlockPos.class,
                        BlockState.class);
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("[CREATE-TRACK] Failed to cache track propagator: {}", e.getMessage());
            }

            try {
                coupleClass = Class.forName("net.createmod.catnip.data.Couple");
                coupleCreateMethod = coupleClass.getMethod("create", Object.class, Object.class);
                coupleGetFirstMethod = coupleClass.getMethod("getFirst");
                coupleGetSecondMethod = coupleClass.getMethod("getSecond");
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("[CREATE-TRACK] Failed to cache Couple class: {}", e.getMessage());
            }

            try {
                trackMaterialClass = Class.forName("com.simibubi.create.content.trains.track.TrackMaterial");
                defaultAndesiteMaterial = trackMaterialClass.getField("ANDESITE").get(null);
                activeTrackMaterial = defaultAndesiteMaterial;
            } catch (ReflectiveOperationException e) {
                LOGGER.warn("[CREATE-TRACK] Failed to cache TrackMaterial: {}", e.getMessage());
            }

            if (bezierConnectionClass != null && coupleClass != null && trackMaterialClass != null) {
                try {
                    bezierConnectionConstructor = bezierConnectionClass.getConstructor(
                            coupleClass, coupleClass, coupleClass, coupleClass,
                            boolean.class, boolean.class, trackMaterialClass);
                } catch (ReflectiveOperationException e) {
                    LOGGER.warn("[CREATE-TRACK] Failed to cache BezierConnection constructor: {}", e.getMessage());
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.debug("Create mod not available, track features disabled: {}", e.getMessage());
            createAvailable = false;
        }

        // Cache fake_track block for fast identity comparison in hot clearing paths
        try {
            net.minecraft.resources.ResourceLocation fakeTrackId =
                    new net.minecraft.resources.ResourceLocation("railwaysuntold", "fake_track");
            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            Block resolved = registry.get(fakeTrackId);
            // registry.get() returns air for unknown keys - only cache if it's actually our block
            if (resolved != null && registry.getKey(resolved).equals(fakeTrackId)) {
                fakeTrackBlock = resolved;
            }
        } catch (Exception e) {
            LOGGER.warn("fake_track block not found in registry: {}", e.getMessage(), e);
        }
    }

    /**
     * Gets the cached TrackMaterial class (used by Railway mod compat).
     */
    public static Class<?> getTrackMaterialClass() {
        return trackMaterialClass;
    }

    /**
     * Gets the default andesite TrackMaterial (used by Railway mod compat for fallback).
     */
    public static Object getDefaultAndesiteMaterial() {
        return defaultAndesiteMaterial;
    }

    /**
     * Gets the active track material (configured material, or default andesite).
     */
    public static Object getActiveTrackMaterial() {
        return activeTrackMaterial != null ? activeTrackMaterial : defaultAndesiteMaterial;
    }

    /**
     * Sets the active track material used for bezier connections.
     * Called by RailwayModCompat during initialization.
     */
    public static void setActiveTrackMaterial(Object material) {
        activeTrackMaterial = material;
    }

    /**
     * Sets the active track block used for straight and diagonal tracks.
     * Called by RailwayModCompat during initialization.
     */
    public static void setActiveTrackBlock(Object block) {
        activeTrackBlock = block;
    }

    /**
     * Gets the cached girder block instance.
     *
     * @return The girder Block, or null if Create is not available
     */
    public static Block getGirderBlock() {
        return createAvailable && girderBlock != null ? (Block) girderBlock : null;
    }

    /**
     * Gets the cached ITrackBlock interface class.
     * @return The ITrackBlock class, or null if Create is not available
     */
    public static Class<?> getITrackBlockClass() {
        return iTrackBlockClass;
    }

    /**
     * Gets the cached TrackBlockEntity class.
     * @return The TrackBlockEntity class, or null if Create is not available
     */
    public static Class<?> getTrackBlockEntityClass() {
        return trackBlockEntityClass;
    }

    /**
     * Gets the cached BezierConnection class.
     * @return The BezierConnection class, or null if Create is not available
     */
    public static Class<?> getBezierConnectionClass() {
        return bezierConnectionClass;
    }

    public static java.lang.reflect.Method getCoupleCreateMethod() {
        return coupleCreateMethod;
    }

    public static java.lang.reflect.Method getCoupleGetFirstMethod() {
        return coupleGetFirstMethod;
    }

    public static java.lang.reflect.Method getCoupleGetSecondMethod() {
        return coupleGetSecondMethod;
    }

    /**
     * Triggers Create's TrackPropagator.onRailAdded to register a track in the graph.
     */
    public static void triggerTrackPropagator(net.minecraft.server.level.ServerLevel level,
                                              net.minecraft.core.BlockPos pos, BlockState state) {
        if (onRailAddedMethod == null) {
            return;
        }
        try {
            onRailAddedMethod.invoke(null, level, pos, state);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[CREATE-TRACK] Failed to trigger track propagator at {}: {}", pos, e.getMessage());
        }
    }

    /**
     * Constructs a BezierConnection object using cached reflection.
     */
    public static Object constructBezierConnection(
            net.minecraft.core.BlockPos pos1, net.minecraft.core.BlockPos pos2,
            net.minecraft.world.phys.Vec3 end1, net.minecraft.world.phys.Vec3 end2,
            net.minecraft.world.phys.Vec3 axis1, net.minecraft.world.phys.Vec3 axis2,
            net.minecraft.world.phys.Vec3 normal1, net.minecraft.world.phys.Vec3 normal2) throws ReflectiveOperationException {
        if (bezierConnectionConstructor == null || coupleCreateMethod == null || activeTrackMaterial == null) {
            throw new IllegalStateException("[CREATE-TRACK] Bezier connection reflection not available");
        }

        Object posCouple = coupleCreateMethod.invoke(null, pos1, pos2);
        Object teaserCouple = coupleCreateMethod.invoke(null, end1, end2);
        Object axesCouple = coupleCreateMethod.invoke(null, axis1.normalize(), axis2.normalize());
        Object normalsCouple = coupleCreateMethod.invoke(null, normal1, normal2);

        return bezierConnectionConstructor.newInstance(
                posCouple, teaserCouple, axesCouple, normalsCouple,
                true, true, activeTrackMaterial);
    }

    /**
     * Gets a track block state for a straight track in the given direction.
     */
    public static BlockState getStraightTrack(Direction direction) {
        return getStraightTrackInternal(direction, true);
    }

    /**
     * Helper to create a straight track block state.
     *
     * @param direction The direction the track should face
     * @param withBlockEntity If true, sets HAS_BE=true; if false, sets HAS_BE=false
     * @return Configured BlockState for the straight track
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState getStraightTrackInternal(Direction direction, boolean withBlockEntity) {
        if (!createAvailable || trackBlock == null) {
            throw new IllegalStateException("[CREATE-TRACK] Create mod is required but not available!");
        }

        try {
            Object effectiveBlock = activeTrackBlock != null ? activeTrackBlock : trackBlock;
            Block block = (Block) effectiveBlock;
            BlockState defaultState = block.defaultBlockState();

            // ZO = North-South track, XO = East-West track
            String shapeName = (direction == Direction.NORTH || direction == Direction.SOUTH) ? CreateReflectionFields.ZO : CreateReflectionFields.XO;
            Comparable shapeValue = Enum.valueOf((Class<Enum>) trackShapeClass, shapeName);

            BlockState shapedState = defaultState.setValue(shapeProperty, shapeValue);
            return shapedState.setValue(hasBEProperty, withBlockEntity);

        } catch (RuntimeException e) {
            LOGGER.error("[CREATE-TRACK] Error getting Create track block: {}", e.getMessage(), e);
            throw new RuntimeException("[CREATE-TRACK] Failed to create Create track block", e);
        }
    }

    /**
     * Calculates the end position for a curve based on starting position, direction, turn parameters.
     *
     * @param start Starting position
     * @param trackDirection Current track direction
     * @param turnLeft Whether turning left (true) or right (false)
     * @param angle Curve angle in degrees (45 or 90)
     * @param radius Curve radius in blocks
     * @param elevationChange Vertical offset (positive for up, negative for down)
     * @return The end position of the curve with elevation change
     */
    public static net.minecraft.core.BlockPos calculateCurveEndpoint(
            net.minecraft.core.BlockPos start,
            Direction trackDirection,
            boolean turnLeft,
            int angle,
            int radius,
            int elevationChange) {

        Direction turnDirection = DirectionUtil.getPerpendicularDirection(trackDirection, turnLeft);

        int dx, dz;

        if (angle == 90) {
            // 90° curve: quarter circle
            // The track goes forward by radius, then turns perpendicular by radius
            dx = trackDirection.getStepX() * radius + turnDirection.getStepX() * radius;
            dz = trackDirection.getStepZ() * radius + turnDirection.getStepZ() * radius;
        } else if (angle == 45) {
            // 45° curve: gentler arc
            // Goes forward more (radius * 1.4 ≈ radius * sqrt(2)) and turns less
            int forwardDist = (int)(radius * 1.4);  // Forward component
            int sideDist = (int)(radius * 0.7);      // Sideways component (creates 45° angle)

            dx = trackDirection.getStepX() * forwardDist + turnDirection.getStepX() * sideDist;
            dz = trackDirection.getStepZ() * forwardDist + turnDirection.getStepZ() * sideDist;
        } else {
            throw new IllegalArgumentException("Invalid curve angle: " + angle + " (must be 45 or 90)");
        }

        return start.offset(dx, elevationChange, dz);
    }

    /**
     * Checks if a block state is a fake track block.
     *
     * @param state The block state to check
     * @return true if the block is a fake_track block
     */
    /**
     * Checks if a block state is a fake_track block (placed by Create along bezier curves).
     * Uses cached block reference for O(1) identity comparison instead of registry lookup.
     */
    public static boolean isFakeTrack(BlockState state) {
        return fakeTrackBlock != null && state.getBlock() == fakeTrackBlock;
    }

    /**
     * Checks if a block state is a track block.
     *
     * @param state The block state to check
     * @return true if the block implements ITrackBlock or is a fake_track block
     */
    public static boolean isTrackBlock(BlockState state) {
        // Check for real track blocks (implements ITrackBlock)
        if (createAvailable && iTrackBlockClass != null && iTrackBlockClass.isInstance(state.getBlock())) {
            return true;
        }
        // FakeTrackBlock doesn't implement ITrackBlock but represents tracks
        return isFakeTrack(state);
    }

    /**
     * Checks if a block state is a girder block (pillars under tracks).
     *
     * @param state The block state to check
     * @return true if the block is a Create metal girder
     */
    public static boolean isGirderBlock(BlockState state) {
        if (!createAvailable || girderBlock == null) {
            return false;
        }
        return state.getBlock() == girderBlock;
    }

    /**
     * Checks if an object is a TrackBlockEntity instance.
     *
     * @param blockEntity The object to check
     * @return true if the object is a TrackBlockEntity
     */
    public static boolean isTrackBlockEntity(Object blockEntity) {
        return trackBlockEntityClass != null && trackBlockEntityClass.isInstance(blockEntity);
    }

    /**
     * Validates that both block entities are TrackBlockEntity instances.
     *
     * @param first First block entity
     * @param second Second block entity
     * @param context Context string for error messages (e.g., "BLOCK-ENTITY-LINKER")
     * @throws IllegalStateException If Create mod is not loaded or if either entity is not a TrackBlockEntity
     */
    public static void validateTrackBlockEntities(Object first, Object second, String context) {
        if (trackBlockEntityClass == null) {
            throw new IllegalStateException("[" + context + "] TrackBlockEntity class not available - Create mod may not be loaded");
        }
        if (!trackBlockEntityClass.isInstance(first) || !trackBlockEntityClass.isInstance(second)) {
            throw new IllegalStateException("[" + context + "] One or both block entities are not TrackBlockEntity instances");
        }
    }

    /**
     * Paves girders under a straight track section using Create's TrackPaver.
     *
     * @param level The server level
     * @param trackPos Position of the track block
     * @param direction Track direction (NORTH, SOUTH, EAST, or WEST)
     */
    public static void placeGirdersUnderStraightTrack(net.minecraft.server.level.ServerLevel level,
                                                     net.minecraft.core.BlockPos trackPos,
                                                     Direction direction) {
        if (!createAvailable) {
            LOGGER.warn("[CREATE-TRACK] Cannot pave girders - Create mod not available");
            return;
        }

        try {
            // Get girder block
            Block girderBlockInstance = (Block) girderBlock;

            // Convert Direction to Vec3 axis
            net.minecraft.world.phys.Vec3 dirVec = net.minecraft.world.phys.Vec3.atLowerCornerOf(direction.getNormal());

            // Create visited set (prevents duplicate placement)
            java.util.Set<net.minecraft.core.BlockPos> visited = new java.util.HashSet<>();

            // Use chunk-safe girder placer instead of Create's TrackPaver
            // This prevents chunk loading freezes caused by Create's blocking level.setBlock() calls
            ChunkSafeGirderPlacer.paveStraightChunkSafe(level, trackPos.below(), dirVec, 1,
                girderBlockInstance, visited);

        } catch (RuntimeException e) {
            LOGGER.warn("[CREATE-TRACK] Could not pave girders for straight track at {}: {}", trackPos, e.getMessage());
        }
    }

    /**
     * Gets a track block state for a diagonal track.
     *
     * @param diagonal The diagonal direction (determines PD or ND track shape)
     * @return BlockState for the diagonal track
     */
    public static BlockState getDiagonalTrack(DiagonalDirection diagonal) {
        return getDiagonalTrackInternal(diagonal, true);
    }

    /**
     * Helper to create a diagonal track block state.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState getDiagonalTrackInternal(DiagonalDirection diagonal, boolean withBlockEntity) {
        if (!createAvailable || trackBlock == null) {
            throw new IllegalStateException("[CREATE-TRACK] Create mod is required but not available!");
        }

        try {
            Object effectiveBlock = activeTrackBlock != null ? activeTrackBlock : trackBlock;
            Block block = (Block) effectiveBlock;
            BlockState defaultState = block.defaultBlockState();

            // PD = SE/NW diagonal, ND = NE/SW diagonal
            String shapeName = diagonal.isPositiveDiagonal() ? CreateReflectionFields.PD : CreateReflectionFields.ND;
            Comparable shapeValue = Enum.valueOf((Class<Enum>) trackShapeClass, shapeName);

            BlockState shapedState = defaultState.setValue(shapeProperty, shapeValue);
            return shapedState.setValue(hasBEProperty, withBlockEntity);

        } catch (RuntimeException e) {
            LOGGER.error("[CREATE-TRACK] Error getting Create diagonal track block: {}", e.getMessage(), e);
            throw new RuntimeException("[CREATE-TRACK] Failed to create Create diagonal track block", e);
        }
    }
}
