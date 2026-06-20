package com.vodmordia.railwaysuntold.worldgen.integration.create;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;

/**
 * Geometry calculations for track connections.
 */
public class TrackGeometryCalculator {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Lazy-cached ITrackBlock methods
    private static volatile java.lang.reflect.Method cachedGetNearestTrackAxis;
    private static volatile java.lang.reflect.Method cachedGetUpNormal;
    private static volatile java.lang.reflect.Method cachedGetCurveStart;

    // Lazy-cached Pair accessor methods
    private static volatile java.lang.reflect.Method cachedPairGetFirst;
    private static volatile java.lang.reflect.Method cachedPairGetSecond;

    public record TrackEndpointData(Vec3 axis, Vec3 end, BlockPos pos, BlockState state, Object track) {}

    /**
     * Gets the nearest track axis using Create's ITrackBlock.getNearestTrackAxis method.
     *
     * @param level            The level
     * @param pos              Block position
     * @param state            Block state
     * @param trackBlock       Track block instance
     * @param iTrackBlockClass ITrackBlock interface class
     * @param lookVec          Look vector direction
     * @return Axis pair from Create's getNearestTrackAxis
     * @throws ReflectiveOperationException If reflection fails
     */
    public static Object getNearestTrackAxis(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                                             Object trackBlock, Class<?> iTrackBlockClass, Vec3 lookVec) throws ReflectiveOperationException {
        if (cachedGetNearestTrackAxis == null) {
            cachedGetNearestTrackAxis = iTrackBlockClass.getMethod("getNearestTrackAxis",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class,
                    Vec3.class);
        }
        return cachedGetNearestTrackAxis.invoke(trackBlock, level, pos, state, lookVec);
    }

    /**
     * Extracts the Vec3 axis from a Pair<Vec3, AxisDirection>.
     *
     * @param pair The axis pair from Create
     * @return The axis vector with proper scaling applied
     * @throws ReflectiveOperationException If reflection fails
     */
    public static Vec3 extractAxisVectorFromPair(Object pair) throws ReflectiveOperationException {
        if (cachedPairGetFirst == null) {
            cachedPairGetFirst = pair.getClass().getMethod("getFirst");
            cachedPairGetSecond = pair.getClass().getMethod("getSecond");
        }

        Vec3 axis = (Vec3) cachedPairGetFirst.invoke(pair);
        Object axisDirection = cachedPairGetSecond.invoke(pair);

        // Check if it's POSITIVE or NEGATIVE and scale accordingly
        // AxisDirection.POSITIVE means scale by -1 (from Create's tryConnect)
        // Use enum name() for robust comparison, fallback to toString()/class name
        boolean isPositive = isAxisDirectionPositive(axisDirection);
        if (isPositive) {
            axis = axis.scale(-1);
        }

        return axis;
    }

