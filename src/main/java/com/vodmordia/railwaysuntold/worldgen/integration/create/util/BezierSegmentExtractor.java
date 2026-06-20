package com.vodmordia.railwaysuntold.worldgen.integration.create.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility to extract segment data from Create mod's BezierConnection.
 */
public class BezierSegmentExtractor {

    // Lazy-cached segment fields
    private static volatile java.lang.reflect.Field cachedIndexField;
    private static volatile java.lang.reflect.Field cachedPositionField;
    private static volatile java.lang.reflect.Field cachedDerivativeField;
    private static volatile java.lang.reflect.Field cachedFaceNormalField;
    private static volatile java.lang.reflect.Field cachedNormalField;

    // Lazy-cached endpoint fields
    private static volatile java.lang.reflect.Field cachedBePositionsField;

    /**
     * Immutable segment data extracted from Create's BezierConnection.Segment.
     */
    public record BezierSegmentData(
            int index,
            Vec3 position,
            Vec3 derivative,
            Vec3 faceNormal,
            Vec3 normal
    ) {
    }

    /**
     * Endpoint positions extracted from a BezierConnection.
     */
    public record BezierEndpoints(
            BlockPos first,
            BlockPos second
    ) {
    }

    /**
     * Extracts all segment data from a BezierConnection object.
     *
     * @param bezierConnection Create's BezierConnection object (or null)
     * @return List of extracted segment data, or empty list if extraction fails
     */
    public static List<BezierSegmentData> extractSegments(Object bezierConnection) {
        if (bezierConnection == null) {
            return Collections.emptyList();
        }

        try {
            List<BezierSegmentData> segments = new ArrayList<>();

            // BezierConnection implements Iterable<Segment>
            Iterable<?> segmentIterator = (Iterable<?>) bezierConnection;

            for (Object segmentObj : segmentIterator) {
                if (cachedIndexField == null) {
                    Class<?> segmentClass = segmentObj.getClass();
                    cachedIndexField = segmentClass.getField("index");
                    cachedPositionField = segmentClass.getField("position");
                    cachedDerivativeField = segmentClass.getField("derivative");
                    cachedFaceNormalField = segmentClass.getField("faceNormal");
                    cachedNormalField = segmentClass.getField("normal");
                }

                int index = cachedIndexField.getInt(segmentObj);
                Vec3 position = (Vec3) cachedPositionField.get(segmentObj);
                Vec3 derivative = (Vec3) cachedDerivativeField.get(segmentObj);
                Vec3 faceNormal = (Vec3) cachedFaceNormalField.get(segmentObj);
                Vec3 normal = (Vec3) cachedNormalField.get(segmentObj);

                segments.add(new BezierSegmentData(index, position, derivative, faceNormal, normal));
            }

            return Collections.unmodifiableList(segments);

        } catch (ReflectiveOperationException | ClassCastException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Extracts the endpoint BlockPos positions from a BezierConnection object.
     *
     * @param bezierConnection Create's BezierConnection object (or null)
     * @return BezierEndpoints with both positions, or null if extraction fails
     */
    @Nullable
    public static BezierEndpoints extractEndpoints(Object bezierConnection) {
        if (bezierConnection == null) {
            return null;
        }

        try {
            if (cachedBePositionsField == null) {
                cachedBePositionsField = bezierConnection.getClass().getField("bePositions");
            }
            Object bePositions = cachedBePositionsField.get(bezierConnection);

            java.lang.reflect.Method getFirst = CreateTrackUtil.getCoupleGetFirstMethod();
            java.lang.reflect.Method getSecond = CreateTrackUtil.getCoupleGetSecondMethod();
            if (getFirst == null || getSecond == null) {
                return null;
            }

            BlockPos first = (BlockPos) getFirst.invoke(bePositions);
            BlockPos second = (BlockPos) getSecond.invoke(bePositions);

            return new BezierEndpoints(first, second);

        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
