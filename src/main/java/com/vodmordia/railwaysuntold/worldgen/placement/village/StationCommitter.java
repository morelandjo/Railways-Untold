package com.vodmordia.railwaysuntold.worldgen.placement.village;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.util.spatial.RotationHelper;
import com.vodmordia.railwaysuntold.worldgen.head.TrackExpansionHead;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import com.vodmordia.railwaysuntold.worldgen.village.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * The head advances naturally via its precision route
 * until that route is exhausted; at that moment the committer stamps the station so its
 * internal track-entry block sits exactly one block ahead of the head (in whatever
 * direction the final segment left the head heading). The track's last placed block and
 * the station's first track block meet by construction - no residual gap, no post-commit
 * replan, no region/tolerance math. The station follows the track.
 *
 */
public final class StationCommitter {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Padding blocks around the station footprint when verifying chunk load state. */
    private static final int CHUNK_CHECK_PADDING = 32;

    /** Safety margin (blocks) around station footprint vs village pieces. */
    private static final int STATION_COLLISION_MARGIN = 5;

    /** A commit only fires when the head is within this Manhattan distance of the plan's arrival. The
     *  route is planned TO the arrival, so a legit arrival lands within ~a segment; a far exhaust means
     *  the route truncated/under-compiled and we must NOT stamp a phantom station where it ran out. */
    private static final int MAX_COMMIT_ARRIVAL_DISTANCE = 100;

    private StationCommitter() {}

    /**
     * Result of a commit attempt.
     */
    public enum CommitStatus {
        /** Station successfully placed; head will advance through it on the next tick. */
        PLACED,
        /** Station footprint chunks not loaded yet; try again next tick. */
        DEFER,
        /** Arrival pose unusable (e.g. would bury existing track); orchestrator should re-target to
         *  another station plan and re-route before abandoning the village. */
        RETARGET,
        /** Permanent failure (missing plan, placement collision, invariant violation). Head halted. */
        FAILED
    }

    /**
     * Carries commit status plus, for {@link CommitStatus#DEFER}, the specific chunks the
     * orchestrator should wait on before retrying. When {@code deferChunks} is non-null and
     * non-empty the orchestrator routes through its chunk-wait machinery (issues tickets
     * for seen chunks, registers a wait-for-load) rather than spinning a continuation loop.
     */
    public record CommitResult(CommitStatus status, @Nullable Set<ChunkPos> deferChunks) {
        public static CommitResult placed() { return new CommitResult(CommitStatus.PLACED, null); }
        public static CommitResult failed() { return new CommitResult(CommitStatus.FAILED, null); }
        public static CommitResult retarget() { return new CommitResult(CommitStatus.RETARGET, null); }
        public static CommitResult deferForChunks(Set<ChunkPos> chunks) {
            return new CommitResult(CommitStatus.DEFER, chunks);
        }
    }

