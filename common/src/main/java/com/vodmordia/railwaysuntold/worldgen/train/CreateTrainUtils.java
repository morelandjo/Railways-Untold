package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.vodmordia.railwaysuntold.platform.Services;
import com.vodmordia.railwaysuntold.worldgen.integration.create.CreateReflectionFields;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

// AddTrainPacket imported via reflection to handle different Create mod versions

/**
 * Utilities for accessing Create mod train internals.
 */
public class CreateTrainUtils {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Cached for TrainPacket creation
    private static volatile Class<?> trainPacketClass = null;
    private static volatile java.lang.reflect.Constructor<?> trainPacketConstructor = null;
    private static volatile boolean networkReflectionInitialized = false;
    private static final Object NETWORK_REFLECTION_LOCK = new Object();

    // Cached for bogey blocks
    private static volatile AbstractBogeyBlock<?> cachedSmallBogey = null;
    private static volatile AbstractBogeyBlock<?> cachedLargeBogey = null;
    private static volatile ResourceLocation cachedStandardStyleId = null;
    private static volatile boolean bogeyReflectionInitialized = false;
    private static final Object BOGEY_REFLECTION_LOCK = new Object();

    /**
     * Calculates the correct Vec3 position for a track node based on block position and track direction.
     *
     *
     * @param blockPos The block position of the track
     * @param trackDirection The direction the track runs (the direction it extends toward)
     * @return The Vec3 position for looking up the track node
     */
    public static Vec3 getTrackNodePosition(net.minecraft.core.BlockPos blockPos, net.minecraft.core.Direction trackDirection) {
        // Track nodes are at the edge of the block in the direction of travel
        // For a track facing SOUTH (Z+), the node is at (x+0.5, y, z+1)
        // For a track facing NORTH (Z-), the node is at (x+0.5, y, z)
        // For a track facing EAST (X+), the node is at (x+1, y, z+0.5)
        // For a track facing WEST (X-), the node is at (x, y, z+0.5)
        return switch (trackDirection) {
            case SOUTH -> new Vec3(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 1);
            case NORTH -> new Vec3(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ());
            case EAST -> new Vec3(blockPos.getX() + 1, blockPos.getY(), blockPos.getZ() + 0.5);
            case WEST -> new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ() + 0.5);
            default -> new Vec3(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
        };
    }

    /**
     * Gets the track graph at a given location.
     *
     * @param level The server level
     * @param pos The position to check (should be calculated using getTrackNodePosition)
     * @return The track graph, or null if none found
     */
    @Nullable
    public static TrackGraph getGraph(ServerLevel level, Vec3 pos) {
        TrackNodeLocation location = new TrackNodeLocation(pos).in(level.dimension());
        return Create.RAILWAYS.getGraph(level, location);
    }

    /**
     * Finds a track node at the given location.
     *
     * @param graph The track graph
     * @param pos The position to check (center of track block)
     * @param level The server level
     * @return The track node, or null if none found
     */
    @Nullable
    public static TrackNode locateNode(TrackGraph graph, Vec3 pos, ServerLevel level) {
        TrackNodeLocation location = new TrackNodeLocation(pos).in(level.dimension());
        return graph.locateNode(location);
    }

    /**
     * Registers a train with the Create railway system.
     *
     * @param train The train to register
     */
    public static void registerTrain(Train train) {
        Create.RAILWAYS.addTrain(train);
    }

    /**
     * Initializes reflection to access Create's TrainPacket class.
     * Networking is now handled by platform-specific CreateHelper implementations.
     */
    private static void initializeNetworkReflection() {
        if (networkReflectionInitialized) return;

        synchronized (NETWORK_REFLECTION_LOCK) {
            // Double-check after acquiring lock
            if (networkReflectionInitialized) return;

            // Get TrainPacket class (Create 1.20.1 uses TrainPacket, not AddTrainPacket)
            try {
                trainPacketClass = Class.forName("com.simibubi.create.content.trains.entity.TrainPacket");
                // Constructor is TrainPacket(Train train, boolean add)
                trainPacketConstructor = trainPacketClass.getConstructor(Train.class, boolean.class);
            } catch (ClassNotFoundException e) {
                LOGGER.warn("[CREATE-TRAIN] Could not find TrainPacket class");
            } catch (NoSuchMethodException e) {
                LOGGER.warn("[CREATE-TRAIN] TrainPacket class found but no (Train, boolean) constructor");
            }

            networkReflectionInitialized = true;
        }
    }

