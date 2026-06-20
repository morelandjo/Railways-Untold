package com.vodmordia.railwaysuntold.mixin;

import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collections;
import java.util.Map;

/**
 * Prevents NPE crash in Navigation.search() when getConnectionsFrom() is called with a null node.
 * Create returns null for null nodes, but Navigation.search() doesn't null-check the result.
 */
@Mixin(value = TrackGraph.class, remap = false)
public abstract class TrackGraphMixin {

    @Inject(method = "getConnectionsFrom", at = @At("HEAD"), cancellable = true)
    private void railwaysuntold$safeGetConnectionsFrom(TrackNode node, CallbackInfoReturnable<Map<TrackNode, TrackEdge>> cir) {
        if (node == null) {
            cir.setReturnValue(Collections.emptyMap());
        }
    }
}
