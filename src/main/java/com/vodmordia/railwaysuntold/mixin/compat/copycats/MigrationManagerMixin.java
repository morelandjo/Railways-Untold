package com.vodmordia.railwaysuntold.mixin.compat.copycats;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fixes Copycats+ MigrationManager.migrateStructure() crashing when loading contraption NBT.
 *
 * The migration creates temp BlockEntities via BlockEntityType.create(pos, state) which never
 * sets a level, then calls be.getLevel().registryAccess() causing NPE. This is a Copycats+ bug
 * that affects ALL contraption NBT loading, not just ours.
 *
 * Fix: skip the migration entirely. The block data is preserved as-is in the contraption NBT.
 * Material data remains intact in the StructureBlockInfo nbt.
 */
@Mixin(targets = "com.copycatsplus.copycats.foundation.copycat.MigrationManager", remap = false)
public class MigrationManagerMixin {

    @Inject(method = "migrateStructure", at = @At("HEAD"), cancellable = true, require = 0)
    private static void railwaysuntold$skipBrokenMigration(StructureBlockInfo info,
                                                            CallbackInfoReturnable<StructureBlockInfo> cir) {
        cir.setReturnValue(info);
    }
}
