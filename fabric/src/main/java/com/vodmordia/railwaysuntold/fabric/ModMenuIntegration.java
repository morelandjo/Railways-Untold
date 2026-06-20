package com.vodmordia.railwaysuntold.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.vodmordia.railwaysuntold.config.ModConfig;
import me.shedaniel.autoconfig.AutoConfig;

/**
 * ModMenu integration for Railways Untold config screen.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> AutoConfig.getConfigScreen(ModConfig.class, parent).get();
    }
}
