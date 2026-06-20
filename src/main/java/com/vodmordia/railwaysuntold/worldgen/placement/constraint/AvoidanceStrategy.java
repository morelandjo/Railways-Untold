package com.vodmordia.railwaysuntold.worldgen.placement.constraint;

import com.vodmordia.railwaysuntold.util.spatial.DiagonalDirection;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Strategy interface for obstacle avoidance during track placement.
 *
 */
public interface AvoidanceStrategy {

    /**
     * Attempts to apply avoidance logic for this strategy's obstacle type.
     *
     * @param context The avoidance context containing all necessary data
     * @return Optional containing the avoidance decision if this strategy applies and found a solution,
     *         empty if this strategy doesn't apply or couldn't find a solution
     */
    Optional<PlacementDecision> apply(AvoidanceContext context);

    /**
     * Returns the priority of this strategy. Lower values are checked first.
     * This allows controlling the order in which different obstacle types are checked.
     *
     * @return Priority value (lower = higher priority)
     */
    int getPriority();

    /**
     * Information about a detected obstacle.
     */
    record ObstacleInfo(BoundingBox box, String description) {}

    /**
     * Context object containing all data needed for avoidance calculations.
     * Reduces parameter passing and makes the API cleaner.
     */
    record AvoidanceContext(
            TrackExpansionHead head,
            BlockPos currentPos,
            Direction expandDir,
            @Nullable DiagonalDirection diagonalDir,
            TerrainScanner.TerrainScan scan,
            ServerLevel level,
            BlockPos destPos,
            int destChunkX,
            int destChunkZ,
            boolean alreadyInTunnel
    ) {
        /** Cardinal lookahead distance. */
        private static final int CARDINAL_LOOKAHEAD = 64;
        /** Diagonal lookahead distance (longer due to √2 step). */
        private static final int DIAGONAL_LOOKAHEAD = 80;

        /**
         * Creates a context for cardinal-direction avoidance checks.
         */
        public static AvoidanceContext create(
                TrackExpansionHead head,
                BlockPos currentPos,
                Direction expandDir,
                TerrainScanner.TerrainScan scan,
                ServerLevel level) {

            BlockPos destPos = currentPos.relative(expandDir, 16);
            int destChunkX = destPos.getX() >> 4;
            int destChunkZ = destPos.getZ() >> 4;
            boolean alreadyInTunnel = TunnelDetection.isAlreadyInTunnel(level, currentPos);

            return new AvoidanceContext(
                    head, currentPos, expandDir, null, scan, level,
                    destPos, destChunkX, destChunkZ, alreadyInTunnel
            );
        }

        /**
         * X coordinate of the trajectory ray endpoint.
         */
        public int trajectoryEndX() {
            if (diagonalDir != null) {
                return currentPos.getX() + diagonalDir.getStepX() * DIAGONAL_LOOKAHEAD;
            }
            return currentPos.getX() + expandDir.getStepX() * CARDINAL_LOOKAHEAD;
        }

        /**
         * Z coordinate of the trajectory ray endpoint.
         */
        public int trajectoryEndZ() {
            if (diagonalDir != null) {
                return currentPos.getZ() + diagonalDir.getStepZ() * DIAGONAL_LOOKAHEAD;
            }
            return currentPos.getZ() + expandDir.getStepZ() * CARDINAL_LOOKAHEAD;
        }
    }
}
