package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateReflectionFields;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Utility class for working with Create mod girder blocks.
 */
public class GirderUtil {

    private static final Logger LOGGER = LogUtils.getLogger();
    // Lazy-cached girder block properties
    private static volatile boolean girderPropertiesCached = false;
    @SuppressWarnings("rawtypes")
    private static volatile net.minecraft.world.level.block.state.properties.Property cachedTopProp;
    @SuppressWarnings("rawtypes")
    private static volatile net.minecraft.world.level.block.state.properties.Property cachedBottomProp;
    @SuppressWarnings("rawtypes")
    private static volatile net.minecraft.world.level.block.state.properties.Property cachedAxisProp;
    @SuppressWarnings("rawtypes")
    private static volatile net.minecraft.world.level.block.state.properties.Property cachedXProp;
    @SuppressWarnings("rawtypes")
    private static volatile net.minecraft.world.level.block.state.properties.Property cachedZProp;

    // Lazy-cached waterlogging
    private static volatile boolean waterloggingCached = false;
    private static volatile java.lang.reflect.Method cachedWithWaterMethod;

    /**
     * Calculates the positions where girders should be placed for a given track position.
     *
     * @param trackPos Track position
     * @param tangent Track tangent vector (normalized direction the track is traveling)
     * @return Array of 2 positions: [left girder, right girder]
     */
    public static BlockPos[] getGirderPositions(BlockPos trackPos, Vec3 tangent) {
        BlockPos girderBasePos = trackPos.below();

        // Cross product with up vector gives perpendicular horizontal direction
        Vec3 mainNormal = tangent.cross(new Vec3(0, 1, 0)).normalize();

        Vec3 center = new Vec3(girderBasePos.getX() + 0.5, girderBasePos.getY() + 0.5, girderBasePos.getZ() + 0.5);

        BlockPos leftGirder = BlockPos.containing(center.add(mainNormal));
        BlockPos rightGirder = BlockPos.containing(center.subtract(mainNormal));

        return new BlockPos[] { leftGirder, rightGirder };
    }

    /**
     * Calculates the positions where girders should be placed using actual world position.
     *
     * @param worldPosition Actual world position of the track segment
     * @param tangent       Track tangent vector (normalized direction the track is traveling)
     * @return Array of 2 positions: [left girder, right girder]
     */
    public static BlockPos[] getGirderPositions(Vec3 worldPosition, Vec3 tangent) {
        int girderY = (int) Math.floor(worldPosition.y) - 1;

        Vec3 mainNormal = tangent.cross(new Vec3(0, 1, 0)).normalize();

        Vec3 center = new Vec3(worldPosition.x, girderY + 0.5, worldPosition.z);

        BlockPos leftGirder = BlockPos.containing(center.add(mainNormal));
        BlockPos rightGirder = BlockPos.containing(center.subtract(mainNormal));

        return new BlockPos[] { leftGirder, rightGirder };
    }

    /**
     * Creates a girder block state with specified properties.
     *
     * @param girderBlock The girder block to create state for
     * @param axis The axis the girder should be oriented along
     * @param top Whether the girder has a top connection
     * @param bottom Whether the girder has a bottom connection
     * @return Configured girder block state, or default state if reflection fails
     */
    @SuppressWarnings("unchecked")
    public static BlockState createGirderState(Block girderBlock, Axis axis, boolean top, boolean bottom) {
        BlockState girderState = girderBlock.defaultBlockState();

        try {
            ensureGirderPropertiesCached();
            if (cachedTopProp == null) {
                return girderState;
            }

            girderState = girderState
                    .setValue(cachedAxisProp, axis)
                    .setValue(cachedTopProp, top)
                    .setValue(cachedBottomProp, bottom);

            net.minecraft.world.level.block.state.properties.Property<Boolean> axisProperty =
                    (net.minecraft.world.level.block.state.properties.Property<Boolean>)
                            (axis == Axis.X ? cachedXProp : cachedZProp);
            girderState = girderState.setValue(axisProperty, true);

        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[GIRDER] Failed to create girder state: {}", e.getMessage());
        }

        return girderState;
    }

    @SuppressWarnings("unchecked")
    private static void ensureGirderPropertiesCached() throws ReflectiveOperationException {
        if (girderPropertiesCached) return;
        Class<?> girderBlockClass = Class.forName("com.simibubi.create.content.decoration.girder.GirderBlock");
        cachedTopProp = (net.minecraft.world.level.block.state.properties.Property<Boolean>)
                girderBlockClass.getField(CreateReflectionFields.TOP).get(null);
        cachedBottomProp = (net.minecraft.world.level.block.state.properties.Property<Boolean>)
                girderBlockClass.getField(CreateReflectionFields.BOTTOM).get(null);
        cachedAxisProp = (net.minecraft.world.level.block.state.properties.Property<Axis>)
                girderBlockClass.getField(CreateReflectionFields.AXIS).get(null);
        cachedXProp = (net.minecraft.world.level.block.state.properties.Property<Boolean>)
                girderBlockClass.getField(CreateReflectionFields.X).get(null);
        cachedZProp = (net.minecraft.world.level.block.state.properties.Property<Boolean>)
                girderBlockClass.getField(CreateReflectionFields.Z).get(null);
        girderPropertiesCached = true;
    }

    /**
     * Applies waterlogging to a girder block state if the position contains water.
     *
     * @param level The level accessor
     * @param state The girder block state to potentially waterlog
     * @param pos   The position where the girder will be placed
     * @return The waterlogged state if water is present, otherwise the original state
     */
    public static BlockState applyWaterlogging(LevelAccessor level, BlockState state, BlockPos pos) {
        try {
            ensureWaterloggingCached();
            if (cachedWithWaterMethod == null) {
                return state;
            }
            return (BlockState) cachedWithWaterMethod.invoke(null, level, state, pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[GIRDER] Failed to apply waterlogging: {}", e.getMessage());
            return state;
        }
    }

    private static void ensureWaterloggingCached() {
        if (waterloggingCached) return;
        try {
            Class<?> waterloggedClass = Class.forName("com.simibubi.create.foundation.block.ProperWaterloggedBlock");
            cachedWithWaterMethod = waterloggedClass.getMethod("withWater",
                    LevelAccessor.class, BlockState.class, BlockPos.class);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[GIRDER] Failed to cache waterlogging method: {}", e.getMessage());
        }
        waterloggingCached = true;
    }
}
