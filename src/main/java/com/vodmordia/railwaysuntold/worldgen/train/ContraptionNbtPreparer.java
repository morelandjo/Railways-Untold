package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.railway.RailwayModCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-{@code readNBT} transforms on a captured carriage contraption's NBT: normalizing seat position
 * encodings, reading the stored assembly direction, shifting the leading bogey to the origin, swapping
 * unresolvable bogey blocks in the palette for the gauge-appropriate one, and identifying bogey blocks.
 * All operate purely on the NBT tree (no world).
 */
public final class ContraptionNbtPreparer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private ContraptionNbtPreparer() {
    }

    /**
     * Replaces unresolvable bogey blocks in the contraption NBT palette with the gauge-appropriate bogey.
     */
    public static void replaceBogeyBlocksInContraptionNbt(CompoundTag contraptionNbt, AbstractBogeyBlock<?> targetBogeyType) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) return;

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)) return;

        ResourceLocation targetId = BuiltInRegistries.BLOCK.getKey(targetBogeyType);
        String targetName = targetId.toString();

        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        Set<String> replacedNames = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            String name = entry.getString(ContraptionNbtKeys.NAME);
            if (isBogeyBlockName(name) && !name.equals(targetName)) {
                AbstractBogeyBlock<?> resolved = CreateTrainUtils.getBogeyBlockByName(name);
                if (resolved == null) {
                    replacedNames.add(name);
                    entry.putString(ContraptionNbtKeys.NAME, targetName);
                    LOGGER.debug("[TRAIN-BUILD] Replaced unresolvable bogey '{}' with '{}' in palette", name, targetName);
                }
            }
        }

        if (replacedNames.isEmpty()) return;
        if (!blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) return;

        List<String> paletteNames = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            paletteNames.add(palette.getCompound(i).getString(ContraptionNbtKeys.NAME));
        }

        ResourceLocation styleId = RailwayModCompat.getDefaultBogeyStyleForGauge();
        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockEntry = blockList.getCompound(i);
            int stateIndex = blockEntry.getInt(ContraptionNbtKeys.STATE);
            if (stateIndex < 0 || stateIndex >= paletteNames.size()) continue;

            String blockName = paletteNames.get(stateIndex);
            if (!blockName.equals(targetName)) continue;

            if (blockEntry.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)) {
                CompoundTag data = blockEntry.getCompound(ContraptionNbtKeys.DATA);
                if (data.contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND) && styleId != null) {
                    CompoundTag bogeyData = data.getCompound(ContraptionNbtKeys.BOGEY_DATA);
                    bogeyData.putString(ContraptionNbtKeys.BOGEY_STYLE, styleId.toString());
                }
            }
        }
    }

    public static boolean isBogeyBlockName(String name) {
        if (name == null) return false;
        ResourceLocation blockId = ResourceLocation.tryParse(name);
        if (blockId != null) {
            var block = BuiltInRegistries.BLOCK.get(blockId);
            if (block instanceof AbstractBogeyBlock<?>) {
                return true;
            }
        }
        return name.contains("bogey");
    }

    /**
     * Finds the leading bogey (smallest assembly-axis position) in the contraption NBT and
     * delegates to {@link ContraptionNbtShifter} to shift the whole contraption so that
     * bogey lands at the origin.
     */
    public static void shiftContraptionToLeadingBogey(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) return;

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)
                || !blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) return;

        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        Set<Integer> bogeyPaletteIndices = new HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString(ContraptionNbtKeys.NAME);
            if (isBogeyBlockName(name)) {
                bogeyPaletteIndices.add(i);
            }
        }
        if (bogeyPaletteIndices.isEmpty()) return;

        Direction assemblyDir = extractAssemblyDirectionFromNbt(contraptionNbt);

        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        BlockPos leadingBogeyPos = null;
        int leadingValue = Integer.MAX_VALUE;

        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            int stateIdx = entry.getInt(ContraptionNbtKeys.STATE);
            if (bogeyPaletteIndices.contains(stateIdx) && entry.contains(ContraptionNbtKeys.POS, Tag.TAG_LONG)) {
                BlockPos pos = BlockPos.of(entry.getLong(ContraptionNbtKeys.POS));
                int val = DirectionUtil.getSignedPositionAlongAxis(pos, assemblyDir);
                if (val < leadingValue) {
                    leadingValue = val;
                    leadingBogeyPos = pos;
                }
            }
        }

        if (leadingBogeyPos == null) return;
        ContraptionNbtShifter.shiftContraption(contraptionNbt, -leadingBogeyPos.getX(), -leadingBogeyPos.getZ());
    }

    public static Direction extractAssemblyDirectionFromNbt(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains("AssemblyDirection")) {
            return Direction.NORTH;
        }
        try {
            return Direction.valueOf(contraptionNbt.getString("AssemblyDirection").toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.NORTH;
        }
    }

    /**
     * Converts seat positions to the direct {X, Y, Z} format that Create expects.
     */
    public static void convertIntArrayPosToCompoundTag(CompoundTag contraptionNbt) {
        if (contraptionNbt.contains("Seats", 9)) {
            convertSeatsToDirectBlockPos(contraptionNbt, "Seats");
        }
    }

    private static void convertSeatsToDirectBlockPos(CompoundTag parent, String listName) {
        ListTag list = parent.getList(listName, 10);
        if (list.isEmpty()) return;

        CompoundTag first = list.getCompound(0);
        if (!first.contains("Pos")) return;

        ListTag newList = new ListTag();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);

            int x, y, z;
            if (entry.contains("Pos", 11)) {
                int[] arr = entry.getIntArray("Pos");
                if (arr.length < 3) continue;
                x = arr[0]; y = arr[1]; z = arr[2];
            } else if (entry.contains("Pos", 10)) {
                CompoundTag posTag = entry.getCompound("Pos");
                x = posTag.contains("X") ? posTag.getInt("X") : posTag.getInt("x");
                y = posTag.contains("Y") ? posTag.getInt("Y") : posTag.getInt("y");
                z = posTag.contains("Z") ? posTag.getInt("Z") : posTag.getInt("z");
            } else {
                continue;
            }

            CompoundTag posTag = new CompoundTag();
            posTag.putInt("X", x);
            posTag.putInt("Y", y);
            posTag.putInt("Z", z);
            newList.add(posTag);
        }

        parent.put(listName, newList);
    }
}
