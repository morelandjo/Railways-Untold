package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import net.minecraft.DetectedVersion;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Test harness seam for this branch.
 *
 * Config: {@link com.vodmordia.railwaysuntold.config.ModConfigHolder#getConfig()}
 * returns a fresh {@code new ModConfig()} with production defaults whenever
 * AutoConfig has not been registered, so config-backed accessors already resolve
 * to defaults in a test JVM (no explicit config load needed, unlike 1.21.1).
 *
 * Minecraft: the bare Loom test JVM has no mod loader, so vanilla registries are
 * not initialized. The route compiler touches {@code BuiltInRegistries.BLOCK}
 * (via CreateTrackUtil) when evaluating curve segments, which fails until
 * {@link Bootstrap#bootStrap()} runs. The 1.21.1 branch gets this for free from
 * the NeoForge JUnit launcher.
 */
public final class PlannerTestConfig {

    private static boolean bootstrapped = false;

    private PlannerTestConfig() {}

    public static synchronized void bootstrapDefaults() {
        if (bootstrapped) return;
        SharedConstants.setVersion(DetectedVersion.BUILT_IN);
        Bootstrap.bootStrap();
        bootstrapped = true;
    }
}
