package com.vodmordia.railwaysuntold.worldgen.survey.extractor;

import com.vodmordia.railwaysuntold.worldgen.survey.SurveyContext;
import com.vodmordia.railwaysuntold.worldgen.survey.SurveyExtractor;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import com.vodmordia.railwaysuntold.worldgen.village.VillageLocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Reads the placed village structure in a surveyed region (ground truth) and produces its exact total
 * bounds + per-piece boxes. Requires FULL chunks so it can reuse the proven
 * {@link VillageLocator#readPlacedLayout} read path; the head will load those chunks on approach
 * anyway, and the result is cached permanently, so the one-time load is bounded.
 */
public final class StructureExtractor implements SurveyExtractor<SurveyedStructureLayout> {

    public static final String ID = "structure";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ChunkStatus requiredStatus() {
        return ChunkStatus.FULL;
    }

    @Override
    @Nullable
    public SurveyedStructureLayout extract(SurveyContext ctx) {
        BlockPos near = new BlockPos(
                ctx.region().anchor().getMiddleBlockX(), 0, ctx.region().anchor().getMiddleBlockZ());
        Optional<PredictedVillageLayout> placed =
                VillageLocator.readPlacedLayout(ctx.level(), ctx.chunks(), near);
        return placed
                .map(l -> new SurveyedStructureLayout(l.structureChunk(), l.totalBounds(), l.pieceBounds(), true))
                .orElse(null);
    }

    @Override
    public SurveyedStructureLayout fromNbt(CompoundTag tag, HolderLookup.Provider provider) {
        return SurveyedStructureLayout.fromNbt(tag, provider);
    }
}
