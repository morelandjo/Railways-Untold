package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers all items for the Railways Untold mod.
 */
public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(RailwaysUntold.MODID);

    public static final DeferredItem<BlockItem> DEAD_END =
            ITEMS.registerSimpleBlockItem(ModBlocks.DEAD_END);

    public static final DeferredItem<BlockItem> START =
            ITEMS.registerSimpleBlockItem(ModBlocks.START);

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
