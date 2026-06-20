package com.vodmordia.railwaysuntold.worldgen.placement.analyzer;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.SegmentAABB;
import com.vodmordia.railwaysuntold.worldgen.tracking.SegmentGeometryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared service for crossing detection geometry calculations.
 */
public class CrossingDetectionService {

    private static final int SAMPLE_POINTS = 20;
    private static final int HORIZONTAL_BUFFER = 3;
    private static final int VERTICAL_BUFFER = 1;
    private static final int BRANCH_PROXIMITY_DISTANCE = 32;

    /**
     * Information about a detected crossing with an existing track.
     */
    public record CrossingInfo(
            BlockPos crossingPoint,
            int existingTrackY,
            int proposedTrackY,
            Direction existingDirection,
            boolean isPerpendicular,
            ConnectedSegment existingSegment
    ) {
    }

    /**
     * Checks if a proposed segment intersects an existing segment.
     *
     * @param propStart     Start of proposed segment
     * @param propEnd       End of proposed segment
     * @param propDirection Direction of travel
     * @param existing      The existing segment to check against
     * @return CrossingInfo if intersection found, null otherwise
     */
    @Nullable
    public static CrossingInfo checkSegmentIntersection(
            BlockPos propStart, BlockPos propEnd, Direction propDirection,
            ConnectedSegment existing) {
        return checkSegmentIntersection(propStart, propEnd, propDirection, existing, true);
    }

    /**
     * Variant that controls the branch-junction exemption. When {@code skipNearbyBranch} is false the
     * proximity exemption is not applied, so a genuine crossing whose proposed segment merely starts near
     * the existing track's start is still reported. Placement keeps the exemption (a branching head emanates
     * from its parent track and must not read that junction as a collision); the placed-track crossing
     * detector disables it for mid-route segments, where a nearby track is a real crossing to resolve, not
     * a junction.
     */
    @Nullable
    public static CrossingInfo checkSegmentIntersection(
            BlockPos propStart, BlockPos propEnd, Direction propDirection,
            ConnectedSegment existing, boolean skipNearbyBranch) {

        if (SegmentGeometryUtil.sharesEndpoint(propStart, propEnd, existing.start, existing.end)) {
            return null;
        }

        int horizontalClearance = RailwaysUntoldConfig.getHorizontalExpansion() + HORIZONTAL_BUFFER;
        int verticalClearance = RailwaysUntoldConfig.getVerticalExpansion() + VERTICAL_BUFFER;

        if (!boundingBoxesOverlap(propStart, propEnd, existing.aabb, horizontalClearance, verticalClearance)) {
            return null;
        }

        if (skipNearbyBranch && isNearbyBranchSegment(propStart, propDirection, existing)) {
            return null;
        }

        List<BlockPos> existingPositions = existing.hasCurveGeometry()
                ? existing.curvePositions
                : interpolateSegmentPositions(existing.start, existing.end, SAMPLE_POINTS);

        IntersectionResult result = findClosestIntersection(propStart, propEnd, existingPositions, horizontalClearance, verticalClearance);

        if (result == null) {
            return null;
        }

        return buildCrossingInfo(propStart, propEnd, propDirection, existing, existingPositions, result);
    }

    private static boolean isNearbyBranchSegment(BlockPos propStart, Direction propDirection, ConnectedSegment existing) {
        if (!isPointNearInfiniteLine(existing.start, propStart, propDirection, 3)) {
            return false;
        }
        double startDistX = propStart.getX() - existing.start.getX();
        double startDistZ = propStart.getZ() - existing.start.getZ();
        double startDistSq = startDistX * startDistX + startDistZ * startDistZ;
        return startDistSq < BRANCH_PROXIMITY_DISTANCE * BRANCH_PROXIMITY_DISTANCE;
    }

    private record IntersectionResult(double propT, int existIdx) {}

