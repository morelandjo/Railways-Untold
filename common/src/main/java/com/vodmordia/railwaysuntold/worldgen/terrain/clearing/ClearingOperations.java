package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionOffsets;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.terrain.UndergroundDetector;
import com.vodmordia.railwaysuntold.worldgen.terrain.UnderwaterDetector;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.ClearingRequest;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.SegmentData;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil.ClearingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.*;

/**
 * Shared low-level clearing utilities used by both bezier and straight-line clearing pipelines.
 */
class ClearingOperations {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double ZERO_VECTOR_THRESHOLD = 0.001;
    private static final double SLERP_PARALLEL_THRESHOLD = 0.9995;

    /**
     * Skips floating vegetation detection for underground segments to preserve surface trees.
     */
    static boolean clearSegmentWithUndergroundAwareness(
            ServerLevel level,
            Vec3 segmentPosition,
            Vec3 normal,
            ClearingContext ctx) {

        BlockPos centerPos = BlockPos.containing(segmentPosition);
        boolean isUnderground = UndergroundDetector.isSegmentUnderground(level, centerPos);
        ctx.setSkipFloatingVegetation(isUnderground);
        ctx.setUnderground(isUnderground);

        clearSegmentWithBasePattern(segmentPosition, normal, ctx);
        return isUnderground;
    }

    /**
     * Clears blocks at a segment using base clear + expansion.
     */
    static void clearSegmentWithBasePattern(
            Vec3 segmentPosition,
            Vec3 normal,
            ClearingContext ctx) {

        BlockPos centerPos = BlockPos.containing(segmentPosition);

        int horizontalRadius = Math.max(0, RailwaysUntoldConfig.getHorizontalExpansion());
        int verticalHeight = Math.max(0, RailwaysUntoldConfig.getVerticalExpansion());

        DirectionOffsets offsets = DirectionOffsets.fromNormal(normal);
        Vec3 leftOffset = offsets.left();
        Vec3 rightOffset = offsets.right();

        // Single dedup set shared across all lines in this segment - avoids per-call
        // HashSet allocation and provides cross-line dedup for overlapping positions
        Set<BlockPos> segmentProcessed = new HashSet<>();

        ctx.clearBlock(centerPos);
        segmentProcessed.add(centerPos);
        clearDensifiedLineFromVec3(segmentPosition, leftOffset, 1, ctx, false, segmentProcessed);
        clearDensifiedLineFromVec3(segmentPosition, rightOffset, 1, ctx, false, segmentProcessed);

        Vec3 belowPosition = segmentPosition.add(0, -1, 0);
        BlockPos belowCenter = BlockPos.containing(belowPosition);
        boolean preserveWater = ctx.shouldPreserveWaterBelowTrack();
        if (preserveWater) {
            ctx.clearBlockPreserveWater(belowCenter);
        } else {
            ctx.clearBlock(belowCenter);
        }
        segmentProcessed.add(belowCenter);
        // Widen below-track clearing for angled tracks - diagonal perpendiculars
        // cover less physical width per block, so scale up to compensate
        double maxComp = Math.max(Math.abs(leftOffset.x), Math.abs(leftOffset.z));
        int belowDistance = maxComp > 0.01 ? Math.max(1, (int) Math.ceil(1.0 / maxComp)) : 1;
        clearDensifiedLineFromVec3(belowPosition, leftOffset, belowDistance, ctx, preserveWater, segmentProcessed);
        clearDensifiedLineFromVec3(belowPosition, rightOffset, belowDistance, ctx, preserveWater, segmentProcessed);

        if (horizontalRadius > 1) {
            clearDensifiedLineFromVec3(segmentPosition, leftOffset, horizontalRadius, ctx, false, segmentProcessed);
            clearDensifiedLineFromVec3(segmentPosition, rightOffset, horizontalRadius, ctx, false, segmentProcessed);
        }

        // Top-to-bottom to prevent gravity block issues
        for (int y = verticalHeight; y >= 1; y--) {
            Vec3 levelPosition = segmentPosition.add(0, y, 0);
            BlockPos levelPos = BlockPos.containing(levelPosition);

            if (segmentProcessed.add(levelPos)) {
                ctx.clearBlock(levelPos);
            }
            clearDensifiedLineFromVec3(levelPosition, leftOffset, horizontalRadius, ctx, false, segmentProcessed);
            clearDensifiedLineFromVec3(levelPosition, rightOffset, horizontalRadius, ctx, false, segmentProcessed);
        }

        // Flood-fill upward through gravity blocks (sand, gravel) at the ceiling
        // to prevent cave-ins after clearing
        if (verticalHeight > 0) {
            int ceilingY = centerPos.getY() + verticalHeight;
            for (BlockPos pos : segmentProcessed) {
                if (pos.getY() == ceilingY) {
                    ctx.clearGravityBlocksAbove(pos);
                }
            }
        }
    }

