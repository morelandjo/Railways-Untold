package com.vodmordia.railwaysuntold.worldgen.tracking;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * Data classes for connected boundary tracking.
 */
public class BoundarySegmentData {

    /**
     * Pre-computed AABB for fast overlap testing.
     */
    public record SegmentAABB(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        public static SegmentAABB compute(BlockPos start, BlockPos end, List<BlockPos> curvePositions) {
            int minX, maxX, minY, maxY, minZ, maxZ;
            if (curvePositions != null && !curvePositions.isEmpty()) {
                minX = Integer.MAX_VALUE;
                maxX = Integer.MIN_VALUE;
                minY = Integer.MAX_VALUE;
                maxY = Integer.MIN_VALUE;
                minZ = Integer.MAX_VALUE;
                maxZ = Integer.MIN_VALUE;
                for (BlockPos pos : curvePositions) {
                    minX = Math.min(minX, pos.getX());
                    maxX = Math.max(maxX, pos.getX());
                    minY = Math.min(minY, pos.getY());
                    maxY = Math.max(maxY, pos.getY());
                    minZ = Math.min(minZ, pos.getZ());
                    maxZ = Math.max(maxZ, pos.getZ());
                }
            } else {
                minX = Math.min(start.getX(), end.getX());
                maxX = Math.max(start.getX(), end.getX());
                minY = Math.min(start.getY(), end.getY());
                maxY = Math.max(start.getY(), end.getY());
                minZ = Math.min(start.getZ(), end.getZ());
                maxZ = Math.max(start.getZ(), end.getZ());
            }
            return new SegmentAABB(minX, maxX, minY, maxY, minZ, maxZ);
        }

        public boolean overlaps(int otherMinX, int otherMaxX, int otherMinY, int otherMaxY, int otherMinZ, int otherMaxZ) {
            return minX <= otherMaxX && maxX >= otherMinX &&
                   minY <= otherMaxY && maxY >= otherMinY &&
                   minZ <= otherMaxZ && maxZ >= otherMinZ;
        }
    }

    /**
     * Represents a confirmed connected segment.
     */
    public static class ConnectedSegment {
        public final BlockPos start;
        public final BlockPos end;
        public final ConnectionType type;
        public final List<BlockPos> curvePositions;
        public final UUID headId;
        public final SegmentAABB aabb;

        public ConnectedSegment(BlockPos start, BlockPos end, ConnectionType type, List<BlockPos> curvePositions, UUID headId) {
            this.start = start;
            this.end = end;
            this.type = type;
            this.curvePositions = curvePositions;
            this.headId = headId;
            this.aabb = SegmentAABB.compute(start, end, curvePositions);
        }

        public boolean hasCurveGeometry() {
            return curvePositions != null && !curvePositions.isEmpty();
        }
    }

    /**
     * Type of track connection.
     */
    public enum ConnectionType {
        BEZIER,
        STRAIGHT
    }
}