    @Nullable
    private static IntersectionResult findClosestIntersection(BlockPos propStart, BlockPos propEnd,
                                                               List<BlockPos> existingPositions,
                                                               int horizontalClearance, int verticalClearance) {
        double minDistSq = Double.MAX_VALUE;
        double bestPropT = 0;
        int bestExistIdx = 0;

        int startSample = Math.max(1, SAMPLE_POINTS / 8);
        for (int i = startSample; i <= SAMPLE_POINTS; i++) {
            double propT = (double) i / SAMPLE_POINTS;

            double propX = propStart.getX() + propT * (propEnd.getX() - propStart.getX());
            double propY = propStart.getY() + propT * (propEnd.getY() - propStart.getY());
            double propZ = propStart.getZ() + propT * (propEnd.getZ() - propStart.getZ());

            for (int j = 0; j < existingPositions.size(); j++) {
                BlockPos existPos = existingPositions.get(j);

                double dx = propX - existPos.getX();
                double dz = propZ - existPos.getZ();
                double horizontalDistSq = dx * dx + dz * dz;
                double dy = Math.abs(propY - existPos.getY());

                if (horizontalDistSq < horizontalClearance * horizontalClearance && dy < verticalClearance) {
                    if (horizontalDistSq < minDistSq) {
                        minDistSq = horizontalDistSq;
                        bestPropT = propT;
                        bestExistIdx = j;
                    }
                }
            }
        }

        return minDistSq < Double.MAX_VALUE ? new IntersectionResult(bestPropT, bestExistIdx) : null;
    }

    private static CrossingInfo buildCrossingInfo(BlockPos propStart, BlockPos propEnd, Direction propDirection,
                                                   ConnectedSegment existing, List<BlockPos> existingPositions,
                                                   IntersectionResult result) {
        int crossX = (int) (propStart.getX() + result.propT * (propEnd.getX() - propStart.getX()));
        int crossZ = (int) (propStart.getZ() + result.propT * (propEnd.getZ() - propStart.getZ()));
        int proposedY = interpolateY(propStart, propEnd, result.propT);
        BlockPos existingPos = existingPositions.get(result.existIdx);
        int existingY = existingPos.getY();

        BlockPos crossingPoint = new BlockPos(crossX, (proposedY + existingY) / 2, crossZ);

        Direction existingDir = DirectionUtil.getSegmentDirection(existing.start, existing.end);
        boolean perpendicular = DirectionUtil.arePerpendicular(propDirection, existingDir);

        return new CrossingInfo(crossingPoint, existingY, proposedY, existingDir, perpendicular, existing);
    }

    /**
     * Interpolates Y value between two positions.
     */
    public static int interpolateY(BlockPos start, BlockPos end, double t) {
        return (int) Math.round(start.getY() + t * (end.getY() - start.getY()));
    }

    /**
     * Creates interpolated positions along a segment.
     */
    public static List<BlockPos> interpolateSegmentPositions(BlockPos start, BlockPos end, int sampleCount) {
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i <= sampleCount; i++) {
            double t = (double) i / sampleCount;
            int x = (int) Math.round(start.getX() + t * (end.getX() - start.getX()));
            int y = (int) Math.round(start.getY() + t * (end.getY() - start.getY()));
            int z = (int) Math.round(start.getZ() + t * (end.getZ() - start.getZ()));
            positions.add(new BlockPos(x, y, z));
        }
        return positions;
    }

    /**
     * Checks if a point is near an infinite line extending from linePoint in lineDirection.
     */
    public static boolean isPointNearInfiniteLine(BlockPos point, BlockPos linePoint, Direction lineDirection, double maxDistance) {
        return switch (lineDirection) {
            case NORTH, SOUTH -> Math.abs(point.getX() - linePoint.getX()) <= maxDistance;
            case EAST, WEST -> Math.abs(point.getZ() - linePoint.getZ()) <= maxDistance;
            default -> false;
        };
    }

    /**
     * Fast AABB overlap test to reject non-overlapping segments before point-to-point checks.
     */
    public static boolean boundingBoxesOverlap(
            BlockPos start1, BlockPos end1,
            SegmentAABB existingAABB,
            int horizontalBuffer, int verticalBuffer) {
        int minX1 = Math.min(start1.getX(), end1.getX()) - horizontalBuffer;
        int maxX1 = Math.max(start1.getX(), end1.getX()) + horizontalBuffer;
        int minZ1 = Math.min(start1.getZ(), end1.getZ()) - horizontalBuffer;
        int maxZ1 = Math.max(start1.getZ(), end1.getZ()) + horizontalBuffer;
        int minY1 = Math.min(start1.getY(), end1.getY()) - verticalBuffer;
        int maxY1 = Math.max(start1.getY(), end1.getY()) + verticalBuffer;

        return existingAABB.overlaps(minX1, maxX1, minY1, maxY1, minZ1, maxZ1);
    }
}
