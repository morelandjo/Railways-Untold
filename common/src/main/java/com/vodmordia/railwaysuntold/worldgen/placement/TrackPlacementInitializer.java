package com.vodmordia.railwaysuntold.worldgen.placement;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.RailwaysUntoldServerTickHandler;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.TrainDefinitionLoader;
import com.vodmordia.railwaysuntold.util.core.SystemStateManager;
import com.vodmordia.railwaysuntold.util.core.TrackLogger;
import com.vodmordia.railwaysuntold.worldgen.head.InitialChunkSelector;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredBlockEntityCreator;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredTunnelFinisher;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.StartingTrainPlacer;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import com.vodmordia.railwaysuntold.worldgen.village.StationSchematicCache;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLayoutPredictor;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLocator;
import com.vodmordia.railwaysuntold.worldgen.village.util.StructureTagResolver;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;

import java.util.List;

/** Initializes progressive track placement when a player joins the world. */
public class TrackPlacementInitializer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static RailwaysUntoldConfig getConfig() {
        return RailwaysUntoldConfig.getDefault();
    }
    private static boolean systemStateRegistered = false;

    public static void register() {
        if (!systemStateRegistered) {
            registerSystemStateComponents();
            systemStateRegistered = true;
        }
        PlayerEvent.PLAYER_JOIN.register(TrackPlacementInitializer::onPlayerLogin);
    }

    private static void registerSystemStateComponents() {
        SystemStateManager.register("ConnectedBoundaryTracker", ConnectedBoundaryTracker::clearAll);
        SystemStateManager.register("ChunkLoadTrackExpander", ChunkLoadTrackExpander::clearAllPlacersAndWaitingChunks);
        SystemStateManager.register("SurveyManager", com.vodmordia.railwaysuntold.worldgen.survey.SurveyManager::clearAll);
        SystemStateManager.register("DeferredConnectionManager", DeferredConnectionManager::clearQueue);
        SystemStateManager.register("DeferredBlockEntityCreator", DeferredBlockEntityCreator::clearPending);
        SystemStateManager.register("RailwaysUntoldServerTickHandler", RailwaysUntoldServerTickHandler::clearDelayedTasks);
        SystemStateManager.register("VillageLocator", VillageLocator::clearCache);
        SystemStateManager.register("StartingTrainPlacer", StartingTrainPlacer::clearCache);
        // TrainDefinitionLoader, StationDefinitionLoader, EventDefinitionLoader managed by reload listeners
        SystemStateManager.register("TrackGraphReadyListener", TrackGraphReadyListener::clearPending);
        SystemStateManager.register("StationSchematicCache", StationSchematicCache::clearCache);
        SystemStateManager.register("VillageLayoutPredictor", VillageLayoutPredictor::clearCache);
        SystemStateManager.register("StructureTagResolver", StructureTagResolver::clearCache);
        SystemStateManager.register("DeferredSchematicPlacer", DeferredSchematicPlacer::clearAll);
        SystemStateManager.register("DeferredTerrainClearer", DeferredTerrainClearer::clearAll);
        SystemStateManager.register("DeferredTunnelFinisher", DeferredTunnelFinisher::clearAll);
        SystemStateManager.register("DeferredCompanionQueue", com.vodmordia.railwaysuntold.worldgen.placement.companion.DeferredCompanionQueue::clearAll);
        SystemStateManager.register("TrackPlacerRegistry", TrackPlacerRegistry::clear);
        SystemStateManager.register("ParallelConvergeRegistry", ParallelConvergeRegistry::clearAll);
        SystemStateManager.register("AtGradeCrossingRegistry", com.vodmordia.railwaysuntold.worldgen.planner.noise.AtGradeCrossingRegistry::clearAll);
        SystemStateManager.register("ForcedCrossingRegistry", com.vodmordia.railwaysuntold.worldgen.planner.noise.ForcedCrossingRegistry::clearAll);
        // Station, event, and train definition loaders are managed by data pack reload lifecycle
        SystemStateManager.register("CoarseRouteFactory", com.vodmordia.railwaysuntold.worldgen.planner.noise.CoarseRouteFactory::shutdown);
        SystemStateManager.register("PropagationSuppressor", com.vodmordia.railwaysuntold.worldgen.integration.create.util.PropagationSuppressor::clear);
        SystemStateManager.register("ChunkSafeBlockAccess", com.vodmordia.railwaysuntold.util.block.ChunkSafeBlockAccess::clearCache);
    }

    private static void onPlayerLogin(ServerPlayer player) {
        if (!getConfig().TRACKS_ENABLED) {
            return;
        }

        ServerLevel level = player.serverLevel();

        if (!level.dimension().location().toString().equals("minecraft:overworld")) {
            return;
        }

        if (TrackLogger.isVerboseEnabled()) {
            LOGGER.info("[SEED] world seed {}", level.getSeed());
        }

        List<? extends String> allowedWorldTypes = RailwaysUntoldConfig.getGenerateInWorldTypes();
        if (!WorldTypeDetector.isWorldTypeAllowed(level, allowedWorldTypes)) {
            return;
        }

        notifyCustomTrainErrors(player);

        TrackPlacementSavedData savedData = TrackPlacementSavedData.get(level);
        if (savedData.isInitiated()) {
            rescueOrphanedHeads(level);
            return;
        }


        level.getServer().execute(() -> {
            try {
                BlockPos worldSpawn = level.getSharedSpawnPos();
                InitialChunkSelector selector = new InitialChunkSelector(level.getSeed(), worldSpawn);
                ChunkPos initialChunk = selector.getInitialChunk();
                Direction trackDirection = selector.getInitialDirection();

                initialChunk = avoidNearbyVillage(level, initialChunk, trackDirection);


                TrackExpansionOrchestrator placer = new TrackExpansionOrchestrator(level, getConfig(), trackDirection, savedData, worldSpawn);
                TrackPlacerRegistry.register(level, placer);
                savedData.markInitiated(trackDirection);

                placer.expandFromInitialChunk(initialChunk);
            } catch (RuntimeException e) {
                LOGGER.error("[PROGRESSIVE-INIT] Error during track placement: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Rescues heads that may have been orphaned during restoration.
     */
    private static void rescueOrphanedHeads(ServerLevel level) {
        TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
        if (placer == null || placer.isComplete()) {
            return;
        }

        level.getServer().execute(() -> {
            for (var head : placer.getHeadManager().getActiveHeads()) {
                if (!head.isComplete()) {
                    if (head.isPaused()) {
                        head.resume();
                    }
                    placer.scheduleHeadExpansion(head, PlacementConstants.MIN_EXPANSION_DELAY_TICKS,
                            HeadScheduler.ScheduleCaller.INITIAL);
                }
            }
        });
    }

    /**
     * Saves head state for all active orchestrators before the server shuts down.
     * Must be called BEFORE clearAllSystemCaches() to capture final head positions.
     */
    public static void saveAllHeadState(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            TrackExpansionOrchestrator placer = TrackPlacerRegistry.get(level);
            if (placer != null) {
                TrackPlacementSavedData savedData = TrackPlacementSavedData.get(level);
                placer.getHeadManager().saveState(savedData);
            }
        }
    }

    public static void clearAllSystemCaches() {
        SystemStateManager.clearAll();
    }

    /**
     * Shifts the initial chunk perpendicular to the track direction so the starting corridor
     * clears any village whose footprint sits across it. Uses the real jigsaw footprints (the
     * same layout the route planner trusts) rather than the biome-sampled prediction, which
     * misses villages whose origin-chunk center reads as a non-village biome.
     */
    private static ChunkPos avoidNearbyVillage(ServerLevel level, ChunkPos initialChunk, Direction trackDirection) {
        // Search radius in blocks - covers villages up to ~5 chunks away
        int searchRadius = 80;

        BlockPos origin = initialChunk.getWorldPosition().offset(8, 0, 8);
        List<BoundingBox> footprints =
                VillageLocator.placedVillageFootprintsInRadius(level, origin, searchRadius);

        BlockPos nudged = StationOriginNudge.resolve(origin, trackDirection, footprints,
                pos -> level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                        pos.getX(), pos.getZ()));
        LOGGER.info("[ORIGIN-NUDGE] origin {},{} dir {} villages {} -> {},{}",
                origin.getX(), origin.getZ(), trackDirection, footprints.size(),
                nudged.getX(), nudged.getZ());
        if (nudged.equals(origin)) {
            return initialChunk;
        }
        return new ChunkPos(nudged);
    }

    /**
     * Notifies the player of any custom train loading errors.
     */
    private static void notifyCustomTrainErrors(ServerPlayer player) {
        if (!TrainDefinitionLoader.INSTANCE.hasLoadErrors()) {
            return;
        }

        List<String> errors = TrainDefinitionLoader.INSTANCE.getLoadErrors();
        player.sendSystemMessage(Component.literal("[Railways Untold] Custom train import errors:")
                .withStyle(ChatFormatting.RED));

        for (String error : errors) {
            player.sendSystemMessage(Component.literal("  - " + error)
                    .withStyle(ChatFormatting.YELLOW));
        }

        player.sendSystemMessage(Component.literal("[Railways Untold] Using default train instead.")
                .withStyle(ChatFormatting.GRAY));
    }
}
