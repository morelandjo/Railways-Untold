package com.vodmordia.railwaysuntold.worldgen.planner.noise;

import com.vodmordia.railwaysuntold.worldgen.api.AvoidanceZone;
import com.vodmordia.railwaysuntold.worldgen.api.TrackAvoidanceApi;
import com.vodmordia.railwaysuntold.worldgen.survey.SurveyedLayoutProvider;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseStructureScanner.PredictedStructure;
import com.vodmordia.railwaysuntold.worldgen.terrain.NoiseTerrainSampler;
import com.vodmordia.railwaysuntold.worldgen.tracking.BoundarySegmentData.ConnectedSegment;
import com.vodmordia.railwaysuntold.worldgen.tracking.ConnectedBoundaryTracker;
import com.vodmordia.railwaysuntold.worldgen.village.PredictedVillageLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Handles structure and existing-track avoidance for coarse route planning.
 */
final class RouteObstacleAvoider {

    private static final int STRUCTURE_SCAN_BUFFER_CHUNKS = 3;
    private static final int STRUCTURE_AVOIDANCE_BUFFER = 16;
    private static final int STRUCTURE_BOUNDING_RADIUS = 48;
    private static final int EXISTING_TRACK_AVOIDANCE_WIDTH = 48;

    /**
     * Below this distance from the head's current position, other-head tracks
     * are skipped by lateral avoidance. Sibling branches that just spawned at
     * a shared junction place their first few blocks within ~5 blocks of the
     * parent's tip
     */
    private static final int SIBLING_JUNCTION_SKIP = 16;

    /**
     * Minimum cluster radius. Prevents a cluster containing a single short
     * segment from being treated as a near-point obstacle that the route can
     * graze.
     */
    private static final int MIN_CLUSTER_RADIUS = 16;

    /**
     * A group of track segments placed by a single other head, represented as
     * a 2D bounding rectangle. The planner detours by enough to clear the
     * entire rectangle in one go - per-segment detour tends to dump the
     * route inside another segment of the same cluster (a sibling branch
     * typically lays 5-10 segments within a 30-40 block box).
     */
    record ExistingTrackCluster(UUID headId, BlockPos boundingMin, BlockPos boundingMax) {
        BlockPos center() {
            return new BlockPos(
                    (boundingMin.getX() + boundingMax.getX()) / 2,
                    (boundingMin.getY() + boundingMax.getY()) / 2,
                    (boundingMin.getZ() + boundingMax.getZ()) / 2);
        }

        /** Radius large enough to clear the entire bounding rectangle from center. */
        int radius() {
            int halfX = (boundingMax.getX() - boundingMin.getX()) / 2;
            int halfZ = (boundingMax.getZ() - boundingMin.getZ()) / 2;
            return Math.max(MIN_CLUSTER_RADIUS, Math.max(halfX, halfZ));
        }

        /**
         * Unit vector along the cluster's dominant horizontal axis (the axis
         * with the larger span). Used for the parallel-angle filter so tracks
         * running roughly along our travel direction don't trigger detours.
         */
        double[] dominantAxis() {
            int spanX = boundingMax.getX() - boundingMin.getX();
            int spanZ = boundingMax.getZ() - boundingMin.getZ();
            if (Math.abs(spanX) >= Math.abs(spanZ)) {
                return new double[]{1.0, 0.0};
            }
            return new double[]{0.0, 1.0};
        }
    }

    /**
     * Scans for structures along the path on the server thread.
     * Must be called from the server thread (accesses chunk generator state and saved data).
     */
    
