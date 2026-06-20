package com.vodmordia.railwaysuntold.forge;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.client.ClientSetup;
import com.vodmordia.railwaysuntold.config.ModConfig;
import com.vodmordia.railwaysuntold.config.ModConfigHolder;
import com.vodmordia.railwaysuntold.worldgen.train.TrackGraphReadyListener;
import dev.architectury.platform.forge.EventBuses;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge-specific entry point for Railways Untold mod.
 */
@Mod(RailwaysUntold.MOD_ID)
public class ForgeRailwaysUntold {

    public ForgeRailwaysUntold() {
        // Register mod event bus with Architectury (required before registry calls)
        EventBuses.registerModEventBus(RailwaysUntold.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());

        // Register config first (before mod initialization)
        ModConfigHolder.register();

        // Initialize the mod
        RailwaysUntold.init();

        // Register Create mod events (Forge-specific)
        TrackGraphReadyListener.registerCreateEvents(() ->
            MinecraftForge.EVENT_BUS.addListener(TrackGraphReadyListener::onTrackGraphMerge));

        // Register client setup event (deferred until registries are ready)
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);

            // Register config screen for Forge Mods menu
            ModLoadingContext.get().registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory(
                    (mc, parent) -> AutoConfig.getConfigScreen(ModConfig.class, parent).get()
                )
            );
        });
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        // Initialize client-side components after registries are ready
        ClientSetup.initialize();
    }
}