    static void clearDensifiedLineFromVec3(
            Vec3 centerPosition,
            Vec3 direction,
            int distance,
            ClearingContext ctx,
            boolean preserveWater,
            Set<BlockPos> alreadyProcessed) {

        if (distance <= 0) {
            return;
        }

        int samples = (int) Math.ceil(distance * 2.0);
        BlockPos centerBlockPos = BlockPos.containing(centerPosition);
        BlockPos prevPos = centerBlockPos;

        for (int s = 1; s <= samples; s++) {
            double fraction = (double) s / 2.0; // Sample at 0.5 block intervals
            if (fraction > distance) {
                break;
            }

            BlockPos offsetFromVec3 = BlockPos.containing(
                    centerPosition.add(direction.scale(fraction))
            );

            // When the perpendicular line steps diagonally (both X and Z change between
            // consecutive samples), fill the L-shaped gap to prevent missed block columns.
            // This happens on curves where the normal isn't axis-aligned.
            int dx = offsetFromVec3.getX() - prevPos.getX();
            int dz = offsetFromVec3.getZ() - prevPos.getZ();
            if (dx != 0 && dz != 0) {
                clearPositionInLine(new BlockPos(prevPos.getX() + dx, offsetFromVec3.getY(), prevPos.getZ()),
                        ctx, preserveWater, alreadyProcessed);
                clearPositionInLine(new BlockPos(prevPos.getX(), offsetFromVec3.getY(), prevPos.getZ() + dz),
                        ctx, preserveWater, alreadyProcessed);
            }

            // When the normal's minor component causes persistent drift away from the
            // center's Z (or X), project each sample back to the center axis. The L-fill
            // above only catches the first diagonal step; this ensures every block column
            // within the radius is also cleared at the center's block coordinate.
            if (offsetFromVec3.getZ() != centerBlockPos.getZ()) {
                clearPositionInLine(new BlockPos(offsetFromVec3.getX(), offsetFromVec3.getY(), centerBlockPos.getZ()),
                        ctx, preserveWater, alreadyProcessed);
            }
            if (offsetFromVec3.getX() != centerBlockPos.getX()) {
                clearPositionInLine(new BlockPos(centerBlockPos.getX(), offsetFromVec3.getY(), offsetFromVec3.getZ()),
                        ctx, preserveWater, alreadyProcessed);
            }

            clearPositionInLine(offsetFromVec3, ctx, preserveWater, alreadyProcessed);
            prevPos = offsetFromVec3;
        }
    }

    private static void clearPositionInLine(BlockPos pos, ClearingContext ctx,
                                             boolean preserveWater, Set<BlockPos> alreadyProcessed) {
        if (alreadyProcessed.add(pos)) {
            if (preserveWater) {
                ctx.clearBlockPreserveWater(pos);
            } else {
                ctx.clearBlock(pos);
            }
        }
    }

