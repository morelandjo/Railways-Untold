package com.vodmordia.railwaysuntold.mixin;

import com.vodmordia.railwaysuntold.worldgen.terrain.harness.TestTerrainChunkGenerator;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.LevelStem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Swaps the overworld chunk generator for TestTerrainChunkGenerator when running under
 * GameTestServer, so gametests run against controllable, non-flat terrain instead of the hardcoded
 * flat preset. The planner reads terrain from the chunk generator (NoiseTerrainSampler), so only
 * replacing the generator makes terrain-dependent logic testable end-to-end.
 *
 * GameTestServer.create bakes the flat dimensions inside a nested lambda, an awkward injection
 * target. Instead this intercepts the overworld LevelStem just before MinecraftServer.createLevels
 * builds the ServerLevel from it, reusing the original stem's biome source. The injector is gated on
 * the server actually being a GameTestServer, so it is a no-op for client, server, and data runs and
 * for the published jar.
 */
@Mixin(MinecraftServer.class)
public abstract class GameTestTerrainMixin {

    // require = 0: this is a gametest-only convenience that targets a local variable in
    // MinecraftServer.createLevels. If another mod's transform or a vanilla change shifts that
    // local's layout, the injector simply does not apply (gametests fall back to the flat preset)
    // instead of hard-crashing every production launch - the mixin already no-ops at runtime for
    // any non-GameTestServer.
    @ModifyVariable(method = "createLevels", at = @At("STORE"), ordinal = 0, require = 0)
    private LevelStem railwaysuntold$swapGameTestGenerator(LevelStem stem) {
        if (!((Object) this instanceof GameTestServer)) {
            return stem;
        }
        return new LevelStem(stem.type(), new TestTerrainChunkGenerator(stem.generator().getBiomeSource()));
    }
}
