package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierEndpoints;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.ClearingRequest;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.ClearingResult;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.SegmentData;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.TunnelStatusMaps;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil.ClearingContext;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.*;

/**
 * Clears terrain along bezier track paths using segment data from BezierConnection.
 */
public class BezierPathClearer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** @param endpoints Optional endpoint positions to ensure both ends are cleared */
    public static ClearingResult clearAlongBezierWithSegments(
            ServerLevel level,
            List<BezierSegmentData> extractedSegments,
            ClearingRequest request,
            BezierEndpoints endpoints) {

        if (extractedSegments == null || extractedSegments.isEmpty()) {
            LOGGER.warn("[BEZIER-CLEAR] No segments to clear - extractedSegments is null or empty");
            return ClearingResult.failure();
        }

        List<SegmentData> segments = convertToSegmentData(extractedSegments, request.origin);
        addMissingEndpoints(segments, endpoints);

        BezierClearingState state = processOriginalSegments(level, segments, request);

        List<SegmentData> densifiedSegments = ClearingOperations.densifySegments(segments);
        processDensifiedSegments(level, densifiedSegments, request, state);

        state.clearingContext.finish();

        // Collect status AFTER clearing so small hills that get cleared away don't trigger facades
        TunnelStatusMaps statusMaps = new TunnelStatusMaps(
                ClearingOperations.collectUndergroundStatus(level, densifiedSegments),
                ClearingOperations.collectUnderwaterStatus(level, densifiedSegments)
        );

        return applyDecorationAndBuildResult(level, densifiedSegments, statusMaps, request);
    }

    private record BezierClearingState(
            ClearingContext clearingContext,
            Map<BlockPos, Boolean> undergroundStatus,
            List<BlockPos> deferredUndergroundCheck,
            Set<BlockPos> processedSegmentCenters) {}

    private static List<SegmentData> convertToSegmentData(List<BezierSegmentData> extractedSegments, BlockPos origin) {
        Vec3 originOffset = Vec3.atLowerCornerOf(origin).add(0, 3 / 16f, 0);
        List<SegmentData> segments = new ArrayList<>();

        for (BezierSegmentData extracted : extractedSegments) {
            Vec3 worldPos = extracted.position().add(originOffset);
            Vec3 normal = ClearingOperations.getValidNormalForClearing(extracted);
            segments.add(new SegmentData(worldPos, normal, true));
        }

        return segments;
    }

    private static void addMissingEndpoints(List<SegmentData> segments, BezierEndpoints endpoints) {
        if (endpoints == null) {
            return;
        }

        Set<BlockPos> existingBlockPositions = new HashSet<>();
        for (SegmentData seg : segments) {
            existingBlockPositions.add(BlockPos.containing(seg.position));
        }

        Vec3 firstEndpointNormal = segments.isEmpty() ? new Vec3(1, 0, 0) : segments.get(0).normal;
        Vec3 secondEndpointNormal = segments.isEmpty() ? new Vec3(1, 0, 0) : segments.get(segments.size() - 1).normal;

        if (!existingBlockPositions.contains(endpoints.first())) {
            Vec3 firstVec = Vec3.atCenterOf(endpoints.first());
            segments.add(0, new SegmentData(firstVec, firstEndpointNormal, true));
        }

        if (!existingBlockPositions.contains(endpoints.second())) {
            Vec3 secondVec = Vec3.atCenterOf(endpoints.second());
            segments.add(new SegmentData(secondVec, secondEndpointNormal, true));
        }
    }

    private static BezierClearingState processOriginalSegments(
            ServerLevel level,
            List<SegmentData> segments,
            ClearingRequest request) {

        ClearingContext clearingContext = new ClearingContext(level);
        clearingContext.setProtectedVillageBounds(request.protectedVillageBounds);
        Set<BlockPos> processedSegmentCenters = new HashSet<>();

        var stationTracker = VillageTargetingSavedData.get(level).getStationTracker();

        for (SegmentData segment : segments) {
            BlockPos centerPos = BlockPos.containing(segment.position);

            if (ClearingOperations.shouldSkipClearing(centerPos, request, level)) {
                continue;
            }

            if (processedSegmentCenters.add(centerPos)) {
                ClearingOperations.clearSegmentWithUndergroundAwareness(level, segment.position, segment.normal, clearingContext);
            }
        }

        return new BezierClearingState(clearingContext, new HashMap<>(), new ArrayList<>(), processedSegmentCenters);
    }

    private static void processDensifiedSegments(
            ServerLevel level,
            List<SegmentData> densifiedSegments,
            ClearingRequest request,
            BezierClearingState state) {

        var stationTracker = VillageTargetingSavedData.get(level).getStationTracker();

        for (SegmentData segment : densifiedSegments) {
            if (segment.isOriginal) {
                continue;
            }

            BlockPos centerPos = BlockPos.containing(segment.position);

            if (ClearingOperations.shouldSkipClearing(centerPos, request, level) ||
                    state.processedSegmentCenters.contains(centerPos)) {
                continue;
            }

            ClearingOperations.clearSegmentWithUndergroundAwareness(level, segment.position, segment.normal, state.clearingContext);
            state.processedSegmentCenters.add(centerPos);
        }
    }

    private static ClearingResult applyDecorationAndBuildResult(
            ServerLevel level,
            List<SegmentData> densifiedSegments,
            TunnelStatusMaps statusMaps,
            ClearingRequest request) {

        int undergroundCount = (int) statusMaps.underground().values().stream().filter(Boolean::booleanValue).count();
        int underwaterCount = (int) statusMaps.underwater().values().stream().filter(Boolean::booleanValue).count();

        int decoratedCount = 0;
        if (undergroundCount > 0 || underwaterCount > 0) {
            decoratedCount = applyTunnelDecorationSelective(
                    level, densifiedSegments, statusMaps, request.config, request.torchOffset);
        }

        return ClearingResult.success();
    }

    /**
     * Applies tunnel decoration selectively to underground or underwater segments.
     */
    private static int applyTunnelDecorationSelective(
            ServerLevel level,
            List<SegmentData> segments,
            TunnelStatusMaps statusMaps,
            RailwaysUntoldConfig config,
            int torchOffset) {

        Set<BlockPos> decoratedPositions = new HashSet<>();
        int segmentIndex = torchOffset;

        for (SegmentData segment : segments) {
            BlockPos centerPos = BlockPos.containing(segment.position);

            Boolean isUnderground = statusMaps.underground().get(centerPos);
            Boolean isUnderwater = statusMaps.underwater().getOrDefault(centerPos, false);

            boolean shouldDecorate = (isUnderground != null && isUnderground) || isUnderwater;

            if (shouldDecorate && decoratedPositions.add(centerPos)) {
                TunnelFinisher.decorateSegment(level, centerPos, segment.normal, segmentIndex, config);
                segmentIndex++;
            }
        }

        return decoratedPositions.size();
    }
}
