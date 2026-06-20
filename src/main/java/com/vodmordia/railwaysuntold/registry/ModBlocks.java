package com.vodmordia.railwaysuntold.registry;

import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.blocks.core.DeadEndBlock;
import com.vodmordia.railwaysuntold.blocks.core.StartBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(RailwaysUntold.MODID);

    public static final DeferredBlock<DeadEndBlock> DEAD_END =
            BLOCKS.register("dead_end",
                    () -> new DeadEndBlock(
                            BlockBehaviour.Properties.of()
                                    .noCollission()
                                    .noOcclusion()
                                    .instabreak()
                    ));

    public static final DeferredBlock<StartBlock> START =
            BLOCKS.register("start",
                    () -> new StartBlock(
                            BlockBehaviour.Properties.of()
                                    .noCollission()
                                    .noOcclusion()
                                    .instabreak()
                    ));

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