    /**
     * Determines if an AxisDirection is POSITIVE
     *
     * @param axisDirection The AxisDirection enum value
     * @return true if the direction is POSITIVE
     */
    private static boolean isAxisDirectionPositive(Object axisDirection) {
        if (axisDirection == null) {
            return false;
        }

        try {
            // First try: use Enum.name() for comparison
            if (axisDirection instanceof Enum) {
                String enumName = ((Enum<?>) axisDirection).name();
                return "POSITIVE".equals(enumName);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Enum name() check failed for AxisDirection, using toString fallback: {}", e.getMessage());
        }

        // Fallback: check toString() or class name
        String representation = axisDirection.toString();
        return representation.contains("POSITIVE") ||
                axisDirection.getClass().getSimpleName().contains("POSITIVE");
    }

    /**
     * Gets the track normal (up vector) from a track block.
     *
     * @param level            The level
     * @param pos              Block position
     * @param state            Block state
     * @param trackBlock       Track block instance
     * @param iTrackBlockClass ITrackBlock interface class
     * @return The normal vector
     * @throws ReflectiveOperationException If reflection fails
     */
    public static Vec3 getTrackNormal(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                                      Object trackBlock, Class<?> iTrackBlockClass) throws ReflectiveOperationException {
        if (cachedGetUpNormal == null) {
            cachedGetUpNormal = iTrackBlockClass.getMethod("getUpNormal",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    BlockState.class);
        }
        return (Vec3) cachedGetUpNormal.invoke(trackBlock, level, pos, state);
    }

    /**
     * Gets the curve start position from a track block.
     *
     * @param level            The level
     * @param pos              Block position
     * @param state            Block state
     * @param trackBlock       Track block instance
     * @param iTrackBlockClass ITrackBlock interface class
     * @param axis             Axis vector
     * @return The curve start position
     * @throws ReflectiveOperationException If reflection fails
     */
    public static Vec3 getTrackCurveStart(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                                          Object trackBlock, Class<?> iTrackBlockClass, Vec3 axis) throws ReflectiveOperationException {
        if (cachedGetCurveStart == null) {
            cachedGetCurveStart = iTrackBlockClass.getMethod("getCurveStart",
                    net.minecraft.world.level.BlockGetter.class,
                    BlockPos.class,
                    BlockState.class,
                    Vec3.class);
        }
        return (Vec3) cachedGetCurveStart.invoke(trackBlock, level, pos, state, axis);
    }

    /**
     * Intersects two lines in 2D (Y axis), returns [t, u] or null if parallel.
     * This is a simplified version of VecHelper.intersect from Create.
     *
     * @param start1 Start point of first line
     * @param start2 Start point of second line
     * @param dir1   Direction vector of first line
     * @param dir2   Direction vector of second line
     * @return Array [t, u] of intersection parameters, or null if parallel
     */
    @Nullable
    public static double[] intersectLines2D(Vec3 start1, Vec3 start2, Vec3 dir1, Vec3 dir2) {
        // Project to XZ plane for intersection test
        double x1 = start1.x;
        double z1 = start1.z;
        double dx1 = dir1.x;
        double dz1 = dir1.z;

        double x2 = start2.x;
        double z2 = start2.z;
        double dx2 = dir2.x;
        double dz2 = dir2.z;

        double det = dx1 * dz2 - dz1 * dx2;

        // Parallel check
        if (Math.abs(det) < 1e-6) {
            return null;
        }

        double t = ((x2 - x1) * dz2 - (z2 - z1) * dx2) / det;
        double u = ((x2 - x1) * dz1 - (z2 - z1) * dx1) / det;

        return new double[]{t, u};
    }

    /**
     * Applies Create's axis flipping logic to ensure axes point in correct directions.
     *
     * @param firstAxis  Initial first axis
     * @param secondAxis Initial second axis
     * @param firstEnd   First curve start position
     * @param secondEnd  Second curve start position
     * @return AxisFlipResult containing potentially flipped axes and new curve start positions
     */
    public static AxisFlipResult applyAxisFlipping(TrackEndpointData first, TrackEndpointData second,
                                                   net.minecraft.world.level.Level level,
                                                   Class<?> iTrackBlockClass) throws ReflectiveOperationException {
        Vec3 firstAxis = first.axis();
        Vec3 secondAxis = second.axis();
        Vec3 firstEnd = first.end();
        Vec3 secondEnd = second.end();
        Vec3 normedAxis1 = firstAxis.normalize();
        Vec3 normedAxis2 = secondAxis.normalize();

        // Flip first axis if it points away from second track
        if (firstAxis.dot(secondEnd.subtract(firstEnd)) < 0) {
            firstAxis = firstAxis.scale(-1);
            normedAxis1 = normedAxis1.scale(-1);
            firstEnd = getTrackCurveStart(level, first.pos(), first.state(), first.track(), iTrackBlockClass, firstAxis);
            if (firstEnd == null) {
                throw com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException
                        .atPosition("first track curve start after flip at " + first.pos());
            }
        }

        // Flip second axis if needed (parallel check or intersection check)
        double[] intersect = intersectLines2D(firstEnd, secondEnd, normedAxis1, normedAxis2);
        boolean parallel = intersect == null;

        if ((parallel && normedAxis1.dot(normedAxis2) > 0) || (!parallel && (intersect[0] < 0 || intersect[1] < 0))) {
            secondAxis = secondAxis.scale(-1);
            secondEnd = getTrackCurveStart(level, second.pos(), second.state(), second.track(), iTrackBlockClass, secondAxis);
            if (secondEnd == null) {
                throw com.vodmordia.railwaysuntold.worldgen.integration.deferred.BlockEntitiesNotReadyException
                        .atPosition("second track curve start after flip at " + second.pos());
            }
        }

        return new AxisFlipResult(firstAxis, secondAxis, firstEnd, secondEnd);
    }

    /**
     * Calculates a tilted normal vector for sloped/elevated tracks.
     * Create uses normals to determine the "up" direction at track endpoints.
     * For sloped tracks, the normal tilts in the direction of travel.
     *
     * @param baseNormal         The base normal vector (typically (0, 1, 0) for flat tracks)
     * @param axis               The track axis (direction of travel)
     * @param elevationChange    The vertical change in blocks (positive = going up)
     * @param horizontalDistance The horizontal distance in blocks
     * @return A tilted normal vector for the sloped track
     */
    public static Vec3 calculateElevatedNormal(Vec3 baseNormal, Vec3 axis, int elevationChange, double horizontalDistance) {
        if (elevationChange == 0 || horizontalDistance <= 0) {
            return baseNormal;
        }

        // Calculate slope: rise/run
        double slope = elevationChange / horizontalDistance;

        // Clamp slope to reasonable values (prevent too steep)
        // Max ~45 degrees (1:1 ratio) to prevent Create's "too_steep" error
        slope = Math.max(-1.0, Math.min(1.0, slope));

        // Tilt the normal in the direction of travel
        // For a track going up, the normal tilts backward (opposite to axis)
        // For a track going down, the normal tilts forward (same as axis)
        Vec3 axisNormalized = axis.normalize();

        // The tilted normal adds a horizontal component based on slope
        // When going up (positive elevation), slope is positive, normal tilts back (-axis direction)
        Vec3 tiltedNormal = new Vec3(
                baseNormal.x - axisNormalized.x * slope,
                baseNormal.y,
                baseNormal.z - axisNormalized.z * slope
        ).normalize();

        return tiltedNormal;
    }

    /**
     * Calculates a tilted normal vector from a direct slope value (rise/run ratio).
     * Used for slope-matched normals at segment junctions.
     */
    public static Vec3 calculateSlopeNormal(Vec3 baseNormal, Vec3 axis, double slope) {
        if (slope == 0.0) return baseNormal;

        slope = Math.max(-1.0, Math.min(1.0, slope));
        Vec3 axisNormalized = axis.normalize();

        Vec3 tiltedNormal = new Vec3(
                baseNormal.x - axisNormalized.x * slope,
                baseNormal.y,
                baseNormal.z - axisNormalized.z * slope
        ).normalize();

        return tiltedNormal;
    }

    /**
     * Result of axis flipping operation.
     */
    public static class AxisFlipResult {
        public final Vec3 firstAxis;
        public final Vec3 secondAxis;
        public final Vec3 firstEnd;
        public final Vec3 secondEnd;

        public AxisFlipResult(Vec3 firstAxis, Vec3 secondAxis, Vec3 firstEnd, Vec3 secondEnd) {
            this.firstAxis = firstAxis;
            this.secondAxis = secondAxis;
            this.firstEnd = firstEnd;
            this.secondEnd = secondEnd;
        }
    }
}
