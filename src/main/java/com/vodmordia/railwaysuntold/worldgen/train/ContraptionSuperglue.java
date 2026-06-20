package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.*;

/**
 * Superglue for assembled contraptions: deriving glue AABBs from block layout (either the NBT block
 * list or a live contraption's blocks) and writing them back. Two block-adjacency notions are used -
 * flood-filled connected-component bounds from NBT, and per-pair 2-block AABBs injected into a live
 * contraption - matching how Create's own SuperGlueEntity binds blocks.
 */
public final class ContraptionSuperglue {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Lazy-cached reflection field for contraption superglue access
    private static volatile java.lang.reflect.Field cachedSuperglueField;

    private ContraptionSuperglue() {
    }

    /**
     * Clears world-space superglue entries that Create's assemble() stored from the
     * virtual world's SuperGlueEntity, then injects proper face-to-face glue AABBs
     * between adjacent block pairs within the contraption.
     *
     */
    @SuppressWarnings("unchecked")
    public static void injectSuperglueIfEmpty(CarriageContraption contraption) {
        try {
            if (cachedSuperglueField == null) {
                cachedSuperglueField = Contraption.class
                        .getDeclaredField("superglue");
                cachedSuperglueField.setAccessible(true);
            }
            List<AABB> superglue = (List<AABB>) cachedSuperglueField.get(contraption);
            if (superglue == null) {
                superglue = new ArrayList<>();
                cachedSuperglueField.set(contraption, superglue);
            }

            // Clear world-space entries from virtual assembly
            superglue.clear();

            // Generate face-to-face glue AABBs between adjacent block pairs.
            // Each AABB covers exactly 2 blocks (1x1x2, 1x2x1, or 2x1x1), matching
            // how Create's own SuperGlueEntity works. This keeps glue tight to the
            // carriage and prevents bridging across inter-carriage gaps.
            Set<BlockPos> blockPositions = contraption.getBlocks().keySet();
            Set<Long> seen = new HashSet<>();
            for (BlockPos pos : blockPositions) {
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.relative(dir);
                    if (!blockPositions.contains(neighbor)) continue;

                    // Deduplicate: only create one AABB per pair
                    long pairKey = Math.min(pos.asLong(), neighbor.asLong()) * 31L
                            + Math.max(pos.asLong(), neighbor.asLong());
                    if (!seen.add(pairKey)) continue;

                    // AABB covering both blocks (min corner to max corner + 1)
                    superglue.add(new AABB(
                            Math.min(pos.getX(), neighbor.getX()),
                            Math.min(pos.getY(), neighbor.getY()),
                            Math.min(pos.getZ(), neighbor.getZ()),
                            Math.max(pos.getX(), neighbor.getX()) + 1,
                            Math.max(pos.getY(), neighbor.getY()) + 1,
                            Math.max(pos.getZ(), neighbor.getZ()) + 1));
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[TRAIN-BUILD] Could not inject superglue via reflection: {}", e.getMessage());
        }
    }

    /**
     * Generates superglue AABBs from block positions in the contraption NBT.
     * Flood-fills connected components and creates a tight bounding box per component.
     */
    public static List<AABB> generateGlueBoxesFromNbt(CompoundTag contraptionNbt) {
        List<AABB> glueBoxes = new ArrayList<>();

        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            return glueBoxes;
        }
        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) {
            return glueBoxes;
        }

        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        Set<BlockPos> blockSet = new HashSet<>();
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            if (entry.contains(ContraptionNbtKeys.POS, Tag.TAG_LONG)) {
                blockSet.add(BlockPos.of(entry.getLong(ContraptionNbtKeys.POS)));
            }
        }

        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : blockSet) {
            if (visited.contains(start)) continue;

            int minX = start.getX(), minY = start.getY(), minZ = start.getZ();
            int maxX = minX, maxY = minY, maxZ = minZ;
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);

            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = pos.relative(dir);
                    if (blockSet.contains(neighbor) && visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }

            AABB box = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
            if (box.getSize() > 1.01) {
                glueBoxes.add(box);
            }
        }

        return glueBoxes;
    }

    public static ListTag writeVec3(double x, double y, double z) {
        ListTag list = new ListTag();
        list.add(DoubleTag.valueOf(x));
        list.add(DoubleTag.valueOf(y));
        list.add(DoubleTag.valueOf(z));
        return list;
    }
}
