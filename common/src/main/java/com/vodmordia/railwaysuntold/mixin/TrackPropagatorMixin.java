package com.vodmordia.railwaysuntold.mixin;

import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.track.TrackPropagator;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.PropagationSuppressor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Intercepts Create's TrackPropagator.onRailAdded() to prevent destructive graph rebuilds
 * during batch placement by DirectGraphConnector.
 *
 * Create's onRailAdded() removes ALL reachable nodes and rebuilds from scratch.
 * If a train is currently on those nodes, it detaches and briefly shows "derailed".
 *
 * This mixin only suppresses during batch mode (PropagationSuppressor.isSuppressed()),
 * when the caller will add graph connections via DirectGraphConnector after placement.
 * Outside of batch mode, Create handles graph building normally - this allows Create
 * to self-heal any graph inconsistencies when tracks are placed by other means.
 */
@Mixin(value = TrackPropagator.class, remap = false)
public class TrackPropagatorMixin {

    @Inject(method = "onRailAdded", at = @At("HEAD"), cancellable = true)
    private static void railwaysuntold$interceptOnRailAdded(LevelAccessor reader, BlockPos pos,
                                                             BlockState state,
                                                             CallbackInfoReturnable<TrackGraph> cir) {
        // Only suppress during batch placement - caller will add graph connections
        // via DirectGraphConnector after all blocks are placed.
        if (PropagationSuppressor.isSuppressed()) {
            cir.setReturnValue(null);
            return;
        }
        // Outside of batch mode, let Create handle graph building normally.
        // This allows Create to self-heal graph issues and properly handle
        // tracks placed by players or other mods.
    }
}