    /**
     * Gets intermediate BlockPos to fill gaps between two positions (excludes endpoints).
     */
    static List<BlockPos> getIntermediateBlocks(BlockPos start, BlockPos end) {
        Set<BlockPos> intermediateSet = new LinkedHashSet<>();

        int dx = end.getX() - start.getX();
        int dy = end.getY() - start.getY();
        int dz = end.getZ() - start.getZ();

        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        int absDz = Math.abs(dz);

        int manhattanDist = absDx + absDy + absDz;
        if (manhattanDist <= 1) {
            return new ArrayList<>();
        }

        for (int i = 1; i < manhattanDist; i++) {
            double t = (double) i / manhattanDist;
            int x = start.getX() + (int) Math.round(t * dx);
            int y = start.getY() + (int) Math.round(t * dy);
            int z = start.getZ() + (int) Math.round(t * dz);
            BlockPos pos = new BlockPos(x, y, z);

            if (!pos.equals(start) && !pos.equals(end)) {
                intermediateSet.add(pos);
            }
        }

        if (absDx > 0 && absDz > 0) {
            traceDiagonalPath(start, end, intermediateSet);
        }

        return new ArrayList<>(intermediateSet);
    }

    private static void traceDiagonalPath(BlockPos start, BlockPos end, Set<BlockPos> intermediateSet) {
        BlockPos current = start;
        while (!current.equals(end)) {
            int remainX = end.getX() - current.getX();
            int remainZ = end.getZ() - current.getZ();
            int remainY = end.getY() - current.getY();

            if (remainX != 0 && remainZ != 0) {
                addIfIntermediate(intermediateSet, start, end,
                        new BlockPos(current.getX() + Integer.compare(remainX, 0), current.getY(), current.getZ()));
                addIfIntermediate(intermediateSet, start, end,
                        new BlockPos(current.getX(), current.getY(), current.getZ() + Integer.compare(remainZ, 0)));
            }

            int nextX = current.getX() + (remainX != 0 ? Integer.compare(remainX, 0) : 0);
            int nextY = current.getY() + (remainY != 0 ? Integer.compare(remainY, 0) : 0);
            int nextZ = current.getZ() + (remainZ != 0 ? Integer.compare(remainZ, 0) : 0);
            current = new BlockPos(nextX, nextY, nextZ);
        }
    }

    private static void addIfIntermediate(Set<BlockPos> set, BlockPos start, BlockPos end, BlockPos pos) {
        if (!pos.equals(start) && !pos.equals(end)) {
            set.add(pos);
        }
    }

    /**
     * Spherical linear interpolation between two vectors.
     */
    static Vec3 slerp(double t, Vec3 from, Vec3 to) {
        from = from.normalize();
        to = to.normalize();

        double dot = from.dot(to);
        dot = Math.max(-1.0, Math.min(1.0, dot));

        if (dot > SLERP_PARALLEL_THRESHOLD) {
            return new Vec3(
                    from.x + t * (to.x - from.x),
                    from.y + t * (to.y - from.y),
                    from.z + t * (to.z - from.z)
            ).normalize();
        }

        double theta = Math.acos(dot);
        double sinTheta = Math.sin(theta);

        double scale0 = Math.sin((1 - t) * theta) / sinTheta;
        double scale1 = Math.sin(t * theta) / sinTheta;

        return new Vec3(
                scale0 * from.x + scale1 * to.x,
                scale0 * from.y + scale1 * to.y,
                scale0 * from.z + scale1 * to.z
        );
    }

    /**
     * Checks if a position should be excluded from clearing.
     */
    static boolean shouldSkipClearing(BlockPos pos, ClearingRequest request, ServerLevel level) {
        if (isInsideExclusionBox(pos, request.exclusionMin, request.exclusionMax)) {
            return true;
        }
        // Underground tunnels bypass village protection - they can't damage surface structures
        if (isInsideProtectedVillage(pos, request.protectedVillageBounds)) {
            if (level != null && UndergroundDetector.isSegmentUnderground(level, pos)) {
                return false;
            }
            return true;
        }
        return false;
    }