    /**
     * Returns true when the head has a locked station plan and its precision route is
     * exhausted. The route was planned from the head's start to {@code plan.entryPoint()};
     * when it finishes, the head is at the last planned block heading the direction the
     * last segment emitted. That (pos, dir) is where the station is stamped.
     *
     */
    public static boolean shouldTriggerCommit(TrackExpansionHead head) {
        if (head.getVillageState().isStationPlaced()) {
            return false;
        }
        StationPlan plan = head.getVillageState().getLockedStationPlan();
        if (plan == null) {
            return false;
        }
        // Don't commit far from the planned arrival. An under-compiled/truncated route can exhaust far
        // short of the village, which would otherwise stamp a phantom station near spawn. Require the
        // head to actually be near plan.arrivalPos; otherwise it replans and continues toward the village.
        BlockPos arrival = plan.arrivalPos();
        if (arrival != null && head.getPosition().distManhattan(arrival) > MAX_COMMIT_ARRIVAL_DISTANCE) {
            return false;
        }
        var precision = head.getTerrainPlanState().getPrecisionRoute();
        if (precision == null || !precision.isValid()) {
            return false;
        }
        if (head.isDiagonal() && precision.hasRemainingPath()) {
            return false;
        }
        if (!precision.hasRemainingPath()) {
            return true;
        }
        // Race with PathExecutor
        if (precision.getCurrentSegmentIndex() != precision.getTotalSegments() - 1) {
            return false;
        }
        var currentSegment = precision.getCurrentSegment();
        if (currentSegment == null) {
            return false;
        }
        BlockPos segmentEnd = currentSegment.getEndPosition();
        if (segmentEnd == null) {
            return false;
        }
        BlockPos headPos = head.getPosition();
        if (segmentEnd.equals(headPos)) {
            return true;
        }
        Direction headDir = head.getDirection();
        int forward = (segmentEnd.getX() - headPos.getX()) * headDir.getStepX()
                + (segmentEnd.getZ() - headPos.getZ()) * headDir.getStepZ();
        if (forward <= 0) {
            return true;
        }
        int horizDist = Math.abs(segmentEnd.getX() - headPos.getX())
                + Math.abs(segmentEnd.getZ() - headPos.getZ());
        return horizDist <= 1;
    }

