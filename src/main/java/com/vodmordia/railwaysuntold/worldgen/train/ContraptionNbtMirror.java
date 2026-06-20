package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * NBT mirror helpers for Create contraption data.
 *
 */
public final class ContraptionNbtMirror {

    private ContraptionNbtMirror() {}

    /**
     * Mirrors all positions and directional block properties in the contraption NBT
     * along the given assembly direction axis. This negates the axis component of
     * positions and swaps the two horizontal facing directions on that axis.
     */
    public static void mirrorContraptionAlongAxis(CompoundTag contraptionNbt, Direction assemblyDirection) {
        Direction.Axis axis = assemblyDirection.getAxis();

        // Mirror every position (block list, seats, actors, interactors, conductor seats, sound sources)
        // across the assembly axis through the shared traversal - the same traversal the shifter/rotator
        // use, so it covers int-array/compound Pos and the sound queue too. The per-block-entry hook
        // mirrors block-entity data (copycat material etc.).
        ContraptionNbtPositions.transformPoints(contraptionNbt,
                axis == Direction.Axis.X ? (x, y, z) -> new int[]{-x, y, z}
                                         : (x, y, z) -> new int[]{x, y, -z},
                entry -> mirrorBlockEntityData(entry, axis));

        // Mirror facing/axis properties in the palette (not position data).
        if (contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
            if (blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)) {
                ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
                for (int i = 0; i < palette.size(); i++) {
                    CompoundTag entry = palette.getCompound(i);
                    if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
                        mirrorPaletteProperties(entry.getCompound("Properties"), axis);
                    }
                }
            }
        }

        // Mirror BoundsFront
        if (contraptionNbt.contains(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_LIST)) {
            ListTag boundsTag = contraptionNbt.getList(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_FLOAT);
            if (boundsTag.size() == 6) {
                float minX = boundsTag.getFloat(0), minY = boundsTag.getFloat(1), minZ = boundsTag.getFloat(2);
                float maxX = boundsTag.getFloat(3), maxY = boundsTag.getFloat(4), maxZ = boundsTag.getFloat(5);
                if (axis == Direction.Axis.X) {
                    float tmp = -maxX; maxX = -minX; minX = tmp;
                } else {
                    float tmp = -maxZ; maxZ = -minZ; minZ = tmp;
                }
                ListTag mirrored = new ListTag();
                mirrored.add(FloatTag.valueOf(minX)); mirrored.add(FloatTag.valueOf(minY)); mirrored.add(FloatTag.valueOf(minZ));
                mirrored.add(FloatTag.valueOf(maxX)); mirrored.add(FloatTag.valueOf(maxY)); mirrored.add(FloatTag.valueOf(maxZ));
                contraptionNbt.put(ContraptionNbtKeys.BOUNDS_FRONT, mirrored);
            }
        }
    }

    private static void mirrorPaletteProperties(CompoundTag props, Direction.Axis axis) {
        // Mirror facing: swap the two directions on the axis
        if (props.contains("facing")) {
            String facing = props.getString("facing");
            Direction dir = Direction.byName(facing);
            if (dir != null && dir.getAxis() == axis) {
                props.putString("facing", dir.getOpposite().getName());
            }
        }
        // Mirror offset property (copycat_half_panel etc.)
        if (props.contains("offset")) {
            String offset = props.getString("offset");
            Direction dir = Direction.byName(offset);
            if (dir != null && dir.getAxis() == axis) {
                props.putString("offset", dir.getOpposite().getName());
            }
        }
        // axis property: unchanged by mirror (X stays X, Z stays Z)

        // Mirror directional connection properties (north/south or east/west swap)
        if (axis == Direction.Axis.X) {
            swapStringProperty(props, "east", "west");
        } else {
            swapStringProperty(props, "north", "south");
        }

        // Mirror octant properties for copycat_byte blocks
        if (axis == Direction.Axis.X) {
            swapStringProperty(props, "bottom_northeast", "bottom_northwest");
            swapStringProperty(props, "bottom_southeast", "bottom_southwest");
            swapStringProperty(props, "top_northeast", "top_northwest");
            swapStringProperty(props, "top_southeast", "top_southwest");
        } else {
            swapStringProperty(props, "bottom_northeast", "bottom_southeast");
            swapStringProperty(props, "bottom_northwest", "bottom_southwest");
            swapStringProperty(props, "top_northeast", "top_southeast");
            swapStringProperty(props, "top_northwest", "top_southwest");
        }

        // Mirror left/right properties (for byte_panel blocks).
        // Mirrors always reverse handedness, so left↔right swap regardless of facing vs axis.
        if (props.contains("bottom_left") && props.contains("bottom_right")) {
            swapStringProperty(props, "bottom_left", "bottom_right");
            swapStringProperty(props, "top_left", "top_right");
        }
    }

    private static void swapStringProperty(CompoundTag props, String key1, String key2) {
        if (props.contains(key1) && props.contains(key2)) {
            String val1 = props.getString(key1);
            String val2 = props.getString(key2);
            props.putString(key1, val2);
            props.putString(key2, val1);
        }
    }

    private static void mirrorBlockEntityData(CompoundTag entry, Direction.Axis axis) {
        mirrorMaterialInTag(entry, ContraptionNbtKeys.DATA, axis);
        mirrorMaterialInTag(entry, "UpdateTag", axis);

        // Flip bogey IsForwards flag - mirror reverses the bogey's facing direction
        if (entry.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)) {
            CompoundTag data = entry.getCompound(ContraptionNbtKeys.DATA);
            if (data.contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND)) {
                CompoundTag bogeyData = data.getCompound(ContraptionNbtKeys.BOGEY_DATA);
                if (bogeyData.contains("IsForwards")) {
                    bogeyData.putBoolean("IsForwards", !bogeyData.getBoolean("IsForwards"));
                }
            }
        }
    }

    private static void mirrorMaterialInTag(CompoundTag blockEntry, String parentKey, Direction.Axis axis) {
        if (!blockEntry.contains(parentKey, Tag.TAG_COMPOUND)) return;
        CompoundTag parent = blockEntry.getCompound(parentKey);

        // Single-state copycat: mirror Material Properties
        if (parent.contains("Material", Tag.TAG_COMPOUND)) {
            CompoundTag material = parent.getCompound("Material");
            if (material.contains("Properties", Tag.TAG_COMPOUND)) {
                mirrorPaletteProperties(material.getCompound("Properties"), axis);
            }
        }

        // Multi-state copycats: mirror per-face material_data
        if (parent.contains("material_data", Tag.TAG_COMPOUND)) {
            mirrorMultiStateMaterialData(parent, axis);
        }
    }

    private static void mirrorMultiStateMaterialData(CompoundTag parent, Direction.Axis axis) {
        CompoundTag materialData = parent.getCompound("material_data");

        // Mirror properties inside each face entry
        for (String key : materialData.getAllKeys()) {
            CompoundTag faceEntry = materialData.getCompound(key);
            if (faceEntry.contains("material", Tag.TAG_COMPOUND)) {
                CompoundTag material = faceEntry.getCompound("material");
                if (material.contains("Properties", Tag.TAG_COMPOUND)) {
                    mirrorPaletteProperties(material.getCompound("Properties"), axis);
                }
            }
        }

        // Swap cardinal face keys on the mirror axis
        if (axis == Direction.Axis.X) {
            swapCompoundKeys(materialData, "east", "west");
        } else {
            swapCompoundKeys(materialData, "north", "south");
        }

        // Swap octant keys (copycat_byte blocks)
        if (axis == Direction.Axis.X) {
            swapCompoundKeys(materialData, "top_northeast", "top_northwest");
            swapCompoundKeys(materialData, "top_southeast", "top_southwest");
            swapCompoundKeys(materialData, "bottom_northeast", "bottom_northwest");
            swapCompoundKeys(materialData, "bottom_southeast", "bottom_southwest");
        } else {
            swapCompoundKeys(materialData, "top_northeast", "top_southeast");
            swapCompoundKeys(materialData, "top_northwest", "top_southwest");
            swapCompoundKeys(materialData, "bottom_northeast", "bottom_southeast");
            swapCompoundKeys(materialData, "bottom_northwest", "bottom_southwest");
        }

        // Swap left/right keys (copycat_byte_panel blocks) - mirrors always reverse handedness
        swapCompoundKeys(materialData, "bottom_left", "bottom_right");
        swapCompoundKeys(materialData, "top_left", "top_right");

        parent.put("material_data", materialData);
    }

    private static void swapCompoundKeys(CompoundTag tag, String key1, String key2) {
        boolean has1 = tag.contains(key1), has2 = tag.contains(key2);
        if (has1 && has2) {
            CompoundTag val1 = tag.getCompound(key1).copy();
            CompoundTag val2 = tag.getCompound(key2).copy();
            tag.put(key1, val2);
            tag.put(key2, val1);
        } else if (has1) {
            tag.put(key2, tag.getCompound(key1).copy());
            tag.remove(key1);
        } else if (has2) {
            tag.put(key1, tag.getCompound(key2).copy());
            tag.remove(key2);
        }
    }

}
