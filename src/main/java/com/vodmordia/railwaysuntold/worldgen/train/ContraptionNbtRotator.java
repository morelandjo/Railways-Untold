package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Rotation;

/**
 * NBT rotation helpers for Create contraption data.
 *
 */
public final class ContraptionNbtRotator {

    private ContraptionNbtRotator() {}

    /**
     * Rotates a full contraption NBT tree N 90° clockwise steps: block positions, palette
     * directional properties, seats, actors, interactors, conductor seats,
     * BoundsFront AABB, and SoundQueue sources.
     */
    public static void rotateContraptionNbt(CompoundTag contraptionNbt, int cwSteps) {
        if (cwSteps == 0) return;

        // Rotate every position (block list, seats, actors, interactors, conductor seats, sound sources)
        // clockwise about Y through the shared traversal; the per-block-entry hook rotates copycat
        // material / multi-state face data.
        ContraptionNbtPositions.transformPoints(contraptionNbt,
                (x, y, z) -> {
                    BlockPos r = SchematicRotator.rotateBlockPos(new BlockPos(x, y, z), cwSteps);
                    return new int[]{r.getX(), r.getY(), r.getZ()};
                },
                entry -> rotateCopycatMaterial(entry, cwSteps));

        // Rotate facing/axis properties in the palette (not position data).
        if (contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
            if (blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)) {
                ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
                for (int i = 0; i < palette.size(); i++) {
                    CompoundTag entry = palette.getCompound(i);
                    if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
                        rotatePaletteProperties(entry.getCompound("Properties"), cwSteps);
                    }
                }
            }
        }

        // Rotate BoundsFront AABB (6 floats: minX, minY, minZ, maxX, maxY, maxZ)
        if (contraptionNbt.contains(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_LIST)) {
            ListTag boundsTag = contraptionNbt.getList(ContraptionNbtKeys.BOUNDS_FRONT, Tag.TAG_FLOAT);
            if (boundsTag.size() == 6) {
                float minX = boundsTag.getFloat(0), minY = boundsTag.getFloat(1), minZ = boundsTag.getFloat(2);
                float maxX = boundsTag.getFloat(3), maxY = boundsTag.getFloat(4), maxZ = boundsTag.getFloat(5);
                for (int s = 0; s < cwSteps; s++) {
                    float newMinX = -maxZ, newMaxX = -minZ;
                    float newMinZ = minX, newMaxZ = maxX;
                    minX = newMinX; maxX = newMaxX;
                    minZ = newMinZ; maxZ = newMaxZ;
                }
                ListTag rotated = new ListTag();
                rotated.add(FloatTag.valueOf(minX));
                rotated.add(FloatTag.valueOf(minY));
                rotated.add(FloatTag.valueOf(minZ));
                rotated.add(FloatTag.valueOf(maxX));
                rotated.add(FloatTag.valueOf(maxY));
                rotated.add(FloatTag.valueOf(maxZ));
                contraptionNbt.put(ContraptionNbtKeys.BOUNDS_FRONT, rotated);
            }
        }
    }

    /** CW-steps -> vanilla {@link Rotation}, for use with StructureTransform etc. */
    public static Rotation cwStepsToRotation(int cwSteps) {
        return switch (cwSteps % 4) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /**
     * Remaps multi-state material_data face keys to match a clockwise rotation.
     * Cardinal keys (north->east->south->west) and octant keys (northeast->southeast->southwest->northwest)
     * are remapped. Non-directional keys (up, down, face-relative keys like bottom_left) are unchanged.
     */
    public static void remapMultiStateFaceKeys(CompoundTag materialData, int cwSteps) {
        rotateDirectionalKeys(materialData, cwSteps);
    }

    /**
     * Cyclically remaps the cardinal (north/east/south/west) and octant
     * (top_/bottom_ + northeast/southeast/southwest/northwest) key sets clockwise by
     * {@code cwSteps}. Works on either a multi-state material_data compound (values are
     * face entries) or a blockstate Properties compound (values are property strings).
     * Non-directional keys are left untouched.
     */
    private static void rotateDirectionalKeys(CompoundTag tag, int cwSteps) {
        rotateKeyCycle(tag, new String[]{"north", "east", "south", "west"}, cwSteps);
        for (String prefix : new String[]{"top_", "bottom_"}) {
            rotateKeyCycle(tag, new String[]{
                    prefix + "northeast", prefix + "southeast", prefix + "southwest", prefix + "northwest"
            }, cwSteps);
        }
    }

    /**
     * Carries each present value at {@code keys[i]} to {@code keys[(i + cwSteps) % 4]}.
     * All source keys are removed before the rotated values are written, so a partial set
     * (e.g. only "north" present) does not leave a stale key behind - the bug that
     * corrupted rotated multi-state copycats with fewer than four faces specified.
     */
    private static void rotateKeyCycle(CompoundTag tag, String[] keys, int cwSteps) {
        Tag[] values = new Tag[4];
        boolean hasAny = false;
        for (int i = 0; i < 4; i++) {
            if (tag.contains(keys[i])) {
                values[i] = tag.get(keys[i]).copy();
                hasAny = true;
            }
        }
        if (!hasAny) return;
        for (String key : keys) {
            tag.remove(key);
        }
        for (int i = 0; i < 4; i++) {
            if (values[i] != null) {
                tag.put(keys[(i + cwSteps) % 4], values[i]);
            }
        }
    }

    private static void rotatePaletteProperties(CompoundTag props, int cwSteps) {
        if (props.contains("facing")) {
            String facing = props.getString("facing");
            Direction dir = Direction.byName(facing);
            if (dir != null && dir.getAxis().isHorizontal()) {
                for (int s = 0; s < cwSteps; s++) {
                    dir = dir.getClockWise();
                }
                props.putString("facing", dir.getName());
            }
        }
        // Rotate 'offset' property (copycat_half_panel etc.) - same logic as facing
        if (props.contains("offset")) {
            String offset = props.getString("offset");
            Direction dir = Direction.byName(offset);
            if (dir != null && dir.getAxis().isHorizontal()) {
                for (int s = 0; s < cwSteps; s++) {
                    dir = dir.getClockWise();
                }
                props.putString("offset", dir.getName());
            }
        }
        if (props.contains("axis")) {
            String axis = props.getString("axis");
            // For 90° or 270° rotations, X ↔ Z
            if ((cwSteps % 2) != 0) {
                if ("x".equals(axis)) {
                    props.putString("axis", "z");
                } else if ("z".equals(axis)) {
                    props.putString("axis", "x");
                }
            }
        }
        // Rotate integer 'rotation' property (signs, banners, skulls: 0-15, 16 compass steps)
        if (props.contains("rotation")) {
            try {
                int rot = Integer.parseInt(props.getString("rotation"));
                rot = (rot + cwSteps * 4) % 16; // Each CW 90° step = 4 of 16 increments
                props.putString("rotation", String.valueOf(rot));
            } catch (NumberFormatException ignored) {}
        }
        // Rotate directional connection properties (glass panes, iron bars, walls, fences:
        // north/east/south/west) and compound directional properties (copycat_byte octants
        // like top_northeast / bottom_southwest). Non-directional keys are left untouched.
        rotateDirectionalKeys(props, cwSteps);
    }

    /**
     * Rotates directional properties inside a block entry's Data and UpdateTag.
     * Handles copycat blocks whose "Material" stores a block state with its own facing/axis/connection props.
     */
    private static void rotateCopycatMaterial(CompoundTag blockEntry, int cwSteps) {
        rotateMaterialInTag(blockEntry, ContraptionNbtKeys.DATA, cwSteps);
        rotateMaterialInTag(blockEntry, "UpdateTag", cwSteps);
    }

    private static void rotateMaterialInTag(CompoundTag blockEntry, String parentKey, int cwSteps) {
        if (!blockEntry.contains(parentKey, Tag.TAG_COMPOUND)) return;
        CompoundTag parent = blockEntry.getCompound(parentKey);

        // Single-material copycats: rotate the Material block state properties
        if (parent.contains("Material", Tag.TAG_COMPOUND)) {
            CompoundTag material = parent.getCompound("Material");
            if (material.contains("Properties", Tag.TAG_COMPOUND)) {
                rotatePaletteProperties(material.getCompound("Properties"), cwSteps);
            }
        }

        // Multi-state copycats (copycat_board, etc.): rotate per-face material_data
        if (parent.contains("material_data", Tag.TAG_COMPOUND)) {
            rotateMultiStateMaterialData(parent, cwSteps);
        }
    }

    /**
     * Rotates multi-state copycat material_data:
     * 1. Remaps horizontal face keys (north->east->south->west for each CW step)
     * 2. Rotates Properties inside each face's material BlockState
     */
    private static void rotateMultiStateMaterialData(CompoundTag parent, int cwSteps) {
        CompoundTag materialData = parent.getCompound("material_data");

        // Rotate material Properties inside each face entry
        for (String key : materialData.getAllKeys()) {
            CompoundTag faceEntry = materialData.getCompound(key);
            if (faceEntry.contains("material", Tag.TAG_COMPOUND)) {
                CompoundTag material = faceEntry.getCompound("material");
                if (material.contains("Properties", Tag.TAG_COMPOUND)) {
                    rotatePaletteProperties(material.getCompound("Properties"), cwSteps);
                }
            }
        }

        // Remap horizontal face keys (north->east->south->west) and per-octant keys
        // (copycat_byte). Non-horizontal keys (up, down) stay in place.
        rotateDirectionalKeys(materialData, cwSteps);

        parent.put("material_data", materialData);
    }
}
