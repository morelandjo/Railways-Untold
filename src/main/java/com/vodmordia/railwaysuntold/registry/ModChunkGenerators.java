package com.vodmordia.railwaysuntold.registry;

import com.mojang.serialization.MapCodec;
import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.worldgen.terrain.harness.TestTerrainChunkGenerator;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registers the test terrain generator's codec so it is a first-class chunk generator type. The
 * generator is only ever instantiated by GameTestTerrainMixin in the gametest world; this registry
 * entry just lets that type round-trip through the chunk-generator registry.
 */
public class ModChunkGenerators {

    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, RailwaysUntold.MODID);

    public static final DeferredHolder<MapCodec<? extends ChunkGenerator>, MapCodec<TestTerrainChunkGenerator>> TEST_TERRAIN =
            CHUNK_GENERATORS.register("test_terrain", () -> TestTerrainChunkGenerator.CODEC);

    public static void register(IEventBus eventBus) {
        CHUNK_GENERATORS.register(eventBus);
    }
}
