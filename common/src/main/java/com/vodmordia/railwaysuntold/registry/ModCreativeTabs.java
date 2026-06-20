package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import dev.architectury.registry.CreativeTabRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(RailwaysUntold.MOD_ID, Registries.CREATIVE_MODE_TAB);

    public static final RegistrySupplier<CreativeModeTab> TRAINWORLD_TAB = TABS.register(
        RailwaysUntold.MOD_ID,
        () -> CreativeTabRegistry.create(
            Component.translatable("itemGroup." + RailwaysUntold.MOD_ID),
            () -> new ItemStack(ModItems.START_ITEM.get())
        )
    );

    public static void register() {
        TABS.register();
    }
}
