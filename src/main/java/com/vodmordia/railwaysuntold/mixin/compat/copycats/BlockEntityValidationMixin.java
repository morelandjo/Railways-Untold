package com.vodmordia.railwaysuntold.mixin.compat.copycats;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works around a Copycats+ crash on 1.21.1 where BlockEntity.validateBlockState() throws
 * IllegalStateException: Copycats+ MigrationManager.migrateStructure() creates a Create
 * CopycatBlockEntity to read old data with a copycats:* block state, and vanilla 1.21.1
 * rejects the mismatched block-entity-type / block-state pairing.
 *
 * <p>This lives in the optional copycats compat config (required = false) rather than the
 * required config, and short-circuits unless Copycats+ is actually installed, so installs
 * without it pay only a mod-presence check (not the registry lookup) per validation.
 */
@Mixin(BlockEntity.class)
public class BlockEntityValidationMixin {

    @Inject(method = "validateBlockState", at = @At("HEAD"), cancellable = true)
    private void railwaysuntold$skipCopycatsValidation(BlockState state, CallbackInfo ci) {
        if (!ModList.get().isLoaded("copycats")) {
            return;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId != null && "copycats".equals(blockId.getNamespace())) {
            ci.cancel();
        }
    }
}
