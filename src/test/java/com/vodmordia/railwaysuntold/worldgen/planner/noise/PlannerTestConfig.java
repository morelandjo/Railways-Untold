package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.vodmordia.railwaysuntold.Config;
import net.neoforged.fml.config.IConfigSpec;

import java.lang.reflect.Constructor;

/**
 * Loads {@link Config#SPEC} with its production defaults so config-backed
 * accessors resolve in a unit test. Without this the ModConfigSpec values
 * throw when read before a world loads them.
 *
 * The only permitted {@link IConfigSpec.ILoadedConfig} is the package-private
 * record {@code net.neoforged.fml.config.LoadedConfig}, so it is built
 * reflectively; the path/modConfig fields are unused by read-only access.
 */
public final class PlannerTestConfig {

    private static boolean loaded = false;

    private PlannerTestConfig() {}

    public static synchronized void bootstrapDefaults() {
        if (loaded) return;
        CommentedConfig data = CommentedConfig.inMemory();
        Config.SPEC.correct(data);
        Config.SPEC.acceptConfig(newLoadedConfig(data));
        loaded = true;
    }

    private static IConfigSpec.ILoadedConfig newLoadedConfig(CommentedConfig data) {
        try {
            Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig");
            Constructor<?> ctor = null;
            for (Constructor<?> c : loadedConfigClass.getDeclaredConstructors()) {
                if (c.getParameterCount() == 3) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                throw new IllegalStateException("LoadedConfig has no 3-arg constructor");
            }
            ctor.setAccessible(true);
            return (IConfigSpec.ILoadedConfig) ctor.newInstance(data, null, null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to bootstrap Config.SPEC for tests", e);
        }
    }
}
