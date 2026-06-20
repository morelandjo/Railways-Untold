package com.vodmordia.railwaysuntold.worldgen.survey.extractor;

import com.vodmordia.railwaysuntold.worldgen.survey.SurveyData;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Ground-truth structure geometry read from loaded chunks: the placed structure's total bounds and
 * per-piece bounding boxes. {@link #asPredictedLayout()} adapts it to {@link PredictedVillageLayout}
 * so existing consumers (station planning, avoidance) take it with no signature change.
 */
public record SurveyedStructureLayout(
        ChunkPos structureChunk, BoundingBox totalBounds, List<BoundingBox> pieceBounds, boolean isVillage)
        implements SurveyData {

    public PredictedVillageLayout asPredictedLayout() {
        return PredictedVillageLayout.create(structureChunk, totalBounds, pieceBounds);
    }

    @Override
    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("cx", structureChunk.x);
        tag.putInt("cz", structureChunk.z);
        tag.putBoolean("village", isVillage);
        tag.putIntArray("total", box(totalBounds));
        ListTag pieces = new ListTag();
        for (BoundingBox b : pieceBounds) {
            pieces.add(new IntArrayTag(box(b)));
        }
        tag.put("pieces", pieces);
        return tag;
    }

    public static SurveyedStructureLayout fromNbt(CompoundTag tag) {
        ChunkPos chunk = new ChunkPos(tag.getInt("cx"), tag.getInt("cz"));
        BoundingBox total = unbox(tag.getIntArray("total"));
        List<BoundingBox> pieces = new ArrayList<>();
        ListTag list = tag.getList("pieces", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            pieces.add(unbox(list.getIntArray(i)));
        }
        return new SurveyedStructureLayout(chunk, total, pieces, tag.getBoolean("village"));
    }

    private static int[] box(BoundingBox b) {
        return new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()};
    }

    private static BoundingBox unbox(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3], a[4], a[5]);
    }
}
