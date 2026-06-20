package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.blocks.core.DeadEndBlock;
import com.vodmordia.railwaysuntold.blocks.core.StartBlock;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(RailwaysUntold.MOD_ID, Registries.BLOCK);

    public static final RegistrySupplier<DeadEndBlock> DEAD_END = BLOCKS.register(
        "dead_end",
        () -> new DeadEndBlock(
                BlockBehaviour.Properties.of()
                        .noCollission()
                        .noOcclusion()
                        .instabreak()
        ));

    public static final RegistrySupplier<StartBlock> START = BLOCKS.register(
        "start",
        () -> new StartBlock(
                BlockBehaviour.Properties.of()
                        .noCollission()
                        .noOcclusion()
                        .instabreak()
        ));

    public static void register() {
        BLOCKS.register();
    }
}
