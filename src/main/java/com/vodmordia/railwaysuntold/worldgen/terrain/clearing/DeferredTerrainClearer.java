package com.vodmordia.railwaysuntold.worldgen.terrain.clearing;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.LightingUpdateUtil;
import com.vodmordia.railwaysuntold.util.chunk.ChunkCoordinateUtil;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierEndpoints;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.BezierSegmentData;
import com.vodmordia.railwaysuntold.worldgen.integration.deferred.DeferredProcessingManager;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.ClearingRequest;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.SegmentData;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.ClearingTypes.TunnelStatusMaps;
import com.vodmordia.railwaysuntold.worldgen.terrain.clearing.util.RemovalUtil.ClearingContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Defers terrain clearing across multiple ticks to avoid lag spikes.
 */
@EventBusSubscriber
public class DeferredTerrainClearer extends DeferredProcessingManager {

    private static final int PROCESS_INTERVAL_TICKS = 1;
    private static final int MAX_SEGMENTS_PER_TICK = 30;
    /** Time budget per tick in nanoseconds (8ms - leaves headroom in a 50ms tick). */
    private static final long TIME_BUDGET_NS = 8_000_000L;

    private static final DeferredTerrainClearer INSTANCE = new DeferredTerrainClearer();

    private static final List<ClearingJob> activeJobs = Collections.synchronizedList(new ArrayList<>());

    @Override
    protected int getProcessIntervalTicks() {
        return PROCESS_INTERVAL_TICKS;
    }

    /**
     * Queues bezier terrain clearing for deferred multi-tick processing.
     *
     * @param bezierConnection Create's BezierConnection object (reflection-accessed)
     * @param onComplete       Callback with underground segment count when job finishes
     */
    public static void queueBezierClearing(
            ServerLevel level,
            Object bezierConnection,
            ClearingRequest request,
            IntConsumer onComplete) {

        if (bezierConnection == null) {
            if (onComplete != null) {
                onComplete.accept(0);
            }
            return;
        }

        List<BezierSegmentData> extractedSegments =
                com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.extractSegments(bezierConnection);
        BezierEndpoints endpoints =
                com.vodmordia.railwaysuntold.worldgen.integration.create.util.BezierSegmentExtractor.extractEndpoints(bezierConnection);

        if (extractedSegments == null || extractedSegments.isEmpty()) {
            if (onComplete != null) {
                onComplete.accept(0);
            }
            return;
        }

        BezierClearingJob job = new BezierClearingJob(
                level, extractedSegments, endpoints, request, onComplete);
        activeJobs.add(job);
    }

    /**
     * @param onComplete Callback with underground segment count when job finishes
     */
    public static void queueStraightClearing(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            Direction direction,
            IntConsumer onComplete) {
        queueStraightClearing(level, start, end, direction, 0, onComplete);
    }

    public static void queueStraightClearing(
            ServerLevel level,
            BlockPos start,
            BlockPos end,
            Direction direction,
            int torchOffset,
            IntConsumer onComplete) {

        StraightClearingJob job = new StraightClearingJob(level, start, end, direction, torchOffset, onComplete);
        activeJobs.add(job);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeJobs.isEmpty()) {
            return;
        }

        long currentTick = event.getServer().overworld().getGameTime();
        if (!INSTANCE.shouldProcess(currentTick)) {
            return;
        }

