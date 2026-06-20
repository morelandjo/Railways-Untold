package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Copycats+ block-entity handling for Create contraptions.
 *
 */
public final class CopycatContraptionHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private CopycatContraptionHandler() {}

    /**
     * Transforms copycat block entity data (Material, material_data) in an assembled contraption
     * to match the block state rotation applied by the virtual assembler.
     *
     * Uses Create's StructureTransform to rotate material BlockStates, with manual face key
     * remapping as a fallback when the Copycats+ block's own transformStorage isn't available.
     *
     * @param updateTags the contraption's {@code updateTags} map (may be null if inaccessible)
     */
    public static void transformBlockEntities(CarriageContraption contraption,
                                               Map<BlockPos, CompoundTag> updateTags,
                                               int cwSteps, ServerLevel level) {
        Rotation rot = ContraptionNbtRotator.cwStepsToRotation(cwSteps);
        StructureTransform transform = new StructureTransform(
                BlockPos.ZERO, Direction.Axis.Y, rot, Mirror.NONE);
        HolderGetter<Block> blockGetter = BuiltInRegistries.BLOCK.asLookup();

        Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks = contraption.getBlocks();

        for (Map.Entry<BlockPos, StructureTemplate.StructureBlockInfo> entry
                : new ArrayList<>(blocks.entrySet())) {
            StructureTemplate.StructureBlockInfo info = entry.getValue();
            CompoundTag nbt = info.nbt();
            if (nbt == null) continue;

            boolean modified = false;

            // Multi-state copycats: use Copycats+' own transformStorage to remap
            // material sections. Each block type (byte, byte_panel, slab, etc.) has
            // its own face-relative key mapping that only the block itself knows.
            if (nbt.contains("material_data", Tag.TAG_COMPOUND)
                    && info.state().getBlock() instanceof EntityBlock entityBlock) {
                try {
                    BlockEntity tempBe = entityBlock.newBlockEntity(info.pos(), info.state());
                    if (tempBe != null) {
                        tempBe.setLevel(level);
                        CompoundTag loadTag = nbt.copy();
                        loadTag.putInt("x", info.pos().getX());
                        loadTag.putInt("y", info.pos().getY());
                        loadTag.putInt("z", info.pos().getZ());
                        // Fix ItemStack format: 1.20.5+ uses lowercase "count", 1.20.1 expects "Count"
                        com.vodmordia.railwaysuntold.util.nbt.NbtHelper.normalizeItemStackTags(loadTag);
                        tempBe.load(loadTag);

                        Block block = info.state().getBlock();
                        try {
                            java.lang.reflect.Method transformStorageMethod = block.getClass().getMethod(
                                    "transformStorage", BlockState.class,
                                    Class.forName("com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity"),
                                    StructureTransform.class);
                            // Pass the CURRENT (already rotated) state - transformStorage
                            // uses the original facing to determine how to remap sections
                            transformStorageMethod.invoke(block, info.state(), tempBe, transform);
                        } catch (NoSuchMethodException e) {
                            // Not a multi-state copycat block, skip
                        }

                        CompoundTag savedTag = tempBe.saveWithoutMetadata();
                        savedTag.putString("id", nbt.getString("id"));

                        blocks.put(entry.getKey(), new StructureTemplate.StructureBlockInfo(
                                info.pos(), info.state(), savedTag));
                        if (updateTags != null) {
                            updateTags.put(entry.getKey(), stripMetadataKeys(savedTag));
                        }
                        modified = true;
                    }
                } catch (Exception e) {
                    LOGGER.warn("[TRAIN-BUILD] Failed to transformStorage for {} at {}: {}",
                            BuiltInRegistries.BLOCK.getKey(info.state().getBlock()), info.pos(), e.getMessage(), e);
                    // Fallback: just rotate material BlockStates without remapping keys
                    CompoundTag rotatedNbt = nbt.copy();
                    CompoundTag materialData = rotatedNbt.getCompound("material_data");
                    for (String key : materialData.getAllKeys()) {
                        CompoundTag faceEntry = materialData.getCompound(key);
                        if (faceEntry.contains("material", Tag.TAG_COMPOUND)) {
                            faceEntry.put("material",
                                    transformMaterialTag(faceEntry.getCompound("material"), transform, blockGetter));
                        }
                    }
                    ContraptionNbtRotator.remapMultiStateFaceKeys(materialData, cwSteps);
                    rotatedNbt.put("material_data", materialData);
                    blocks.put(entry.getKey(), new StructureTemplate.StructureBlockInfo(
                            info.pos(), info.state(), rotatedNbt));
                    if (updateTags != null) {
                        updateTags.put(entry.getKey(), stripMetadataKeys(rotatedNbt));
                    }
                    modified = true;
                }
            }

            // Single-state copycats: rotate Material BlockState via StructureTransform
            if (!modified && nbt.contains("Material", Tag.TAG_COMPOUND)) {
                CompoundTag rotatedNbt = nbt.copy();
                rotatedNbt.put("Material",
                        transformMaterialTag(rotatedNbt.getCompound("Material"), transform, blockGetter));
                blocks.put(entry.getKey(), new StructureTemplate.StructureBlockInfo(
                        info.pos(), info.state(), rotatedNbt));
                if (updateTags != null) {
                    updateTags.put(entry.getKey(), stripMetadataKeys(rotatedNbt));
                }
            }
        }
    }

    /**
     * Warns when a copycat block in the contraption has neither a Material tag nor
     * a material_data tag in its NBT or UpdateTag - usually a sign that
     * {@link #transformBlockEntities} or the assembler lost the material reference.
     *
     * @param updateTags the contraption's {@code updateTags} map (may be null)
     */
    public static void logMaterialDiagnostics(CarriageContraption contraption,
                                               Map<BlockPos, CompoundTag> updateTags,
                                               int carriageIdx) {
        for (var entry : contraption.getBlocks().entrySet()) {
            String blockName = BuiltInRegistries.BLOCK.getKey(entry.getValue().state().getBlock()).toString();
            if (!blockName.contains("copycat")) continue;

            CompoundTag nbt = entry.getValue().nbt();
            boolean nbtHasMat = nbt != null && (nbt.contains("Material") || nbt.contains("material_data"));

            CompoundTag ut = updateTags != null ? updateTags.get(entry.getKey()) : null;
            boolean utHasMat = ut != null && (ut.contains("Material") || ut.contains("material_data"));
            boolean utEmpty = ut != null && ut.isEmpty();

            if (!nbtHasMat && !utHasMat) {
                LOGGER.warn("[TRAIN-BUILD] Carriage {} copycat MISSING material: block={}, pos={}, nbtKeys={}, updateTagKeys={}, utEmpty={}",
                        carriageIdx, blockName, entry.getKey(),
                        nbt != null ? nbt.getAllKeys() : "null",
                        ut != null ? ut.getAllKeys() : "null",
                        utEmpty);
            }
        }
    }

    /**
     * Converts Copycats+ material_data to Material and syncs Material to UpdateTag.
     */
    public static void convertMaterialData(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) return;

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)
                || !blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) return;

        List<String> paletteNames = readPaletteNames(blocksTag);
        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            convertBlockEntry(blockList.getCompound(i), paletteNames);
        }
    }

    private static List<String> readPaletteNames(CompoundTag blocksTag) {
        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        List<String> paletteNames = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) {
            paletteNames.add(palette.getCompound(i).getString(ContraptionNbtKeys.NAME));
        }
        return paletteNames;
    }

    private static void convertBlockEntry(CompoundTag blockEntry, List<String> paletteNames) {
        int stateIndex = blockEntry.getInt(ContraptionNbtKeys.STATE);
        String blockName = (stateIndex >= 0 && stateIndex < paletteNames.size()) ? paletteNames.get(stateIndex) : "";
        if (!blockName.contains("copycat")) return;
        if (!blockEntry.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)) return;

        CompoundTag data = blockEntry.getCompound(ContraptionNbtKeys.DATA);
        if (isMultiStateCopycat(data)) {
            syncMultiStateUpdateTag(blockEntry, data);
        } else {
            syncSingleMaterialUpdateTag(blockEntry, data);
        }
    }

    /**
     * Detects multi-state copycats (copycat_board, copycat_slab, copycat_byte, etc.):
     * material_data contains per-section entries (e.g. north/south, top/bottom,
     * top_northeast/bottom_southwest) rather than a single material. Detected by
     * checking if any value in material_data is a CompoundTag, which covers all
     * Copycats+ section naming conventions.
     */
    private static boolean isMultiStateCopycat(CompoundTag data) {
        if (!data.contains("material_data", Tag.TAG_COMPOUND)) return false;
        CompoundTag md = data.getCompound("material_data");
        for (String key : md.getAllKeys()) {
            if (md.get(key) instanceof CompoundTag) return true;
        }
        return false;
    }

    /** Multi-state copycats: ensure UpdateTag has material_data for client rendering. */
    private static void syncMultiStateUpdateTag(CompoundTag blockEntry, CompoundTag data) {
        if (!blockEntry.contains("UpdateTag", Tag.TAG_COMPOUND)) {
            blockEntry.put("UpdateTag", data.copy());
            return;
        }
        CompoundTag updateTag = blockEntry.getCompound("UpdateTag");
        if (!updateTag.contains("material_data")) {
            updateTag.put("material_data", data.get("material_data").copy());
        }
    }

    /** Single-material copycats: normalize material_data -> Material, then sync into UpdateTag. */
    private static void syncSingleMaterialUpdateTag(CompoundTag blockEntry, CompoundTag data) {
        if (data.contains("material_data") && !data.contains("Material")) {
            Tag materialData = data.get("material_data");
            if (materialData instanceof CompoundTag) {
                data.put("Material", materialData.copy());
            }
        }
        if (!data.contains("Material")) return;

        if (!blockEntry.contains("UpdateTag", Tag.TAG_COMPOUND)) {
            CompoundTag updateTag = new CompoundTag();
            updateTag.put("Material", data.get("Material").copy());
            if (data.contains("Item")) {
                updateTag.put("Item", data.get("Item").copy());
            }
            blockEntry.put("UpdateTag", updateTag);
            return;
        }
        CompoundTag updateTag = blockEntry.getCompound("UpdateTag");
        if (!updateTag.contains("Material")) {
            updateTag.put("Material", data.get("Material").copy());
        }
        if (data.contains("Item") && !updateTag.contains("Item")) {
            updateTag.put("Item", data.get("Item").copy());
        }
    }

    /**
     * Returns a copy of the tag with block entity metadata keys stripped.
     * UpdateTags should contain only custom data (Material, material_data, etc.).
     */
    public static CompoundTag stripMetadataKeys(CompoundTag nbt) {
        CompoundTag clean = nbt.copy();
        clean.remove("id");
        clean.remove("x");
        clean.remove("y");
        clean.remove("z");
        clean.remove("ForgeData");
        clean.remove("ForgeCaps");
        return clean;
    }

    private static CompoundTag transformMaterialTag(CompoundTag materialTag,
                                                     StructureTransform transform,
                                                     HolderGetter<Block> blockGetter) {
        BlockState materialState = NbtUtils.readBlockState(blockGetter, materialTag);
        if (materialState.isAir()) return materialTag;
        BlockState rotated = transform.apply(materialState);
        if (rotated == materialState) return materialTag;
        return NbtUtils.writeBlockState(rotated);
    }
}
