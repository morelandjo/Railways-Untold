package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, RailwaysUntold.MODID);

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
