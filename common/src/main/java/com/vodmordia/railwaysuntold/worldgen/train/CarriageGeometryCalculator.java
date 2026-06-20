package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.CarriageContraptionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry/layout math for train schematics: bogey spacing, train height, the spread axis of
 * carriage entities, inter-carriage gaps, and assigning superglue boxes to carriages. Operates only on
 * data (NBT, BlockPos, Vec3, AABB) - no world and no Create block/registry statics.
 */
public final class CarriageGeometryCalculator {

    private CarriageGeometryCalculator() {
    }

    public static double componentAlongAxis(Vec3 vec, Direction.Axis axis) {
        return switch (axis) {
            case X -> vec.x;
            case Y -> vec.y;
            case Z -> vec.z;
        };
    }

    /**
     * Calculates the spacing between the first and last bogey along the assembly axis.
     */
    public static int bogeySpacing(List<BogeyInfo> bogeys, Direction assemblyDirection) {
        if (bogeys.size() < 2) {
            return 0;
        }

        BogeyInfo first = bogeys.get(0);
        BogeyInfo last = bogeys.get(bogeys.size() - 1);

        int firstPos = DirectionUtil.getPositionAlongAxis(first.relativePos, assemblyDirection);
        int lastPos = DirectionUtil.getPositionAlongAxis(last.relativePos, assemblyDirection);

        return Math.abs(lastPos - firstPos);
    }

    /**
     * Calculates the height of the train from block positions.
     * Returns the vertical span (maxY - minY + 1) of all blocks in the contraption.
     */
    public static int trainHeight(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            return 3; // Default fallback
        }

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) {
            return 3; // Default fallback
        }

        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        if (blockList.isEmpty()) {
            return 3; // Default fallback
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockEntry = blockList.getCompound(i);
            long packedPos = blockEntry.getLong(ContraptionNbtKeys.POS);
            BlockPos pos = BlockPos.of(packedPos);

            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }

        if (minY == Integer.MAX_VALUE || maxY == Integer.MIN_VALUE) {
            return 3; // Default fallback
        }

        return maxY - minY + 1;
    }

    /**
     * Finds the horizontal axis (X or Z) with the greatest spread among carriage entity positions.
     */
    public static Direction.Axis findSpreadAxis(List<Vec3> entityPositions) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 pos : entityPositions) {
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxZ = Math.max(maxZ, pos.z);
        }
        return (maxX - minX) >= (maxZ - minZ) ? Direction.Axis.X : Direction.Axis.Z;
    }

    public static double spreadOnAxis(List<Vec3> entityPositions, Direction.Axis axis) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Vec3 pos : entityPositions) {
            double val = componentAlongAxis(pos, axis);
            min = Math.min(min, val);
            max = Math.max(max, val);
        }
        return max - min;
    }

    /**
     * Computes the inter-carriage spacings (gaps between adjacent carriages) for a multi-carriage train.
     * Create positions each carriage entity at its trailing bogey, so the gap is the entity-to-entity
     * distance along the spread axis minus the next carriage's own bogey span. Entity positions must be
     * in carriage order (nearest station first); returns an empty list for a single carriage.
     */
    public static List<Integer> interCarriageSpacings(List<Vec3> entityPositions, List<Integer> bogeysPerCarriage,
                                                      List<BogeyInfo> allBogeys, Direction assemblyDirection) {
        Direction.Axis spreadAxis = findSpreadAxis(entityPositions);
        List<Integer> interCarriageSpacings = new ArrayList<>();
        if (entityPositions.size() > 1) {
            int bogeyOffset = 0;
            for (int c = 0; c < entityPositions.size() - 1; c++) {
                int countThis = bogeysPerCarriage.get(c);
                int countNext = bogeysPerCarriage.get(c + 1);

                Vec3 entityPosThis = entityPositions.get(c);
                Vec3 entityPosNext = entityPositions.get(c + 1);

                // Entity-to-entity distance along the actual spread axis
                double entityDist = Math.abs(
                        componentAlongAxis(entityPosNext, spreadAxis)
                        - componentAlongAxis(entityPosThis, spreadAxis));

                // Next carriage's bogey span: distance from first to last bogey in local space.
                // The entity is at the trailing bogey, so we subtract the next carriage's
                // span to get from entity_next to the next carriage's leading bogey.
                int nextCarriageSpan = 0;
                int nextBogeyOffset = bogeyOffset + countThis;
                if (countNext > 1) {
                    int minPos = Integer.MAX_VALUE, maxPos = Integer.MIN_VALUE;
                    for (int b = 0; b < countNext; b++) {
                        int pos = DirectionUtil.getSignedPositionAlongAxis(
                                allBogeys.get(nextBogeyOffset + b).relativePos, assemblyDirection);
                        minPos = Math.min(minPos, pos);
                        maxPos = Math.max(maxPos, pos);
                    }
                    nextCarriageSpan = maxPos - minPos;
                }

                // Inter-carriage spacing = entity distance minus next carriage's bogey span
                int spacing = Math.max(0, (int) Math.round(entityDist) - nextCarriageSpan);
                interCarriageSpacings.add(spacing);


                bogeyOffset += countThis;
            }
        }
        return interCarriageSpacings;
    }

    /**
     * Distributes glue boxes to the nearest carriage based on bogey positions.
     */
    public static List<CarriageContraptionData> distributeGlueToCarriages(
            List<CarriageContraptionData> carriages, List<AABB> glueBoxes,
            List<BogeyInfo> allBogeys, List<Integer> bogeysPerCarriage, Direction direction,
            List<Vec3> entityPositions) {

        int numCarriages = carriages.size();
        List<List<AABB>> perCarriageGlue = new ArrayList<>();
        for (int i = 0; i < numCarriages; i++) {
            perCarriageGlue.add(new ArrayList<>());
        }

        // Calculate carriage center positions from bogey groups (offset-based)
        List<Double> carriageCenters = new ArrayList<>();
        int offset = 0;
        for (int c = 0; c < numCarriages; c++) {
            int count = bogeysPerCarriage.get(c);
            double pos1 = DirectionUtil.getPositionAlongAxis(allBogeys.get(offset).relativePos, direction);
            double pos2 = DirectionUtil.getPositionAlongAxis(allBogeys.get(offset + count - 1).relativePos, direction);
            carriageCenters.add((pos1 + pos2) / 2.0);
            offset += count;
        }

        for (AABB box : glueBoxes) {
            double boxCenter = switch (direction.getAxis()) {
                case X -> box.getCenter().x;
                case Z -> box.getCenter().z;
                case Y -> box.getCenter().y;
            };

            // Find nearest carriage
            int nearest = 0;
            double minDist = Double.MAX_VALUE;
            for (int c = 0; c < numCarriages; c++) {
                double dist = Math.abs(boxCenter - carriageCenters.get(c));
                if (dist < minDist) {
                    minDist = dist;
                    nearest = c;
                }
            }
            perCarriageGlue.get(nearest).add(box);
        }

        // Transform glue boxes from schematic-space to anchor-relative coordinates
        List<CarriageContraptionData> result = new ArrayList<>();
        for (int c = 0; c < numCarriages; c++) {
            Vec3 entityPos = entityPositions.get(c);
            List<AABB> transformed = new ArrayList<>();
            for (AABB box : perCarriageGlue.get(c)) {
                transformed.add(box.move(-entityPos.x, -entityPos.y, -entityPos.z));
            }
            CarriageContraptionData original = carriages.get(c);
            result.add(new CarriageContraptionData(
                    original.nbt(), original.hasForwardControls(), original.hasBackwardControls(),
                    transformed));
        }
        return result;
    }
}
