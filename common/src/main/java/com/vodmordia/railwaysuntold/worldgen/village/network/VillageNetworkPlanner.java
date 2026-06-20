package com.vodmordia.railwaysuntold.worldgen.village.network;

import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.worldgen.village.AttemptedVillageTracker;
import com.vodmordia.railwaysuntold.worldgen.village.VillageInfo;
import com.vodmordia.railwaysuntold.worldgen.village.VillagePredictor;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Computes a graph of candidate rail links over predicted villages near spawn,
 * stores the result in {@link VillageTargetingSavedData}, and exposes it to
 * head targeting code.
 *
 */
public final class VillageNetworkPlanner {

    private VillageNetworkPlanner() {}

    /**
     * Multiplier applied to {@link RailwaysUntoldConfig#VILLAGE_SEARCH_RADIUS}
     * to determine the world-space radius for the planner input.
     */
    private static final int SEARCH_RADIUS_MULTIPLIER = 2;

    /** Computes the edge list if it has not yet been computed for this level. */
    public static void computeIfStale(ServerLevel level) {
        VillageTargetingSavedData data = VillageTargetingSavedData.get(level);
        if (!data.getNetworkEdges().isEmpty()) return;
        recompute(level, data);
    }

    /**
     * Resolves an edge endpoint position back to a {@link VillageInfo}.
     * Predictions encode the structure-set name and spacing used to derive
     * the deterministic village UUID, so a raw position alone is insufficient
     * - we re-predict in a small radius and match on X/Z.
     */
    @Nullable
    public static VillageInfo resolveVillage(ServerLevel level, BlockPos candidate) {
        int searchRadius = 256;
        List<VillagePredictor.PredictedVillage> nearby =
                VillagePredictor.predictVillagesInRadius(level, candidate, searchRadius);
        for (VillagePredictor.PredictedVillage pred : nearby) {
            if (pred.approximateCenter.getX() == candidate.getX()
                    && pred.approximateCenter.getZ() == candidate.getZ()) {
                return VillagePredictor.createVillageInfoFromPrediction(pred);
            }
        }
        return null;
    }

    /**
     * Returns edges with at least one endpoint within {@code radiusBlocks} of
     * {@code origin} (2D distance, ignoring Y). The returned list is a fresh
     * copy and may be mutated freely.
     */
    public static List<StructureConnection> getEdgesFrom(ServerLevel level, BlockPos origin, int radiusBlocks) {
        List<StructureConnection> all = VillageTargetingSavedData.get(level).getNetworkEdges();
        if (all.isEmpty() || radiusBlocks <= 0) return List.of();
        long r2 = (long) radiusBlocks * (long) radiusBlocks;
        List<StructureConnection> out = new ArrayList<>();
        for (StructureConnection edge : all) {
            if (within2D(edge.from(), origin, r2) || within2D(edge.to(), origin, r2)) {
                out.add(edge);
            }
        }
        return out;
    }

    private static void recompute(ServerLevel level, VillageTargetingSavedData data) {
        RailwaysUntoldConfig config = new RailwaysUntoldConfig();
        int searchRadius = config.VILLAGE_SEARCH_RADIUS * SEARCH_RADIUS_MULTIPLIER;
        if (searchRadius <= 0) {
            data.setNetworkEdges(List.of());
            return;
        }

        BlockPos spawn = level.getSharedSpawnPos();
        List<VillagePredictor.PredictedVillage> predictions =
                VillagePredictor.predictVillagesInRadius(level, spawn, searchRadius);

        AttemptedVillageTracker attemptedTracker = data.getAttemptedTracker();
        Set<Long> seenPositions = new HashSet<>();
        List<BlockPos> positions = new ArrayList<>(predictions.size());
        for (VillagePredictor.PredictedVillage pred : predictions) {
            VillageInfo info = VillagePredictor.createVillageInfoFromPrediction(pred);
            UUID villageId = info.villageId;
            if (attemptedTracker.isVillageAttempted(villageId)) continue;
            BlockPos flat = new BlockPos(info.center.getX(), 0, info.center.getZ());
            long key = (((long) flat.getX()) << 32) ^ (flat.getZ() & 0xffffffffL);
            if (seenPositions.add(key)) positions.add(flat);
        }

        if (positions.size() < 2) {
            data.setNetworkEdges(List.of());
            return;
        }

        NetworkPlanner planner = new DelaunayPlanner();
        int maxEdgeLen = searchRadius;
        List<StructureConnection> edges = planner.plan(positions, maxEdgeLen);
        data.setNetworkEdges(edges);

    }

    private static boolean within2D(BlockPos a, BlockPos b, long r2) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz <= r2;
    }
}