    static boolean segmentCrossesAvoidableStructure(BlockPos start, BlockPos target,
                                                    List<PredictedStructure> structures,
                                                    @Nullable ServerLevel level) {
        if (structures == null || structures.isEmpty()) return false;

        double dx = target.getX() - start.getX();
        double dz = target.getZ() - start.getZ();
        double pathLenSq = dx * dx + dz * dz;
        if (pathLenSq < 1.0) return false;

        for (PredictedStructure s : structures) {
            int[] box = footprintBox(s);
            if (box != null) {
                // Box-aware: a long-thin footprint is cleared tightly on its short side instead of by
                // the max-dimension circle, which over-avoids and can push the route into other terrain.
                if (corridorCrossesBox(box, start.getX(), start.getZ(), dx, dz, pathLenSq)) {
                    return true;
                }
                continue;
            }

            int cx = s.approximateCenter().getX();
            int cz = s.approximateCenter().getZ();
            int radius = getStructureRadius(s, level);

            double t = ((cx - start.getX()) * dx + (cz - start.getZ()) * dz) / pathLenSq;
            if (t <= 0.0 || t >= 1.0) continue;

            double projX = start.getX() + t * dx;
            double projZ = start.getZ() + t * dz;
            double perpX = cx - projX;
            double perpZ = cz - projZ;
            double perpDistSq = perpX * perpX + perpZ * perpZ;
            if (perpDistSq < (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the corridor passes alongside the AABB {@code box} (closest approach strictly between the
     * endpoints, mirroring the circle test), measured against the box's support extent perpendicular to
     * the path so the avoidance hugs the footprint's true short side rather than a bounding circle.
     */
    private static boolean corridorCrossesBox(int[] box, int startX, int startZ,
                                              double dx, double dz, double pathLenSq) {
        double cx = (box[0] + box[2]) / 2.0;
        double cz = (box[1] + box[3]) / 2.0;
        double t = ((cx - startX) * dx + (cz - startZ) * dz) / pathLenSq;
        if (t <= 0.0 || t >= 1.0) return false;

        double projX = startX + t * dx;
        double projZ = startZ + t * dz;
        double perpDist = Math.sqrt((cx - projX) * (cx - projX) + (cz - projZ) * (cz - projZ));

        double len = Math.sqrt(pathLenSq);
        double perpAxisX = -dz / len;
        double perpAxisZ = dx / len;
        double halfX = (box[2] - box[0]) / 2.0;
        double halfZ = (box[3] - box[1]) / 2.0;
        // Support extent of the AABB along the perpendicular axis.
        double perpHalf = halfX * Math.abs(perpAxisX) + halfZ * Math.abs(perpAxisZ);
        return perpDist < perpHalf;
    }

    /** Union AABB of a structure's footprint as {minX, minZ, maxX, maxZ}, or null when it has none. */
    @Nullable
    private static int[] footprintBox(PredictedStructure s) {
        List<BoundingBox> fp = s.footprint();
        if (fp == null || fp.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BoundingBox b : fp) {
            minX = Math.min(minX, b.minX());
            minZ = Math.min(minZ, b.minZ());
            maxX = Math.max(maxX, b.maxX());
            maxZ = Math.max(maxZ, b.maxZ());
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    static List<PredictedStructure> scanStructures(ServerLevel level, BlockPos start, BlockPos target,
                                                    @Nullable NoiseTerrainSampler sampler) {
        return scanStructures(level, start, target, sampler, true);
    }

    /**
     * @param includeAvoidanceZones whether to merge in the global {@link TrackAvoidanceApi} zones. The
     *   per-segment post-plan re-scan passes false: the zones are global (independent of the segment) and
     *   were already merged by the initial whole-route scan, so re-collecting them for every segment is
     *   pure waste - the caller dedupes by chunk, so they never produce a new conflict. The village
     *   search below stays per-segment because it is path-specific.
     */
    static List<PredictedStructure> scanStructures(ServerLevel level, BlockPos start, BlockPos target,
                                                    @Nullable NoiseTerrainSampler sampler, boolean includeAvoidanceZones) {
        List<PredictedStructure> allStructures = NoiseStructureScanner.scanAlongPath(
                level, start, target, STRUCTURE_SCAN_BUFFER_CHUNKS, sampler);
        // Don't exclude the target village - the route must go AROUND it to reach
        // the entry point on the correct edge, not through the middle.
        List<PredictedStructure> filtered = new ArrayList<>(allStructures);

        if (includeAvoidanceZones) {
            for (AvoidanceZone zone : TrackAvoidanceApi.collectZones(level)) {
                BlockPos minCorner = zone.minCorner();
                BlockPos maxCorner = zone.maxCorner();
                BlockPos center = new BlockPos(
                        (minCorner.getX() + maxCorner.getX()) / 2,
                        (minCorner.getY() + maxCorner.getY()) / 2,
                        (minCorner.getZ() + maxCorner.getZ()) / 2);
                ChunkPos centerChunk = new ChunkPos(center);
                int halfX = (maxCorner.getX() - minCorner.getX()) / 2;
                int halfZ = (maxCorner.getZ() - minCorner.getZ()) / 2;
                int radius = Math.max(halfX, halfZ);
                filtered.add(new PredictedStructure(
                        centerChunk, center, "avoidance_zone:" + radius, List.of(), false));
            }
        }

        // Add discovered villages from VillageLocator - the noise scanner can miss villages,
        // but VillageLocator tracks ones found during chunk loading. This ensures the route
        // doesn't go straight through a known village.
        int searchRadius = (int) Math.sqrt(
                (double)(target.getX() - start.getX()) * (target.getX() - start.getX()) +
                (double)(target.getZ() - start.getZ()) * (target.getZ() - start.getZ())) + 128;
        BlockPos midpoint = new BlockPos((start.getX() + target.getX()) / 2, start.getY(),
                (start.getZ() + target.getZ()) / 2);
        for (com.vodmordia.railwaysuntold.worldgen.village.VillageInfo village :
                com.vodmordia.railwaysuntold.worldgen.village.VillageLocator.findVillagesInRadius(level, midpoint, searchRadius)) {
            // Skip if already in the predicted list (avoid duplicates)
            boolean alreadyPresent = filtered.stream().anyMatch(s ->
                    s.approximateCenter().distManhattan(village.center) < 32);
            if (alreadyPresent) continue;

            filtered.add(new PredictedStructure(
                    new ChunkPos(village.center), village.center,
                    "minecraft:villages", List.of("minecraft:village"), true));
        }

        // Attach exact footprints to villages - surveyed ground truth if a survey exists, else the
        // structure.generate prediction - so box-aware avoidance hugs the real building rectangle
        // instead of a bounding circle. Non-villages and unresolvable villages keep the circle path.
        List<PredictedStructure> withFootprints = new ArrayList<>(filtered.size());
        for (PredictedStructure s : filtered) {
            if (s.isVillage() && s.footprint() == null) {
                PredictedVillageLayout layout = SurveyedLayoutProvider.resolveLayout(level, s.chunkPos());
                if (layout != null && !layout.pieceBounds().isEmpty()) {
                    withFootprints.add(s.withFootprint(layout.pieceBounds()));
                    continue;
                }
            }
            withFootprints.add(s);
        }
        return withFootprints;
    }

    /**
     * Builds a 2D waypoint path from start to target, inserting detour waypoints
     * around predicted structures and other-head track clusters. 
     */
    static List<BlockPos> buildPathWithAvoidance(
            NoiseTerrainSampler sampler, BlockPos start, BlockPos target,
            List<PredictedStructure> structures, @Nullable ServerLevel level,
            List<ExistingTrackCluster> existingTrackClusters) {

        List<BlockPos> path = new ArrayList<>();
        path.add(start);

        if (structures.isEmpty() && existingTrackClusters.isEmpty()) {
            path.add(target);
            return path;
        }

        double pathDx = target.getX() - start.getX();
        double pathDz = target.getZ() - start.getZ();
        double pathLen = Math.sqrt(pathDx * pathDx + pathDz * pathDz);
        if (pathLen < 1) {
            path.add(target);
            return path;
        }

        // Snap path direction to octagonal so detour waypoints align with
        // the precision compiler's cardinal/diagonal run system.
        double[] snapped = RouteGeometry.snapToOctagonal(pathDx / pathLen, pathDz / pathLen);
        double dirX = snapped[0];
        double dirZ = snapped[1];

        // Cluster structures whose avoidance zones overlap so adjacent obstacles
        // are detoured as one group. 
        List<StructureCluster> clusters = clusterStructures(structures, level);
        clusters.sort((a, b) -> {
            double projA = projectOntoPath(a.center(), start, dirX, dirZ);
            double projB = projectOntoPath(b.center(), start, dirX, dirZ);
            return Double.compare(projA, projB);
        });

        // Distance to the route end along the travel axis. A structure lying entirely beyond the target
        // (e.g. the destination village body, when the route ends at a runway start just short of it) must
        // not trigger a detour - the corridor stops before reaching it.
        double corridorLength = projectOntoPath(target, start, dirX, dirZ);

        BlockPos current = start;
        for (StructureCluster cluster : clusters) {
            List<BlockPos> detourPoints = createDetourAroundStructureCluster(
                    sampler, start, current, cluster, dirX, dirZ, corridorLength);
            for (BlockPos dp : detourPoints) {
                if (!dp.equals(current)) {
                    path.add(dp);
                }
            }
            current = path.get(path.size() - 1);
        }

        if (!existingTrackClusters.isEmpty()) {
            List<ExistingTrackCluster> sortedClusters = new ArrayList<>(existingTrackClusters);
            sortedClusters.sort((a, b) -> {
                double projA = projectOntoPath(a.center(), start, dirX, dirZ);
                double projB = projectOntoPath(b.center(), start, dirX, dirZ);
                return Double.compare(projA, projB);
            });

            for (ExistingTrackCluster cluster : sortedClusters) {
                List<BlockPos> detourPoints = createDetourAroundCluster(
                        sampler, start, current, cluster, dirX, dirZ);
                for (BlockPos dp : detourPoints) {
                    if (!dp.equals(current)) {
                        path.add(dp);
                    }
                }
                current = path.get(path.size() - 1);
            }
        }

        if (!path.get(path.size() - 1).equals(target)) {
            path.add(target);
        }

        return path;
    }


    /**
     * A group of predicted structures whose individual avoidance zones overlap.
     * Represented as an axis-aligned bounding rectangle expanded by the avoidance
     * buffer; the detour uses the combined center and radius so the path goes
     * around the whole group instead of zigzagging between each structure's
     * preferred side.
     */
    private record StructureCluster(int minX, int minZ, int maxX, int maxZ, String label,
                                    boolean footprintDerived) {
        BlockPos center() {
            return new BlockPos((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
        }

        int radius() {
            return Math.max((maxX - minX) / 2, (maxZ - minZ) / 2);
        }

        /**
         * Extent perpendicular to a unit travel direction. For a footprint-derived rectangle this is the
         * true reach toward the corridor, so a long-thin village laid along the path detours by its short
         * side instead of its long one. Falls back to {@link #radius()} for circle-derived squares.
         */
        int perpendicularRadius(double dirX, double dirZ) {
            if (!footprintDerived) {
                return radius();
            }
            double halfX = (maxX - minX) / 2.0;
            double halfZ = (maxZ - minZ) / 2.0;
            return (int) Math.round(halfX * Math.abs(dirZ) + halfZ * Math.abs(dirX));
        }
    }

    /**
     * Groups predicted structures whose avoidance boxes overlap. Each structure
     * starts as a square (center ± structureRadius); two squares that overlap
     * when expanded by STRUCTURE_AVOIDANCE_BUFFER on all sides are merged.
     */
    private static List<StructureCluster> clusterStructures(
            List<PredictedStructure> structures, @Nullable ServerLevel level) {
        List<int[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<Boolean> footprintDerived = new ArrayList<>();
        for (PredictedStructure s : structures) {
            int[] footprint = footprintBox(s);
            if (footprint != null) {
                // Cluster around the true rectangle when we have one, not a center±radius square.
                boxes.add(new int[]{footprint[0], footprint[1], footprint[2], footprint[3]});
                footprintDerived.add(true);
            } else {
                int cx = s.approximateCenter().getX();
                int cz = s.approximateCenter().getZ();
                int r = getStructureRadius(s, level);
                boxes.add(new int[]{cx - r, cz - r, cx + r, cz + r});
                footprintDerived.add(false);
            }
            labels.add(s.structureSetName());
        }

        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (boxesOverlap(boxes.get(i), boxes.get(j), STRUCTURE_AVOIDANCE_BUFFER)) {
                        int[] a = boxes.get(i);
                        int[] b = boxes.get(j);
                        a[0] = Math.min(a[0], b[0]);
                        a[1] = Math.min(a[1], b[1]);
                        a[2] = Math.max(a[2], b[2]);
                        a[3] = Math.max(a[3], b[3]);
                        labels.set(i, labels.get(i) + "," + labels.get(j));
                        footprintDerived.set(i, footprintDerived.get(i) || footprintDerived.get(j));
                        boxes.remove(j);
                        labels.remove(j);
                        footprintDerived.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }

        List<StructureCluster> result = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            int[] b = boxes.get(i);
            result.add(new StructureCluster(b[0], b[1], b[2], b[3], labels.get(i), footprintDerived.get(i)));
        }
        return result;
    }

    private static boolean boxesOverlap(int[] a, int[] b, int expand) {
        return a[0] - expand <= b[2] && a[2] + expand >= b[0]
                && a[1] - expand <= b[3] && a[3] + expand >= b[1];
    }

    private static List<BlockPos> createDetourAroundStructureCluster(
            NoiseTerrainSampler sampler, BlockPos start, BlockPos current,
            StructureCluster cluster, double dirX, double dirZ, double corridorLength) {

        BlockPos center = cluster.center();
        int centerX = center.getX();
        int centerZ = center.getZ();

        // Decisions (is it ahead? does it block the corridor?) are measured from the
        // corridor origin, not the running detour corner, so a prior detour doesn't skew them.
        double forwardProj = (centerX - start.getX()) * dirX + (centerZ - start.getZ()) * dirZ;
        if (forwardProj < 0) {
            return List.of();
        }

        // Skip a cluster that lies entirely beyond the route end along the travel axis: the corridor stops
        // at the target before reaching it, so it never blocks the path. Without this, routing to a runway
        // start just short of the destination village still detours around the village body and can place a
        // corner inside it.
        double nearEdgeProj = Math.min(
                Math.min((cluster.minX() - start.getX()) * dirX + (cluster.minZ() - start.getZ()) * dirZ,
                         (cluster.maxX() - start.getX()) * dirX + (cluster.minZ() - start.getZ()) * dirZ),
                Math.min((cluster.minX() - start.getX()) * dirX + (cluster.maxZ() - start.getZ()) * dirZ,
                         (cluster.maxX() - start.getX()) * dirX + (cluster.maxZ() - start.getZ()) * dirZ));
        if (nearEdgeProj > corridorLength) {
            return List.of();
        }

        // For footprint-derived clusters the avoidance reach is the extent toward the corridor, not the
        // longest axis - so a village laid along the path is cleared by its short side.
        int clusterRadius = cluster.perpendicularRadius(dirX, dirZ);
        int offset = clusterRadius + STRUCTURE_AVOIDANCE_BUFFER;

        double perpDistFromRoute = Math.abs(
                (centerX - start.getX()) * (-dirZ) + (centerZ - start.getZ()) * dirX);
        if (perpDistFromRoute > offset) {
            return List.of();
        }

        List<BlockPos> detour = DetourGeometryCalculator.createDetourWaypoints(sampler, start, current, centerX, centerZ, clusterRadius, offset, dirX, dirZ);

        return detour;
    }

    private static int getStructureRadius(PredictedStructure structure, @Nullable ServerLevel level) {
        int[] footprint = footprintBox(structure);
        if (footprint != null) {
            return Math.max((footprint[2] - footprint[0]) / 2, (footprint[3] - footprint[1]) / 2);
        }
        if (structure.isVillage() && level != null) {
            // Prefer surveyed ground truth (exact placed bounds); fall back to the structure.generate
            // prediction. Behavior is identical to predict() until a survey for this village exists.
            PredictedVillageLayout layout = SurveyedLayoutProvider.resolveLayout(level, structure.chunkPos());
            if (layout != null) {
                BoundingBox bounds = layout.totalBounds();
                int halfX = (bounds.maxX() - bounds.minX()) / 2;
                int halfZ = (bounds.maxZ() - bounds.minZ()) / 2;
                return Math.max(halfX, halfZ);
            }
        }
        // Externally-supplied avoidance zones encode their actual radius in the structure name
        if (structure.structureSetName().startsWith("avoidance_zone:")) {
            try {
                return Integer.parseInt(structure.structureSetName().substring("avoidance_zone:".length()));
            } catch (NumberFormatException e) {
                // Fall through to default
            }
        }
        return STRUCTURE_BOUNDING_RADIUS;
    }

    private static double projectOntoPath(BlockPos pos, BlockPos origin, double dirX, double dirZ) {
        return RouteGeometry.dot(pos.getX() - origin.getX(), pos.getZ() - origin.getZ(), dirX, dirZ);
    }

    /**
     * Collects existing track segments placed by other heads and groups them
     * by placing head into clusters. Each cluster is a bounding rectangle
     * covering all of that head's segments in the query area.
     */
    static List<ExistingTrackCluster> collectExistingTrackClusters(ServerLevel level, BlockPos start,
                                                      BlockPos target, UUID headId) {
        int buffer = EXISTING_TRACK_AVOIDANCE_WIDTH + CoarseRoutePlanner.SAMPLE_INTERVAL;
        BlockPos queryMin = new BlockPos(
                Math.min(start.getX(), target.getX()) - buffer,
                Math.min(start.getY(), target.getY()),
                Math.min(start.getZ(), target.getZ()) - buffer);
        BlockPos queryMax = new BlockPos(
                Math.max(start.getX(), target.getX()) + buffer,
                Math.max(start.getY(), target.getY()),
                Math.max(start.getZ(), target.getZ()) + buffer);

        Set<ChunkPos> affectedChunks = ConnectedBoundaryTracker.getChunksSpannedBySegment(queryMin, queryMax);

        // Accumulate per-head bounds: min/max X/Y/Z for every other head's
        // segments in the query area. Station track (null headId) and the
        // querying head's own track are filtered out - we only route around
        // tracks placed by sibling or unrelated heads.
        Map<UUID, int[]> headBounds = new LinkedHashMap<>();
        for (ChunkPos chunk : affectedChunks) {
            List<ConnectedSegment> segments = ConnectedBoundaryTracker.getSegmentsInChunk(level, chunk);
            for (ConnectedSegment seg : segments) {
                if (seg.headId == null || seg.headId.equals(headId)) continue;
                int[] b = headBounds.computeIfAbsent(seg.headId, k -> new int[]{
                        Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                        Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
                });
                b[0] = Math.min(b[0], Math.min(seg.start.getX(), seg.end.getX()));
                b[1] = Math.min(b[1], Math.min(seg.start.getY(), seg.end.getY()));
                b[2] = Math.min(b[2], Math.min(seg.start.getZ(), seg.end.getZ()));
                b[3] = Math.max(b[3], Math.max(seg.start.getX(), seg.end.getX()));
                b[4] = Math.max(b[4], Math.max(seg.start.getY(), seg.end.getY()));
                b[5] = Math.max(b[5], Math.max(seg.start.getZ(), seg.end.getZ()));
            }
        }

        List<ExistingTrackCluster> clusters = new ArrayList<>(headBounds.size());
        for (Map.Entry<UUID, int[]> entry : headBounds.entrySet()) {
            int[] b = entry.getValue();
            clusters.add(new ExistingTrackCluster(
                    entry.getKey(),
                    new BlockPos(b[0], b[1], b[2]),
                    new BlockPos(b[3], b[4], b[5])));
        }

        return clusters;
    }

    private static List<BlockPos> createDetourAroundCluster(
            NoiseTerrainSampler sampler, BlockPos start, BlockPos current,
            ExistingTrackCluster cluster, double dirX, double dirZ) {

        BlockPos center = cluster.center();

        // Decisions are measured from the corridor origin (the head tip), not the
        // running detour corner, so a prior detour doesn't skew them.
        double forwardProj = (center.getX() - start.getX()) * dirX + (center.getZ() - start.getZ()) * dirZ;
        if (forwardProj < 0) {
            return List.of();
        }

        // Skip clusters immediately next to the head tip - these are sibling branches
        // sharing a junction. Avoidance at that range would push the route out of the
        // junction entirely, defeating the branch.
        double distFromStart = Math.sqrt(
                (double)(center.getX() - start.getX()) * (center.getX() - start.getX()) +
                (double)(center.getZ() - start.getZ()) * (center.getZ() - start.getZ()));
        if (distFromStart < SIBLING_JUNCTION_SKIP) {
            return List.of();
        }

        int clusterRadius = cluster.radius();
        int offset = clusterRadius + STRUCTURE_AVOIDANCE_BUFFER;

        double perpDistFromRoute = Math.abs(
                (center.getX() - start.getX()) * (-dirZ) + (center.getZ() - start.getZ()) * dirX);
        if (perpDistFromRoute > offset) {
            return List.of();
        }

        // Parallel filter: dominantAxis() is always a cardinal unit vector and the travel
        // direction is octagonal-snapped, so the only parallel case is a cardinal heading
        // running along the cluster's own cardinal axis - a 45° diagonal heading is never
        // parallel to a cardinal cluster axis. Skip those: a track along our heading runs
        // alongside rather than across the path.
        double[] domAxis = cluster.dominantAxis();
        boolean clusterAlongX = domAxis[0] != 0.0;
        boolean travelAlongX = Math.abs(dirX) > Math.abs(dirZ);
        boolean travelAlongZ = Math.abs(dirZ) > Math.abs(dirX);
        if ((clusterAlongX && travelAlongX) || (!clusterAlongX && travelAlongZ)) {
            return List.of();
        }

        // Perpendicular filter: a cluster running ACROSS our heading is a crossing, not something to
        // detour around. Detouring it laterally just swings the route into another part of the same
        // track (the 'curve a lot then fail' storm). Skip the detour so the route runs straight through
        // and PlacedTrackConflictDetector decides over/under (when there's slope room) or it merges at
        // the crossing. Only diagonal/angled clusters fall through to a lateral detour.
        boolean clusterPerpToTravel = (clusterAlongX && travelAlongZ) || (!clusterAlongX && travelAlongX);
        if (clusterPerpToTravel) {
            return List.of();
        }

        List<BlockPos> detour = DetourGeometryCalculator.createDetourWaypoints(sampler, start, current,
                center.getX(), center.getZ(), clusterRadius, offset, dirX, dirZ);


        return detour;
    }

}