        processJobs(event.getServer().overworld());
    }

    private static void processJobs(ServerLevel currentLevel) {
        int segmentsRemaining = MAX_SEGMENTS_PER_TICK;
        long deadlineNanos = System.nanoTime() + TIME_BUDGET_NS;
        List<ClearingJob> completedJobs = new ArrayList<>();
        List<ClearingJob> staleJobs = new ArrayList<>();

        synchronized (activeJobs) {
            int jobCount = activeJobs.size();
            if (jobCount == 0) {
                return;
            }

            int perJobBudget = Math.max(1, segmentsRemaining / jobCount);

            for (ClearingJob job : activeJobs) {
                if (segmentsRemaining <= 0 || System.nanoTime() > deadlineNanos) {
                    break;
                }

                if (INSTANCE.isStaleLevel(job.getLevel(), currentLevel)) {
                    staleJobs.add(job);
                    continue;
                }

                int budget = Math.min(perJobBudget, segmentsRemaining);
                int processed = job.processSegments(budget, deadlineNanos);
                segmentsRemaining -= processed;

                if (job.isComplete()) {
                    completedJobs.add(job);
                }
            }

            activeJobs.removeAll(staleJobs);
        }

        for (ClearingJob job : completedJobs) {
            job.finalize_();
            activeJobs.remove(job);
        }
    }

    /** Called during world unload. */
    public static void clearAll() {
        activeJobs.clear();
        INSTANCE.resetProcessTick();
    }

    private interface ClearingJob {
        ServerLevel getLevel();
        int processSegments(int maxSegments, long deadlineNanos);
        boolean isComplete();
        void finalize_();
    }

    private static class BezierClearingJob implements ClearingJob {

        private enum Phase { ORIGINAL_SEGMENTS, DENSIFIED_SEGMENTS, COMPLETE }

        private final ServerLevel level;
        private final ClearingRequest request;
        private final IntConsumer onComplete;

        private final List<SegmentData> originalSegments;
        private Phase phase = Phase.ORIGINAL_SEGMENTS;
        private int segmentIndex = 0;

        // State accumulated across ticks
        private final ClearingContext clearingContext;
        private final Set<BlockPos> processedSegmentCenters = new HashSet<>();
        // Underground status captured BEFORE gravity block clearing (accurate reading)
        private final Map<BlockPos, Boolean> preClearUndergroundStatus = new HashMap<>();

        // Densified phase
        private List<SegmentData> densifiedSegments;
        private int densifiedIndex = 0;

        BezierClearingJob(
                ServerLevel level,
                List<BezierSegmentData> extractedSegments,
                BezierEndpoints endpoints,
                ClearingRequest request,
                IntConsumer onComplete) {

            this.level = level;
            this.request = request;
            this.onComplete = onComplete;
            this.clearingContext = new ClearingContext(level);
            this.clearingContext.setProtectedVillageBounds(request.protectedVillageBounds);

            this.originalSegments = convertToSegmentData(extractedSegments, request.origin);
            addMissingEndpoints(this.originalSegments, endpoints);
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }

        @Override
        public int processSegments(int maxSegments, long deadlineNanos) {
            return switch (phase) {
                case ORIGINAL_SEGMENTS -> processOriginalPhase(maxSegments, deadlineNanos);
                case DENSIFIED_SEGMENTS -> processDensifiedPhase(maxSegments, deadlineNanos);
                case COMPLETE -> 0;
            };
        }

        private int processOriginalPhase(int maxSegments, long deadlineNanos) {
            int processed = 0;

            while (segmentIndex < originalSegments.size() && processed < maxSegments) {
                if (processed > 0 && System.nanoTime() > deadlineNanos) {
                    break;
                }

                SegmentData segment = originalSegments.get(segmentIndex);
                BlockPos centerPos = BlockPos.containing(segment.position);

                if (ClearingOperations.shouldSkipClearing(centerPos, request, level)) {
                    segmentIndex++;
                    continue;
                }

                // Pause job if chunk is unloaded - retry next tick instead of silently skipping
                if (ChunkCoordinateUtil.getLoadedChunk(level, centerPos) == null) {
                    break;
                }

                segmentIndex++;
                if (processedSegmentCenters.add(centerPos)) {
                    boolean isUnderground = ClearingOperations.clearSegmentWithUndergroundAwareness(level, segment.position, segment.normal, clearingContext);
                    preClearUndergroundStatus.put(centerPos, isUnderground);
                    processed++;
                }
            }

            if (segmentIndex >= originalSegments.size()) {
                transitionToDensifiedPhase();
            }

            return processed;
        }

        private void transitionToDensifiedPhase() {
            densifiedSegments = ClearingOperations.densifySegments(originalSegments);
            phase = Phase.DENSIFIED_SEGMENTS;
        }

        private int processDensifiedPhase(int maxSegments, long deadlineNanos) {
            int processed = 0;

            while (densifiedIndex < densifiedSegments.size() && processed < maxSegments) {
                if (processed > 0 && System.nanoTime() > deadlineNanos) {
                    break;
                }

                SegmentData segment = densifiedSegments.get(densifiedIndex);

                if (segment.isOriginal) {
                    densifiedIndex++;
                    continue;
                }

                BlockPos centerPos = BlockPos.containing(segment.position);

                if (ClearingOperations.shouldSkipClearing(centerPos, request, level) ||
                        processedSegmentCenters.contains(centerPos)) {
                    densifiedIndex++;
                    continue;
                }

                // Pause job if chunk is unloaded - retry next tick instead of silently skipping
                if (ChunkCoordinateUtil.getLoadedChunk(level, centerPos) == null) {
                    break;
                }

                densifiedIndex++;
                boolean isUnderground = ClearingOperations.clearSegmentWithUndergroundAwareness(level, segment.position, segment.normal, clearingContext);
                preClearUndergroundStatus.put(centerPos, isUnderground);
                processedSegmentCenters.add(centerPos);
                processed++;
            }

            if (densifiedIndex >= densifiedSegments.size()) {
                phase = Phase.COMPLETE;
            }

            return processed;
        }

        @Override
        public boolean isComplete() {
            return phase == Phase.COMPLETE;
        }

        @Override
        public void finalize_() {
            clearingContext.finish();

            // Use underground status captured BEFORE clearing - clearGravityBlocksAbove can
            // remove gravity blocks (sand/gravel) above the clearing zone
            Map<BlockPos, Boolean> undergroundStatus = preClearUndergroundStatus;
            Map<BlockPos, Boolean> underwaterStatus =
                    ClearingOperations.collectUnderwaterStatus(level, densifiedSegments);

            TunnelStatusMaps statusMaps = new TunnelStatusMaps(undergroundStatus, underwaterStatus);

            int undergroundCount = (int) undergroundStatus.values().stream()
                    .filter(Boolean::booleanValue).count();
            int underwaterCount = (int) underwaterStatus.values().stream()
                    .filter(Boolean::booleanValue).count();

            if (undergroundCount > 0 || underwaterCount > 0) {
                applyTunnelDecoration(level, densifiedSegments, statusMaps, request);
            }

            updateLightingFromClearedBounds(level, clearingContext);

            if (onComplete != null) {
                onComplete.accept(undergroundCount);
            }
        }

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

        private static void applyTunnelDecoration(
                ServerLevel level,
                List<SegmentData> segments,
                TunnelStatusMaps statusMaps,
                ClearingRequest request) {

            Set<BlockPos> decoratedPositions = new HashSet<>();
            int segmentIndex = request.torchOffset;

            for (SegmentData segment : segments) {
                BlockPos centerPos = BlockPos.containing(segment.position);

                // Skip decoration for segments inside the exclusion box (e.g. branch junctions
                // where a sibling segment already handles decoration)
                if (ClearingOperations.isInsideExclusionBox(centerPos, request.exclusionMin, request.exclusionMax)) {
                    continue;
                }

                Boolean isUnderground = statusMaps.underground().get(centerPos);
                Boolean isUnderwater = statusMaps.underwater().getOrDefault(centerPos, false);

                boolean shouldDecorate = (isUnderground != null && isUnderground) || isUnderwater;

                if (shouldDecorate && decoratedPositions.add(centerPos)) {
                    TunnelFinisher.decorateSegment(level, centerPos, segment.normal, segmentIndex,
                            request.config != null ? request.config : RailwaysUntoldConfig.getDefault());
                    segmentIndex++;
                }
            }
        }

        /**
         * Uses the actual bounding box of cleared positions (with margin for light propagation)
         */
        private static void updateLightingFromClearedBounds(ServerLevel level, ClearingContext ctx) {
            BlockPos clearedMin = ctx.getClearedBoundsMin();
            BlockPos clearedMax = ctx.getClearedBoundsMax();

            if (clearedMin == null) {
                // Nothing was cleared - skip lighting update entirely
                return;
            }

            // Small margin - checkBlock() triggers the light engine's own propagation,
            // so we only need to ensure positions at the boundary of cleared area are queued
            int margin = 2;
            BlockPos lightingMin = new BlockPos(
                    clearedMin.getX() - margin,
                    clearedMin.getY() - 1,
                    clearedMin.getZ() - margin
            );
            BlockPos lightingMax = new BlockPos(
                    clearedMax.getX() + margin,
                    clearedMax.getY() + margin,
                    clearedMax.getZ() + margin
            );

            LightingUpdateUtil.updateLightingForArea(level, lightingMin, lightingMax);
        }
    }

    private static class StraightClearingJob implements ClearingJob {

        private enum Phase { SEGMENTS, COMPLETE }

        private final ServerLevel level;
        private final BlockPos start;
        private final Direction direction;
        private final int distance;
        private final Vec3 normal;
        private final int torchOffset;
        private final IntConsumer onComplete;

        private Phase phase = Phase.SEGMENTS;
        private int positionIndex = 0;

        // State accumulated across ticks
        private final ClearingContext clearingContext;
        // Underground status captured BEFORE gravity block clearing (accurate reading)
        private final Map<BlockPos, Boolean> preClearUndergroundStatus = new HashMap<>();

        StraightClearingJob(
                ServerLevel level,
                BlockPos start,
                BlockPos end,
                Direction direction,
                int torchOffset,
                IntConsumer onComplete) {

            this.level = level;
            this.start = start;
            this.direction = direction;
            this.distance = DirectionUtil.calculateDirectionalDistance(start, end, direction);
            this.normal = getPerpendicularNormal(direction);
            this.torchOffset = torchOffset;
            this.onComplete = onComplete;
            this.clearingContext = new ClearingContext(level);
        }

        private static Vec3 getPerpendicularNormal(Direction direction) {
            return switch (direction) {
                case NORTH, SOUTH -> new Vec3(1, 0, 0);
                case EAST, WEST -> new Vec3(0, 0, 1);
                default -> new Vec3(1, 0, 0);
            };
        }

        @Override
        public ServerLevel getLevel() {
            return level;
        }

        @Override
        public int processSegments(int maxSegments, long deadlineNanos) {
            if (phase != Phase.SEGMENTS) {
                return 0;
            }

            int processed = 0;

            while (positionIndex <= distance && processed < maxSegments) {
                if (processed > 0 && System.nanoTime() > deadlineNanos) {
                    break;
                }

                BlockPos centerPos = start.relative(direction, positionIndex);

                // Pause job if chunk is unloaded - retry next tick instead of silently skipping
                if (ChunkCoordinateUtil.getLoadedChunk(level, centerPos) == null) {
                    break;
                }

                positionIndex++;
                boolean isUnderground = ClearingOperations.clearSegmentWithUndergroundAwareness(level, Vec3.atCenterOf(centerPos), normal, clearingContext);
                preClearUndergroundStatus.put(centerPos, isUnderground);
                processed++;
            }

            if (positionIndex > distance) {
                phase = Phase.COMPLETE;
            }

            return processed;
        }

        @Override
        public boolean isComplete() {
            return phase == Phase.COMPLETE;
        }

        @Override
        public void finalize_() {
            clearingContext.finish();

            // Use underground status captured BEFORE clearing - clearGravityBlocksAbove can
            // remove gravity blocks (sand/gravel) above the clearing zone
            Map<BlockPos, Boolean> undergroundStatus = preClearUndergroundStatus;
            Map<BlockPos, Boolean> underwaterStatus = ClearingOperations.collectUnderwaterStatus(level, start, direction, distance, normal);

            int undergroundCount = (int) undergroundStatus.values().stream()
                    .filter(Boolean::booleanValue).count();
            int underwaterCount = (int) underwaterStatus.values().stream()
                    .filter(Boolean::booleanValue).count();

            if (undergroundCount > 0 || underwaterCount > 0) {
                TunnelStatusMaps statusMaps = new TunnelStatusMaps(undergroundStatus, underwaterStatus);
                ClearingOperations.applyStraightLineTunnelDecoration(level, start, direction, distance, statusMaps, normal, torchOffset);
            }

            updateLightingFromClearedBounds(level, clearingContext);

            if (onComplete != null) {
                onComplete.accept(undergroundCount);
            }
        }

        private static void updateLightingFromClearedBounds(ServerLevel level, ClearingContext ctx) {
            BlockPos clearedMin = ctx.getClearedBoundsMin();
            BlockPos clearedMax = ctx.getClearedBoundsMax();

            if (clearedMin == null) {
                return;
            }

            int margin = 2;
            BlockPos lightingMin = new BlockPos(
                    clearedMin.getX() - margin,
                    clearedMin.getY() - 1,
                    clearedMin.getZ() - margin
            );
            BlockPos lightingMax = new BlockPos(
                    clearedMax.getX() + margin,
                    clearedMax.getY() + margin,
                    clearedMax.getZ() + margin
            );
            LightingUpdateUtil.updateLightingForArea(level, lightingMin, lightingMax);
        }
    }
}
