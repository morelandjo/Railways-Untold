package com.vodmordia.railwaysuntold;

import com.vodmordia.railwaysuntold.registry.*;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredBlockEntityCreator;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredConnectionManager;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredSchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredTunnelFinisher;
import com.vodmordia.railwaysuntold.worldgen.placement.ChunkLoadTrackExpander;
import com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementInitializer;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.DeferredTerrainClearer;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RailwaysUntold {

    public static final Logger LOGGER = LoggerFactory.getLogger(RailwaysUntold.class);
    public static final String MOD_ID = "railwaysuntold";

    public static void init() {
        LOGGER.info("[RailwaysUntold] Initializing Railways Untold mod...");

        ModBlocks.register(); // Must be first
        ModItems.register();
        ModBlockEntities.register();
        ModMenuTypes.register();
        ModCreativeTabs.register();

        ChunkLoadTrackExpander.register();
        DeferredSchematicPlacer.register();
        DeferredBlockEntityCreator.register();
        DeferredConnectionManager.register();
        DeferredTunnelFinisher.register();
        DeferredTerrainClearer.register();
        TrackGraphReadyListener.register();
        RailwaysUntoldServerTickHandler.register();
        com.vodmordia.railwaysuntold.command.RailwaysUntoldCommands.register();
        com.vodmordia.railwaysuntold.worldgen.placement.companion.DeferredCompanionQueue.register();
        TrackPlacementInitializer.register();
        com.vodmordia.railwaysuntold.worldgen.survey.SurveyManager.register();
        com.vodmordia.railwaysuntold.worldgen.survey.SurveyExtractorRegistry.register(
                new com.vodmordia.railwaysuntold.worldgen.survey.extractor.StructureExtractor());

        ReloadListenerRegistry.register(PackType.SERVER_DATA, com.vodmordia.railwaysuntold.datapack.EventDefinitionLoader.INSTANCE);
        ReloadListenerRegistry.register(PackType.SERVER_DATA, com.vodmordia.railwaysuntold.datapack.StationDefinitionLoader.INSTANCE);
        ReloadListenerRegistry.register(PackType.SERVER_DATA, com.vodmordia.railwaysuntold.datapack.TrainDefinitionLoader.INSTANCE);
        ReloadListenerRegistry.register(PackType.SERVER_DATA, com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader.INSTANCE);

        RailwaysUntoldConfig.initialize();
        registerServerEvents();

        LOGGER.info("[RailwaysUntold] Railways Untold mod initialized - track network generation enabled");
    }

    private static void registerServerEvents() {
        LifecycleEvent.SERVER_STARTED.register(server -> {
            LOGGER.info("[RailwaysUntold] Server started - initializing systems");
            com.vodmordia.railwaysuntold.util.core.TrackLogger.setVerboseEnabled(
                    RailwaysUntoldConfig.isVerboseLoggingEnabled());
            com.vodmordia.railwaysuntold.config.RailwaysUntoldJsonConfig.load();
            TrackPlacementInitializer.clearAllSystemCaches();
            com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.initialize();
            // Never auto-resume the track-following chunk loader across worlds/sessions.
            com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.reset(server);
        });

        LifecycleEvent.SERVER_STOPPING.register(server -> {
            LOGGER.info("[RailwaysUntold] Server stopping - clearing all static state");
            com.vodmordia.railwaysuntold.util.core.SystemStateManager.markShuttingDown();
            // Stop the autoload chunk driver and release the corridor/deferred force-load tickets it
            // pinned. Otherwise the chunk system keeps generating/holding that moving band while the
            // world saves, hanging the shutdown. Must run before clearAllSystemCaches drops tracking.
            com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.disable(server, "server stopping");
            com.vodmordia.railwaysuntold.worldgen.placement.ChunkLoadTrackExpander.releaseAllHeldChunks(server);
            TrackPlacementInitializer.saveAllHeadState(server);
            TrackPlacementInitializer.clearAllSystemCaches();
        });
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
