package com.vodmordia.railwaysuntold.mixin.compat.framedblocks;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes FramedBlocks camo data lost when loading from Create contraptions.
 *
 * Create's contraption system stores block entity data via getUpdateTag() which
 * uses FramedBlocks' network format: {@code camo: {type: int, state: int}}.
 * But FramedBlocks' load() expects persistent format:
 * {@code camo: {type: "framedblocks:block", state: {Name:..., Properties:...}}}.
 *
 * This mixin converts the network format to persistent format in-place before
 * FramedBlocks' load() processes it, so the camo data is preserved.
 */
@Mixin(targets = "xfacthd.framedblocks.api.block.FramedBlockEntity", remap = false)
public class FramedBlockEntityMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject(method = {"m_142466_", "load"}, at = @At("HEAD"), require = 0)
    private void railwaysuntold$convertNetworkCamo(CompoundTag nbt, CallbackInfo ci) {
        if (!nbt.contains("camo", Tag.TAG_COMPOUND)) return;
        CompoundTag camo = nbt.getCompound("camo");

        // Only convert if "type" is an integer (network format).
        // Persistent format has "type" as a string - leave those alone.
        if (!camo.contains("type", Tag.TAG_INT)) return;

        int syncId = camo.getInt("type");
        if (syncId == 0 || !camo.contains("state", Tag.TAG_INT)) {
            // Empty camo in network format - remove so load() sees nothing
            nbt.remove("camo");
            return;
        }

        int stateId = camo.getInt("state");
        BlockState blockState = Block.stateById(stateId);

        if (blockState == null || blockState.isAir()) {
            LOGGER.warn("[FramedBlocksMixin] Could not resolve state ID {}, removing camo", stateId);
            nbt.remove("camo");
            return;
        }

        // Determine factory type from sync ID
        // In FramedBlocks: 1 = block, 2 = fluid
        String factoryType;
        if (syncId == 1) {
            factoryType = "framedblocks:block";
        } else if (syncId == 2) {
            factoryType = "framedblocks:fluid";
        } else {
            LOGGER.warn("[FramedBlocksMixin] Unknown camo sync ID {}, removing camo", syncId);
            nbt.remove("camo");
            return;
        }

        // Replace network-format camo with persistent format in-place
        CompoundTag newCamo = new CompoundTag();
        newCamo.putString("type", factoryType);
        newCamo.put("state", NbtUtils.writeBlockState(blockState));
        nbt.put("camo", newCamo);

    }
}
