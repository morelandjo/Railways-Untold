package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects when a station footprint would land on top of already-placed track. Track placement is
 * track-first, so a head that branches and then curves back onto its own pre-branch mainline can
 * arrive at a pose whose station footprint buries that line. The committer uses this as a hard
 * reject; the assigner uses it to demote approach sides that would bury track so a clear side is
 * preferred before any approach is built.
 *
 * The head's own approach connecting into the station terminates at the entry block, so segments
 * with a cell near the entry are exempt - only foreign track (or the head's mainline crossed
 * elsewhere under the footprint) counts as a burial.
 */
public final class StationTrackBurialCheck {

    /** Horizontal margin (blocks) added to the station footprint when scanning for buried track. */
    private static final int TRACK_FOOTPRINT_MARGIN = 1;

    /** Vertical half-band (blocks) around the platform within which placed track counts as buried. */
    private static final int TRACK_VERTICAL_BAND = 8;

    /**
     * Placed track whose cells come within this horizontal (Manhattan) distance of the entry is the
     * head's own approach connecting into the station, and so is exempt.
     */
    private static final int ENTRY_CONNECTOR_EXCLUSION = 6;

    private StationTrackBurialCheck() {
    }

    /**
     * Returns true if any placed track segment, other than the head's own approach entering at
     * {@code entryPoint}, runs through the station footprint. The footprint is the station's
     * track-plane bounds ({@code footprintMin}/{@code footprintMax}, with their Y at platform
     * level) expanded horizontally by {@link #TRACK_FOOTPRINT_MARGIN} and vertically by
     * {@link #TRACK_VERTICAL_BAND}.
     */
    public static boolean buriesExistingTrack(ServerLevel level, BlockPos footprintMin,
                                              BlockPos footprintMax, BlockPos entryPoint) {
        BlockPos min = new BlockPos(
                footprintMin.getX() - TRACK_FOOTPRINT_MARGIN,
                footprintMin.getY() - TRACK_VERTICAL_BAND,
                footprintMin.getZ() - TRACK_FOOTPRINT_MARGIN);
        BlockPos max = new BlockPos(
                footprintMax.getX() + TRACK_FOOTPRINT_MARGIN,
                footprintMax.getY() + TRACK_VERTICAL_BAND,
                footprintMax.getZ() + TRACK_FOOTPRINT_MARGIN);
        return ConnectedBoundaryTracker.hasSegmentIntersecting(
                level, min, max, seg -> segmentConnectsToEntry(seg, entryPoint));
    }

    /**
     * Reorders {@code plans} so approach sides whose predicted footprint would bury existing track
     * are tried last, mirroring how village-piece collisions are demoted. If every side buries
     * track (or none does) the original order is returned unchanged, leaving the commit-time
     * reject as the authoritative guard.
     */
    public static List<StationPlan> demoteBuryingPlans(ServerLevel level, List<StationPlan> plans,
                                                       SelectedStation station) {
        if (plans.size() <= 1) {
            return plans;
        }
        List<StationPlan> clear = new ArrayList<>(plans.size());
        List<StationPlan> burying = new ArrayList<>();
        for (StationPlan plan : plans) {
            if (planBuriesTrack(level, plan, station)) {
                burying.add(plan);
            } else {
                clear.add(plan);
            }
        }
        if (clear.isEmpty() || burying.isEmpty()) {
            return plans;
        }
        clear.addAll(burying);
        return clear;
    }

    /**
     * True if the station footprint for {@code plan}'s arrival pose would bury existing track. Uses
     * the entry-aligned footprint so the prediction matches what the committer actually places.
     */
    public static boolean planBuriesTrack(ServerLevel level, StationPlan plan, SelectedStation station) {
        StationPlacementGeometry.FootprintXZ footprint = StationPlacementGeometry.computeEntryAlignedFootprintXZ(
                station, plan.arrivalPos(), plan.arrivalDir(), 0);
        int y = plan.arrivalPos().getY();
        return buriesExistingTrack(level,
                new BlockPos(footprint.minX(), y, footprint.minZ()),
                new BlockPos(footprint.maxX(), y, footprint.maxZ()),
                plan.arrivalPos());
    }

    /** True if the segment has any cell within {@link #ENTRY_CONNECTOR_EXCLUSION} of the entry. */
    private static boolean segmentConnectsToEntry(ConnectedSegment seg, BlockPos entryPoint) {
        if (horizontalDistance(seg.start, entryPoint) <= ENTRY_CONNECTOR_EXCLUSION
                || horizontalDistance(seg.end, entryPoint) <= ENTRY_CONNECTOR_EXCLUSION) {
            return true;
        }
        if (seg.curvePositions != null) {
            for (BlockPos cell : seg.curvePositions) {
                if (horizontalDistance(cell, entryPoint) <= ENTRY_CONNECTOR_EXCLUSION) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int horizontalDistance(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
    }
}
