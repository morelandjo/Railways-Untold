package com.vodmordia.railwaysuntold;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.registry.*;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

@Mod(RailwaysUntold.MODID)
public class RailwaysUntold {
    public static final String MODID = "railwaysuntold";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RailwaysUntold(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC, "railways-untold/config.toml");

        if (FMLEnvironment.dist.isClient()) {
            com.vodmordia.railwaysuntold.client.ConfigScreenRegistration.register(modContainer);
        }

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModChunkGenerators.register(modEventBus);

        // Register region-survey extractors (pluggable ground-truth producers).
        com.vodmordia.railwaysuntold.worldgen.survey.SurveyExtractorRegistry.register(
                new com.vodmordia.railwaysuntold.worldgen.survey.extractor.StructureExtractor());
    }

    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(com.vodmordia.railwaysuntold.datapack.EventDefinitionLoader.INSTANCE);
        event.addListener(com.vodmordia.railwaysuntold.datapack.StationDefinitionLoader.INSTANCE);
        event.addListener(com.vodmordia.railwaysuntold.datapack.TrainDefinitionLoader.INSTANCE);
        event.addListener(com.vodmordia.railwaysuntold.datapack.BiomeSettingsLoader.INSTANCE);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        com.vodmordia.railwaysuntold.util.core.TrackLogger.setVerboseEnabled(
                com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig.isVerboseLoggingEnabled());
        com.vodmordia.railwaysuntold.config.RailwaysUntoldJsonConfig.load();
        com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementInitializer.clearAllSystemCaches();
        com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat.initialize();
        // Never auto-resume the track-following chunk loader across worlds/sessions.
        com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.reset(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        com.vodmordia.railwaysuntold.util.core.SystemStateManager.markShuttingDown();
        // Stop the autoload chunk driver and release the corridor/deferred force-load tickets it
        // pinned. Otherwise the chunk system keeps generating/holding that moving band while the
        // world saves, hanging the shutdown. Must run before clearAllSystemCaches drops tracking.
        com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController.disable(event.getServer(), "server stopping");
        com.vodmordia.railwaysuntold.worldgen.placement.ChunkLoadTrackExpander.releaseAllHeldChunks(event.getServer());
        com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementInitializer.saveAllHeadState(event.getServer());
        com.vodmordia.railwaysuntold.worldgen.placement.TrackPlacementInitializer.clearAllSystemCaches();
    }
}
