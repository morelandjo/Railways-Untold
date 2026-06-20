package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(RailwaysUntold.MOD_ID, Registries.ITEM);

    public static final RegistrySupplier<BlockItem> DEAD_END_ITEM = ITEMS.register(
        "dead_end",
        () -> new BlockItem(ModBlocks.DEAD_END.get(), new Item.Properties().arch$tab(ModCreativeTabs.TRAINWORLD_TAB))
    );

    public static final RegistrySupplier<BlockItem> START_ITEM = ITEMS.register(
        "start",
        () -> new BlockItem(ModBlocks.START.get(), new Item.Properties().arch$tab(ModCreativeTabs.TRAINWORLD_TAB))
    );

    public static void register() {
        ITEMS.register();
    }
}
