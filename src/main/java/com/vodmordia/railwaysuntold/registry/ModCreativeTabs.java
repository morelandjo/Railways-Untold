package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers creative mode tabs for the Railways Untold mod.
 */
public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RailwaysUntold.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> RAILWAYSUNTOLD_TAB =
            CREATIVE_MODE_TABS.register("railwaysuntold_tab", () ->
                    CreativeModeTab.builder()
                            .title(Component.translatable("itemGroup." + RailwaysUntold.MODID))
                            .icon(() -> new ItemStack(ModItems.START.get()))
                            .displayItems((params, output) -> {
                                output.accept(ModItems.DEAD_END.get());
                                output.accept(ModItems.START.get());
                            })
                            .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