    /**
     * Place the station aligned to the head's current pose. The station's internal track
     * entry block will land exactly one block ahead of the head in its direction of motion
     * (by construction via {@link SchematicPlacer#inverseEntryTransform}).
     */
    public static CommitResult tryCommit(ServerLevel level, TrackExpansionHead head) {
        StationPlan plan = head.getVillageState().getLockedStationPlan();
        SelectedStation selectedStation = head.getVillageState().getSelectedStation();
        if (plan == null || selectedStation == null || selectedStation.validation() == null) {
            LOGGER.warn("[STATION-COMMIT] Head {} at {}: missing locked plan or selected station, halting",
                    head.getHeadNumber(), head.getPosition());
            head.pause();
            return CommitResult.failed();
        }

        BlockPos headPos = head.getPosition();
        Direction headDir = head.getDirection();

        SchematicValidator.SchematicValidationResult validation = selectedStation.validation();
        NbtSchematicLoader.LoadedSchematic schematic = selectedStation.schematic();

        // Compute placement origin up-front so we can chunk-check the footprint before
        // committing. The same origin is re-derived inside placeStationAlignedToHead
        Rotation rotation = SchematicPlacer.calculateRotationForAlignment(
                validation.trackDirection, headDir);
        BlockPos desiredWorldTrackEntry = headPos.relative(headDir);
        BlockPos placementOrigin = SchematicPlacer.inverseEntryTransform(
                desiredWorldTrackEntry, headDir, schematic, validation, rotation);

        Vec3i rotatedSize = RotationHelper.getRotatedSize(schematic.getSize(), rotation);
        BlockPos footprintMin = placementOrigin.offset(-CHUNK_CHECK_PADDING, 0, -CHUNK_CHECK_PADDING);
        BlockPos footprintMax = placementOrigin.offset(
                rotatedSize.getX() - 1 + CHUNK_CHECK_PADDING, 0,
                rotatedSize.getZ() - 1 + CHUNK_CHECK_PADDING);
        Set<ChunkPos> unloadedFootprint = collectUnloadedFootprintChunks(level, footprintMin, footprintMax);
        if (!unloadedFootprint.isEmpty()) {
            // Hand the chunks back so the orchestrator can route through deferForChunks
            return CommitResult.deferForChunks(unloadedFootprint);
        }

        // Collision check moved from upstream NoiseStationPlanner: with track-first
        // placement the station origin isn't known until the head's actual arrival
        // pose is in. Reject the commit if the schematic would overlap a village
        // structure; the orchestrator abandons the village and the head replans.
        var layout = head.getVillageState().getPrecomputedLayout();
        if (layout != null && !layout.pieceBounds().isEmpty()
                && StationPlacementGeometry.wouldCollideWithVillagePieces(
                        headPos, headDir, selectedStation, layout.pieceBounds(),
                        STATION_COLLISION_MARGIN)) {
            LOGGER.warn("[STATION-COMMIT] Head {} commit FAILED: station footprint at origin {} would collide with village pieces - abandoning",
                    head.getHeadNumber(), placementOrigin);
            return CommitResult.failed();
        }

        // Existing-track collision check: with track-first placement the station origin isn't
        // known until the head's actual arrival pose is in, so a head that looped back toward its
        // village can land the station footprint on top of mainline track it placed earlier (e.g.
        // a head that branched, then curved around onto its own pre-branch line). Burying that
        // track silently destroys a working line. Reject the commit so the head abandons this
        // village and replans, exactly as for a village-piece collision. The head's own approach
        // track terminates at the entry block and is exempt.
        BlockPos footprintTrackMin = new BlockPos(
                placementOrigin.getX(), headPos.getY(), placementOrigin.getZ());
        BlockPos footprintTrackMax = new BlockPos(
                placementOrigin.getX() + rotatedSize.getX() - 1, headPos.getY(),
                placementOrigin.getZ() + rotatedSize.getZ() - 1);
        if (StationTrackBurialCheck.buriesExistingTrack(level, footprintTrackMin, footprintTrackMax, headPos)) {
            LOGGER.info("[STATION-COMMIT] Head {} arrival pose buries existing track at origin {} - re-targeting to another station plan",
                    head.getHeadNumber(), placementOrigin);
            return CommitResult.retarget();
        }

        VillageStationBuilder.StationPlacementResult placement =
                VillageStationBuilder.placeStationAlignedToHead(level, headPos, headDir, selectedStation);

        if (placement == null || !placement.success) {
            LOGGER.warn("[STATION-COMMIT] Head {} commit FAILED: track-first placement returned failure at origin {} - halting",
                    head.getHeadNumber(), placementOrigin);
            head.pause();
            return CommitResult.failed();
        }

        // duplicate-check so any divergence between the two code paths surfaces here.
        if (!placement.trackEntryPoint.equals(headPos)) {
            LOGGER.warn("[STATION-COMMIT] Head {} commit FAILED: entry invariant violated. head={} but placement entry={} - halting",
                    head.getHeadNumber(), headPos, placement.trackEntryPoint);
            head.pause();
            return CommitResult.failed();
        }

        // Record the placed station.
        SchematicPlacer.SchematicPlacementResult stationRecord =
                new SchematicPlacer.SchematicPlacementResult(
                        true, false, placement.stationPosition,
                        placement.trackEntryPoint, placement.trackExitPoint,
                        placement.trackDirection, null, null);
        head.getVillageState().setPlacedStation(stationRecord);


        // Mark the village STATION_PLACED so it can't be re-discovered and re-assigned.
        java.util.UUID villageId = head.getVillageState().getTargetVillageId();
        if (villageId != null) {
            VillageTargetingSavedData savedData = VillageTargetingSavedData.get(level);
            savedData.getAttemptedTracker().markVillageAttempted(
                    villageId, AttemptedVillageTracker.AttemptReason.STATION_PLACED);
            savedData.setDirty();
        }

        return CommitResult.placed();
    }

    private static Set<ChunkPos> collectUnloadedFootprintChunks(ServerLevel level, BlockPos min, BlockPos max) {
        int minChunkX = min.getX() >> 4;
        int maxChunkX = max.getX() >> 4;
        int minChunkZ = min.getZ() >> 4;
        int maxChunkZ = max.getZ() >> 4;
        Set<ChunkPos> unloaded = new HashSet<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (level.getChunkSource().getChunk(cx, cz, false) == null) {
                    unloaded.add(new ChunkPos(cx, cz));
                }
            }
        }
        return unloaded;
    }
}