    static boolean isInsideProtectedVillage(BlockPos pos, List<BoundingBox> villageBounds) {
        if (villageBounds == null || villageBounds.isEmpty()) {
            return false;
        }
        for (BoundingBox box : villageBounds) {
            if (box.isInside(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a position is inside the exclusion bounding box.
     */
    static boolean isInsideExclusionBox(BlockPos pos, BlockPos min, BlockPos max) {
        if (min == null || max == null) {
            return false;
        }
        return pos.getX() >= min.getX() && pos.getX() <= max.getX() &&
                pos.getY() >= min.getY() && pos.getY() <= max.getY() &&
                pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ();
    }

    /** Falls back through multiple computation strategies if primary normal is zero. */
    static Vec3 getValidNormalForClearing(BezierSegmentData extracted) {
        Vec3 normal = extracted.normal();

        if (normal != null && normal.lengthSqr() > ZERO_VECTOR_THRESHOLD * ZERO_VECTOR_THRESHOLD) {
            return normal.normalize();
        }

        // Normal is zero when faceNormal and derivative are parallel - compute fallback
        Vec3 derivative = extracted.derivative();
        Vec3 faceNormal = extracted.faceNormal();

        if (derivative != null && faceNormal != null) {
            Vec3 computed = faceNormal.cross(derivative);
            if (computed.lengthSqr() > ZERO_VECTOR_THRESHOLD * ZERO_VECTOR_THRESHOLD) {
                return computed.normalize();
            }
        }

        // Derive perpendicular from derivative using world up as reference
        if (derivative != null && derivative.lengthSqr() > ZERO_VECTOR_THRESHOLD * ZERO_VECTOR_THRESHOLD) {
            Vec3 derivNorm = derivative.normalize();
            Vec3 perpendicular = new Vec3(0, 1, 0).cross(derivNorm);

            if (perpendicular.lengthSqr() > ZERO_VECTOR_THRESHOLD * ZERO_VECTOR_THRESHOLD) {
                return perpendicular.normalize();
            }

            // Track is nearly vertical
            perpendicular = new Vec3(0, 0, 1).cross(derivNorm);
            if (perpendicular.lengthSqr() > ZERO_VECTOR_THRESHOLD * ZERO_VECTOR_THRESHOLD) {
                return perpendicular.normalize();
            }
        }

        if (RailwaysUntoldConfig.isVerboseLoggingEnabled()) {
            LOGGER.warn("[BEZIER-CLEAR] Could not compute valid normal for segment index {}, using fallback (1, 0, 0)", extracted.index());
        }
        return new Vec3(1, 0, 0);
    }

    /**
     * Backfills deferred underground segments based on later detection results.
     */
    static void backfillDeferredUndergroundSegments(List<BlockPos> deferredPositions, Map<BlockPos, Boolean> undergroundStatus) {
        if (!deferredPositions.isEmpty()) {
            boolean anyUnderground = undergroundStatus.values().stream().anyMatch(Boolean::booleanValue);
            if (anyUnderground) {
                for (BlockPos deferredPos : deferredPositions) {
                    undergroundStatus.put(deferredPos, true);
                }
            }
        }
    }

    /**
     * Determines underground status, deferring for early segments.
     */
    static boolean determineUndergroundStatus(
            ServerLevel level,
            BlockPos centerPos,
            int segmentCounter,
            int skipCount,
            List<BlockPos> deferredList) {

        if (segmentCounter < skipCount) {
            deferredList.add(centerPos);
            return false;
        }
        return UndergroundDetector.isSegmentUnderground(level, centerPos);
    }

    static Map<BlockPos, Boolean> collectUndergroundStatus(ServerLevel level, List<SegmentData> segments) {
        Map<BlockPos, Boolean> undergroundStatus = new HashMap<>();
        for (SegmentData segment : segments) {
            BlockPos centerPos = BlockPos.containing(segment.position);
            if (!undergroundStatus.containsKey(centerPos)) {
                undergroundStatus.put(centerPos, UndergroundDetector.isSegmentUnderground(level, centerPos));
            }
        }
        return undergroundStatus;
    }

    /**
     * Collects underwater status for a list of segment data.
     */
    static Map<BlockPos, Boolean> collectUnderwaterStatus(ServerLevel level, List<SegmentData> segments) {
        Map<BlockPos, Boolean> underwaterStatus = new HashMap<>();

        for (SegmentData segment : segments) {
            BlockPos centerPos = BlockPos.containing(segment.position);
            if (!underwaterStatus.containsKey(centerPos)) {
                underwaterStatus.put(centerPos, UnderwaterDetector.isSegmentUnderwater(level, centerPos, segment.normal));
            }
        }

        return underwaterStatus;
    }

    static Map<BlockPos, Boolean> collectUnderwaterStatus(ServerLevel level, BlockPos start,
                                                            Direction direction, int distance, Vec3 normal) {
        Map<BlockPos, Boolean> underwaterStatus = new HashMap<>();
        for (int i = 0; i <= distance; i++) {
            BlockPos centerPos = start.relative(direction, i);
            if (!underwaterStatus.containsKey(centerPos)) {
                underwaterStatus.put(centerPos, UnderwaterDetector.isSegmentUnderwater(level, centerPos, normal));
            }
        }
        return underwaterStatus;
    }

    static void applyStraightLineTunnelDecoration(ServerLevel level, BlockPos start, Direction direction,
                                                    int distance, ClearingTypes.TunnelStatusMaps statusMaps, Vec3 normal,
                                                    int torchOffset) {
        int segmentIndex = torchOffset;
        for (int i = 0; i <= distance; i++) {
            BlockPos centerPos = start.relative(direction, i);
            boolean isUnderground = statusMaps.underground().getOrDefault(centerPos, false);
            boolean isUnderwater = statusMaps.underwater().getOrDefault(centerPos, false);
            if (isUnderground || isUnderwater) {
                TunnelFinisher.decorateSegment(level, centerPos, normal, segmentIndex, RailwaysUntoldConfig.getDefault());
                segmentIndex++;
            }
        }
    }

    /**
     * Fills gaps between segments to ensure continuous coverage along the path.
     */
    static List<SegmentData> densifySegments(List<SegmentData> segments) {
        if (segments.size() < 2) {
            return segments;
        }

        List<SegmentData> densified = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            SegmentData curr = segments.get(i);
            densified.add(curr);
            if (i < segments.size() - 1) {
                SegmentData next = segments.get(i + 1);

                BlockPos currPos = BlockPos.containing(curr.position);
                BlockPos nextPos = BlockPos.containing(next.position);

                int manhattanDist = Math.abs(nextPos.getX() - currPos.getX())
                        + Math.abs(nextPos.getY() - currPos.getY())
                        + Math.abs(nextPos.getZ() - currPos.getZ());

                if (manhattanDist > 1) {
                    List<BlockPos> intermediateBlocks = getIntermediateBlocks(currPos, nextPos);

                    for (int j = 0; j < intermediateBlocks.size(); j++) {
                        BlockPos fillPos = intermediateBlocks.get(j);

                        double t = (double) (j + 1) / (intermediateBlocks.size() + 1);
                        Vec3 interpolatedNormal = slerp(t, curr.normal, next.normal);
                        Vec3 fillVec3 = Vec3.atCenterOf(fillPos);
                        densified.add(new SegmentData(fillVec3, interpolatedNormal, false));
                    }
                }
            }
        }

        return densified;
    }
}
