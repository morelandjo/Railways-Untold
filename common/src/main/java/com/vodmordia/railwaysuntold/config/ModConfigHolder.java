package com.vodmordia.railwaysuntold.config;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;

/**
 * Holds the config instance and provides access to it.
 * Initialized by platform-specific entry points.
 */
public class ModConfigHolder {

    private static ConfigHolder<ModConfig> holder;
    private static boolean initialized = false;

    public static void register() {
        if (initialized) {
            return;
        }
        holder = AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
        initialized = true;
    }

    /** Returns a default instance if not yet registered */
    public static ModConfig getConfig() {
        if (!initialized || holder == null) {
            return new ModConfig();
        }
        return holder.getConfig();
    }

    public static ConfigHolder<ModConfig> getHolder() {
        return holder;
    }

    public static void save() {
        if (holder != null) {
            holder.save();
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
