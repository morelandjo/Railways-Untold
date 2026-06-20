package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.CarriageContraptionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Read-only analysis of an assembled train's NBT to decide whether the carriage order should be
 * reversed so the engine/controls end up nearest the station. Combines the controls' carriage index
 * with an engine indicator (smokestack / steam whistle) detected in the block palette. Pure NBT queries.
 */
final class TrainOrientationAnalyzer {

    private static final List<String> ENGINE_INDICATOR_KEYWORDS = List.of("smokestack", "steam_whistle");

    private TrainOrientationAnalyzer() {
    }

    public record OrientationResult(boolean shouldReverse) {}

    private record IndicatorInfo(String blockName, @Nullable Boolean atForwardEnd) {}

    /**
     * Analyzes the train to determine if carriage order should be reversed
     * so the engine/controls end up nearest the station.
     */
    static OrientationResult analyzeTrainOrientation(List<CarriageContraptionData> contraptions,
                                                     Direction schematicAD) {
        if (contraptions.isEmpty()) return new OrientationResult(false);

        // Find controls position
        int firstControlIdx = -1;
        for (int i = 0; i < contraptions.size(); i++) {
            CarriageContraptionData c = contraptions.get(i);
            if (c.hasForwardControls() || c.hasBackwardControls()) {
                if (firstControlIdx == -1) firstControlIdx = i;
            }
        }

        if (firstControlIdx == -1) return new OrientationResult(false);

        // Single-carriage trains: carriage reordering is meaningless
        if (contraptions.size() == 1) return new OrientationResult(false);

        // Find engine indicator (smokestack preferred over whistle since it's more
        // reliably at the engine front). Search all carriages, keep the best match.
        int indicatorCarriageIdx = -1;
        boolean foundSmokestack = false;
        for (int i = 0; i < contraptions.size(); i++) {
            CompoundTag nbt = contraptions.get(i).nbt();
            IndicatorInfo info = findEngineIndicator(nbt, schematicAD);
            if (info != null) {
                boolean isSmokestack = info.blockName.toLowerCase(Locale.ROOT).contains("smokestack");
                if (!foundSmokestack || isSmokestack) {
                    if (isSmokestack || indicatorCarriageIdx == -1) {
                        indicatorCarriageIdx = i;
                        foundSmokestack = isSmokestack;
                    }
                }
            }
        }
        // Multi-carriage trains: use carriage index of controls + indicator
        double midpoint = (contraptions.size() - 1) / 2.0;
        boolean controlsSayReverse = firstControlIdx > midpoint;

        if (indicatorCarriageIdx == -1) {
            if (!controlsSayReverse) return new OrientationResult(false);
            return new OrientationResult(true);
        }

        boolean indicatorInSecondHalf = indicatorCarriageIdx > midpoint;
        boolean indicatorInFirstHalf = indicatorCarriageIdx < midpoint;

        if (indicatorInSecondHalf) {
            return new OrientationResult(true);
        }

        if (indicatorInFirstHalf && controlsSayReverse) {
            return new OrientationResult(false);
        }

        if (controlsSayReverse) {
            return new OrientationResult(true);
        }
        return new OrientationResult(false);
    }

    /**
     * Searches a carriage's block palette for engine-indicator blocks (smokestack, steam whistle).
     * If found, also determines whether the indicator is at the AD-forward or AD-backward
     * end of the carriage by comparing its position to the carriage's block center.
     */
    @Nullable
    private static IndicatorInfo findEngineIndicator(CompoundTag contraptionNbt, Direction schematicAD) {
        CompoundTag blocks = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (blocks.isEmpty()) return null;

        ListTag palette = blocks.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        ListTag blockList = blocks.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);

        // Find palette indices that match engine indicator keywords
        Set<Integer> indicatorPaletteIndices = new HashSet<>();
        Map<Integer, String> indicatorNames = new HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString("Name");
            String lower = name.toLowerCase(Locale.ROOT);
            for (String keyword : ENGINE_INDICATOR_KEYWORDS) {
                if (lower.contains(keyword)) {
                    indicatorPaletteIndices.add(i);
                    indicatorNames.put(i, name);
                    break;
                }
            }
        }
        if (indicatorPaletteIndices.isEmpty()) return null;

        // Scan all blocks to find indicator position and carriage extents along AD axis
        Direction.Axis axis = schematicAD.getAxis();
        int sign = schematicAD.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;

        int minAlong = Integer.MAX_VALUE;
        int maxAlong = Integer.MIN_VALUE;
        int indicatorAlong = 0;
        String foundName = null;
        boolean foundIndicator = false;

        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            BlockPos pos = BlockPos.of(entry.getLong(ContraptionNbtKeys.POS));
            int coord = getAxisComponent(pos, axis) * sign;

            if (coord < minAlong) minAlong = coord;
            if (coord > maxAlong) maxAlong = coord;

            if (!foundIndicator && indicatorPaletteIndices.contains(entry.getInt(ContraptionNbtKeys.STATE))) {
                indicatorAlong = coord;
                foundName = indicatorNames.get(entry.getInt(ContraptionNbtKeys.STATE));
                foundIndicator = true;
            }
        }

        if (!foundIndicator || minAlong == maxAlong) {
            return foundIndicator ? new IndicatorInfo(foundName, null) : null;
        }

        // AD-forward = higher signed coordinate (farther from station)
        double center = (minAlong + maxAlong) / 2.0;
        boolean atForwardEnd = indicatorAlong >= center;
        return new IndicatorInfo(foundName, atForwardEnd);
    }

    private static int getAxisComponent(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }
}
