package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.track.SlopeValidator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Inserts cardinal-axis anchor waypoints at the ends of a coarse route's corner list so the precision
 * compiler's first and last runs are aligned to the head's forward direction and the station's arrival
 * direction respectively. Both insertions are no-ops when the path already runs straight along the axis
 * for at least the anchor distance. Pure geometry on BlockPos lists - no world.
 */
public final class WaypointAxisInjector {

    private WaypointAxisInjector() {
    }

    /**
     * The on-axis anchor distance: far enough that a subsequent geometric bend has room for its
     * transition curve without overlapping the route end. {@code max(SAMPLE_INTERVAL,
     * minCurveRadius + straight endpoint padding)}.
     */
    private static int anchorDistance() {
        return Math.max(CoarseRoutePlanner.SAMPLE_INTERVAL,
                RailwaysUntoldConfig.getMinCurveRadius() + SlopeValidator.STRAIGHT_ENDPOINT_BLOCKS);
    }

    /**
     * Runway start for a village approach: a point on the arrival axis, backed off from the arrival pose
     * past the village body's extent (plus a margin) in the direction opposite the arrival. Routing the
     * approach to this point instead of straight to the arrival keeps the tangent final run alongside the
     * body rather than inside it, and makes the avoidance detour exit on the arrival side. The lateral
     * coordinate matches the arrival pose, so the runway-to-arrival leg is cardinal along the arrival axis.
     * Distance is at least the normal anchor distance, so a body that is shallow along the axis still
     * leaves room for the transition curve.
     */
    public static BlockPos runwayStartClearingBody(BlockPos target, Direction arrivalDir,
                                                    BoundingBox body, int margin) {
        int backExtent = switch (arrivalDir) {
            case EAST  -> target.getX() - body.minX();
            case WEST  -> body.maxX() - target.getX();
            case SOUTH -> target.getZ() - body.minZ();
            case NORTH -> body.maxZ() - target.getZ();
            default    -> 0;
        };
        int dist = Math.max(anchorDistance(), backExtent + margin);
        return target.relative(arrivalDir.getOpposite(), dist);
    }

    /**
     * Prepends an on-axis waypoint so the first sampled coarse run matches the head's
     * current forward direction. The precision compiler's first run then matches
     * headDirection and no alignment curve / diagonal entry is emitted at the track
     * tip. Distance is max(SAMPLE_INTERVAL, minCurveRadius + endpoint padding) - any
     * subsequent geometric bend has room for its transition curve without
     * overlapping the tip.
     */
    public static List<BlockPos> injectForwardAxisPrefix(List<BlockPos> pathWaypoints,
                                                          BlockPos start,
                                                          Direction headDirection) {
        int prefix = anchorDistance();
        BlockPos onAxis = start.relative(headDirection, prefix);

        // If the original path's second point is already on-axis ahead of the tip,
        // the prefix is a no-op - skip insertion to avoid redundant waypoints.
        if (pathWaypoints.size() >= 2) {
            BlockPos next = pathWaypoints.get(1);
            int lateral = switch (headDirection) {
                case EAST, WEST -> next.getZ() - start.getZ();
                case SOUTH, NORTH -> next.getX() - start.getX();
                default -> 0;
            };
            int forward = switch (headDirection) {
                case EAST -> next.getX() - start.getX();
                case WEST -> start.getX() - next.getX();
                case SOUTH -> next.getZ() - start.getZ();
                case NORTH -> start.getZ() - next.getZ();
                default -> 0;
            };
            if (lateral == 0 && forward >= prefix) {
                return pathWaypoints;
            }
        }

        List<BlockPos> prefixed = new ArrayList<>(pathWaypoints.size() + 1);
        prefixed.add(pathWaypoints.isEmpty() ? start : pathWaypoints.get(0));
        prefixed.add(onAxis);
        for (int i = 1; i < pathWaypoints.size(); i++) {
            prefixed.add(pathWaypoints.get(i));
        }
        return prefixed;
    }

    /**
     * Mirror of {@link #injectForwardAxisPrefix} for the route's tail. Adds a waypoint
     * one curve-radius back from the target along the arrival cardinal so the run
     * classifier sees a cardinal-axis final segment.
     */
    public static List<BlockPos> injectArrivalAxisSuffix(List<BlockPos> pathWaypoints,
                                                         BlockPos target,
                                                         Direction arrivalDir) {
        if (pathWaypoints.size() < 2) {
            return pathWaypoints;
        }
        int suffix = anchorDistance();
        BlockPos onAxis = target.relative(arrivalDir.getOpposite(), suffix);

        // No-op if the second-to-last waypoint is already on the arrival axis behind
        // the target by at least the suffix distance - the natural path already lands
        // cardinal-aligned, no anchor needed.
        BlockPos prev = pathWaypoints.get(pathWaypoints.size() - 2);
        int lateral = switch (arrivalDir) {
            case EAST, WEST -> prev.getZ() - target.getZ();
            case SOUTH, NORTH -> prev.getX() - target.getX();
            default -> 0;
        };
        int forward = switch (arrivalDir) {
            case EAST -> target.getX() - prev.getX();
            case WEST -> prev.getX() - target.getX();
            case SOUTH -> target.getZ() - prev.getZ();
            case NORTH -> prev.getZ() - target.getZ();
            default -> 0;
        };
        if (lateral == 0 && forward >= suffix) {
            return pathWaypoints;
        }

        List<BlockPos> suffixed = new ArrayList<>(pathWaypoints.size() + 1);
        for (int i = 0; i < pathWaypoints.size() - 1; i++) {
            suffixed.add(pathWaypoints.get(i));
        }
        suffixed.add(onAxis);
        suffixed.add(pathWaypoints.get(pathWaypoints.size() - 1));
        return suffixed;
    }
}