    /**
     * Initializes the access to Create bogey blocks and styles.
     */
    @SuppressWarnings("unchecked")
    private static void initializeBogeyReflection() {
        if (bogeyReflectionInitialized) return;

        synchronized (BOGEY_REFLECTION_LOCK) {
            // Double-check after acquiring lock
            if (bogeyReflectionInitialized) return;

            try {
                Class<?> allBlocksClass = Class.forName("com.simibubi.create.AllBlocks");
                Field smallBogeyField = allBlocksClass.getField(CreateReflectionFields.SMALL_BOGEY);
                Object blockEntry = smallBogeyField.get(null);
                Method getMethod = blockEntry.getClass().getMethod("get");
                cachedSmallBogey = (AbstractBogeyBlock<?>) getMethod.invoke(blockEntry);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("[CREATE-TRAIN] Failed to get SMALL_BOGEY via reflection: {}", e.getMessage());
            }

            try {
                Class<?> allBlocksClass = Class.forName("com.simibubi.create.AllBlocks");
                Field largeBogeyField = allBlocksClass.getField(CreateReflectionFields.LARGE_BOGEY);
                Object blockEntry = largeBogeyField.get(null);
                Method getMethod = blockEntry.getClass().getMethod("get");
                cachedLargeBogey = (AbstractBogeyBlock<?>) getMethod.invoke(blockEntry);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("[CREATE-TRAIN] Failed to get LARGE_BOGEY via reflection: {}", e.getMessage());
            }

            try {
                Class<?> allBogeyStylesClass = Class.forName("com.simibubi.create.AllBogeyStyles");
                Field standardField = allBogeyStylesClass.getField(CreateReflectionFields.STANDARD);
                Object bogeyStyle = standardField.get(null);

                // Use CreateHelper to get the ID with proper field name handling
                cachedStandardStyleId = Services.CREATE.getBogeyStyleId(bogeyStyle);
                LOGGER.debug("[CREATE-TRAIN] Got BogeyStyle id: {}", cachedStandardStyleId);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.error("[CREATE-TRAIN] Failed to get STANDARD bogey style via reflection: {}", e.getMessage());
                // Default fallback
                cachedStandardStyleId = new ResourceLocation("create", "standard");
            }

            bogeyReflectionInitialized = true;
        }
    }

    /**
     * Sends the TrainPacket to all clients to sync the new train.
     * Uses platform-specific CreateHelper for networking abstraction.
     *
     * @param train The train to sync
     */
    public static void syncTrainToClients(Train train) {
        initializeNetworkReflection();

        if (trainPacketConstructor == null) {
            LOGGER.warn("[CREATE-TRAIN] Cannot send TrainPacket - TrainPacket class not found");
            return;
        }

        try {
            // Create TrainPacket with add=true
            Object packet = trainPacketConstructor.newInstance(train, true);

            // Use platform-specific CreateHelper to send the packet
            boolean sent = Services.CREATE.sendTrainPacketToAllClients(packet);
            if (!sent) {
                LOGGER.warn("[CREATE-TRAIN] Failed to send TrainPacket for train {}", train.id);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[CREATE-TRAIN] Failed to send TrainPacket: {}", e.getMessage(), e);
        }
    }

    /**
     * Registers a train and syncs it to all clients.
     *
     * @param train The train to register and sync
     */
    public static void registerAndSyncTrain(Train train) {
        try {
            registerTrain(train);
        } catch (RuntimeException e) {
            LOGGER.error("[CREATE-TRAIN] Failed to register train with RAILWAYS: {}", e.getMessage(), e);
        }

        try {
            syncTrainToClients(train);
        } catch (RuntimeException e) {
            LOGGER.error("[CREATE-TRAIN] Failed to sync train to clients: {}", e.getMessage(), e);
        }

        // Verify train was registered
        if (Create.RAILWAYS.trains.get(train.id) == null) {
            LOGGER.error("[CREATE-TRAIN] Train {} was NOT found in RAILWAYS after registration", train.id);
        }
    }

    /**
     * Gets the SMALL_BOGEY block from Create's AllBlocks.
     *
     * @return The small bogey block, or null if reflection failed
     */
    @Nullable
    public static AbstractBogeyBlock<?> getSmallBogeyBlock() {
        initializeBogeyReflection();
        return cachedSmallBogey;
    }

    /**
     * Gets the LARGE_BOGEY block from Create's AllBlocks.
     *
     * @return The large bogey block, or null if reflection failed
     */
    @Nullable
    public static AbstractBogeyBlock<?> getLargeBogeyBlock() {
        initializeBogeyReflection();
        return cachedLargeBogey;
    }

    /**
     * Gets the STANDARD bogey style ID from Create's AllBogeyStyles.
     *
     * @return The standard bogey style ResourceLocation, or fallback if reflection failed
     */
    @Nullable
    public static ResourceLocation getStandardBogeyStyleId() {
        initializeBogeyReflection();
        return cachedStandardStyleId;
    }

    /**
     * Gets a bogey block by its registry name.
     *
     * @param blockName The block registry name (e.g., "create:small_bogey")
     * @return The bogey block, or null if not found
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static AbstractBogeyBlock<?> getBogeyBlockByName(String blockName) {
        if (blockName == null) {
            return null;
        }

        try {
            ResourceLocation blockId = ResourceLocation.tryParse(blockName);
            if (blockId == null) {
                LOGGER.warn("[CREATE-TRAIN] Could not parse block name '{}' as ResourceLocation", blockName);
                return null;
            }

            var registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK;
            var optional = registry.getOptional(blockId);

            if (optional.isPresent() && optional.get() instanceof AbstractBogeyBlock<?> bogeyBlock) {
                return bogeyBlock;
            }

            // Fallback: check common bogey types via cached values
            if ("create:small_bogey".equals(blockName)) {
                return getSmallBogeyBlock();
            } else if ("create:large_bogey".equals(blockName)) {
                return getLargeBogeyBlock();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[CREATE-TRAIN] Failed to get bogey block for '{}': {}", blockName, e.getMessage());
        }

        LOGGER.warn("[CREATE-TRAIN] No bogey block found for '{}'", blockName);
        return null;
    }

    /**
     * Gets the track graph for a given track position and direction.
     *
     * @param level The server level
     * @param trackPos The block position of the track
     * @param trackDirection The direction the track runs
     * @return The track graph, or null if none found
     */
    @Nullable
    public static TrackGraph getGraphForTrack(ServerLevel level, BlockPos trackPos, Direction trackDirection) {
        Vec3 trackCenter = getTrackNodePosition(trackPos, trackDirection);
        return getGraph(level, trackCenter);
    }

}
