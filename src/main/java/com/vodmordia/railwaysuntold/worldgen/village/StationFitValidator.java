package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Compile-time check that a precision route's natural final pose is a usable station
 * site for a given village.
 *
 */
public final class StationFitValidator {

    /** Max Manhattan distance from final pose to village envelope for a station to "serve" it. */
    private static final int STATION_SERVE_DISTANCE = 48;

    /** Safety margin around station footprint vs village pieces (matches StationCommitter). */
    private static final int COLLISION_MARGIN = 5;

    /** Distance past the station's exit to probe for tangency. A non-zero value avoids
     *  borderline rejections where the exit lands exactly on the village edge. */
    private static final int TANGENCY_MARGIN = 4;

    private StationFitValidator() {}

    public record Result(boolean ok, String reason) {
        public static Result success() { return new Result(true, null); }
        public static Result failure(String reason) { return new Result(false, reason); }
    }

    /**
     * Returns true if a station at the given pose would have its runway exit inside the
     * village envelope - i.e., the runway is radial (pointing into the village body)
     * rather than tangent.
     */
    public static boolean isRadialArrival(BlockPos pos, Direction dir,
                                           PredictedVillageLayout layout, SelectedStation station) {
        int stationLength = Math.max(
                station.schematic().getWidth(), station.schematic().getLength());
        BlockPos exitProbe = pos.relative(dir, stationLength + TANGENCY_MARGIN);
        return isInside(layout.totalBounds(), exitProbe);
    }

    /**
     * Evaluates the three fit criteria for the given arrival pose.
     */
    public static Result validate(BlockPos finalPos, Direction finalDir,
                                   PredictedVillageLayout layout, SelectedStation station) {
        // 1. Proximity - station must be near enough to actually serve the village.
        int proximity = distanceTo(finalPos, layout.totalBounds());
        if (proximity > STATION_SERVE_DISTANCE) {
            return Result.failure("station at " + finalPos + " is " + proximity
                    + " blocks from village envelope (max " + STATION_SERVE_DISTANCE + ")");
        }

        // 2. Collision - rotated footprint must not overlap any village piece.
        if (!layout.pieceBounds().isEmpty()
                && StationPlacementGeometry.wouldCollideWithVillagePieces(
                        finalPos, finalDir, station, layout.pieceBounds(), COLLISION_MARGIN)) {
            return Result.failure("station footprint at " + finalPos + " heading " + finalDir
                    + " collides with village pieces");
        }

        // 3. Tangency - extending past the station exit in the arrival direction must
        //    not enter the village body. If it does, the runway points into the village
        //    and continuation track will drive through it.
        if (isRadialArrival(finalPos, finalDir, layout, station)) {
            return Result.failure("station exit probe extends into village body at "
                    + finalPos + " heading " + finalDir + " (runway is radial)");
        }

        return Result.success();
    }

    /**
     * Manhattan distance from {@code pos} to the nearest point on {@code bounds} in 2D.
     * Returns 0 if {@code pos} is inside {@code bounds} (ignoring Y).
     */
    private static int distanceTo(BlockPos pos, BoundingBox bounds) {
        int dx = Math.max(0, Math.max(bounds.minX() - pos.getX(), pos.getX() - bounds.maxX()));
        int dz = Math.max(0, Math.max(bounds.minZ() - pos.getZ(), pos.getZ() - bounds.maxZ()));
        return dx + dz;
    }

    /**
     * 2D bounds-contains check (Y is ignored). Used to decide whether the tangency
     * probe landed inside the village envelope.
     */
    private static boolean isInside(BoundingBox bounds, BlockPos pos) {
        return pos.getX() >= bounds.minX() && pos.getX() <= bounds.maxX()
                && pos.getZ() >= bounds.minZ() && pos.getZ() <= bounds.maxZ();
    }
}
