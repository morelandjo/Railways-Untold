package com.vodmordia.railwaysuntold.worldgen.terrain;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.StructureAvoidanceFilter;
import com.vodmordia.railwaysuntold.config.StructureUndergroundFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Predicts structure locations along a route using seed-deterministic placement checks.
 * Works without loaded chunks by querying StructurePlacement.isStructureChunk(),
 * which only needs the world seed and chunk coordinates.
 *
 */
public class NoiseStructureScanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Sentinel value for "no track Y known; skip Y-based filtering".
     */
    public static final int NO_TRACK_Y = Integer.MIN_VALUE;

    /**
     * A structure is treated as below the track (and therefore irrelevant to
     * surface avoidance) when the top of its bounding box is at least this
     * many blocks below the track's planned Y.
     */
    private static final int Y_BUFFER_BELOW_TRACK = 4;

    public record PredictedStructure(
            ChunkPos chunkPos,
            BlockPos approximateCenter,
            String structureSetName,
            List<String> possibleStructures,
            boolean isVillage,
            @Nullable List<BoundingBox> footprint
    ) {
        /** A structure avoided as a radius circle; a real footprint may be attached later for box-aware avoidance. */
        public PredictedStructure(ChunkPos chunkPos, BlockPos approximateCenter, String structureSetName,
                                  List<String> possibleStructures, boolean isVillage) {
            this(chunkPos, approximateCenter, structureSetName, possibleStructures, isVillage, null);
        }

        /** Same structure with its ground-truth/predicted piece boxes attached for box-aware avoidance. */
        public PredictedStructure withFootprint(@Nullable List<BoundingBox> footprint) {
            return new PredictedStructure(chunkPos, approximateCenter, structureSetName,
                    possibleStructures, isVillage, footprint);
        }
    }

    public enum VerifyResult {
        /** Vanilla confirms a structure from this set will generate (or did generate) at the chunk. */
        PRESENT,
        /** Vanilla confirms no structure from this set is or will be at the chunk. */
        ABSENT,
        /** Cache miss and caller opted out of forcing a chunk load. */
        UNKNOWN
    }

    /**
     * Scans for predicted structures along a path between two positions.
     * Checks every chunk the path passes through plus a buffer.
     *
     * @param level        Server level
     * @param from         Start position
     * @param to           End position
     * @param bufferChunks Number of chunks on each side of the path to check
     * @return List of predicted structures along the path
     */
    /** Mutable counter bundle so per-structureSet work can update scan stats without plumbing returns. */
    private static final class ScanStats {
        int verifiedPresent = 0;
        int verifiedAbsent = 0;
    }

    /** Per-scan constants + accumulators threaded through the extracted helpers. */
    private record ScanContext(
            ServerLevel level,
            ChunkGeneratorStructureState generatorState,
            BlockPos from, BlockPos to,
            double pathLengthSq,
            int trackY, int bufferChunks,
            @Nullable NoiseTerrainSampler sampler,
            List<PredictedStructure> predictions,
            ScanStats stats) {}

    public static List<PredictedStructure> scanAlongPath(
            ServerLevel level, BlockPos from, BlockPos to, int bufferChunks,
            @Nullable NoiseTerrainSampler sampler) {

        ChunkGeneratorStructureState generatorState = level.getChunkSource().getGeneratorState();
        List<Holder<StructureSet>> structureSets = generatorState.possibleStructureSets();

        if (structureSets.isEmpty()) {
            return Collections.emptyList();
        }

        // Track Y for the below-track filter: use the lower of the route's two
        // endpoints so structures whose top sits below the lowest point of the
        // planned span are filtered.
        int trackY = Math.min(from.getY(), to.getY());

        int minChunkX = (Math.min(from.getX(), to.getX()) >> 4) - bufferChunks;
        int maxChunkX = (Math.max(from.getX(), to.getX()) >> 4) + bufferChunks;
        int minChunkZ = (Math.min(from.getZ(), to.getZ()) >> 4) - bufferChunks;
        int maxChunkZ = (Math.max(from.getZ(), to.getZ()) >> 4) + bufferChunks;

        double pathDx = to.getX() - from.getX();
        double pathDz = to.getZ() - from.getZ();
        double pathLengthSq = pathDx * pathDx + pathDz * pathDz;

        ScanContext ctx = new ScanContext(
                level, generatorState, from, to,
                pathLengthSq,
                trackY, bufferChunks, sampler,
                new ArrayList<>(), new ScanStats());

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                int chunkCenterX = (cx << 4) + 8;
                int chunkCenterZ = (cz << 4) + 8;

                if (!isChunkNearPath(ctx, chunkCenterX, chunkCenterZ)) continue;

                for (Holder<StructureSet> setHolder : structureSets) {
                    tryPredictStructure(ctx, setHolder, cx, cz, chunkCenterX, chunkCenterZ);
                }
            }
        }

        logScanResults(ctx);
        return ctx.predictions();
    }

    private static boolean isChunkNearPath(ScanContext ctx, int chunkCenterX, int chunkCenterZ) {
        if (ctx.pathLengthSq() <= 0) return true;
        double distToPath = pointToLineDistanceSq(
                chunkCenterX, chunkCenterZ,
                ctx.from().getX(), ctx.from().getZ(),
                ctx.to().getX(), ctx.to().getZ());
        int maxDistBlocks = (ctx.bufferChunks() + 1) * 16;
        return distToPath <= (double) maxDistBlocks * maxDistBlocks;
    }

    private static void tryPredictStructure(ScanContext ctx, Holder<StructureSet> setHolder,
                                             int cx, int cz, int chunkCenterX, int chunkCenterZ) {
        StructureSet structureSet = setHolder.value();
        StructurePlacement placement = structureSet.placement();

        if (!placement.isStructureChunk(ctx.generatorState(), cx, cz)) return;

        // Skip structures that are entirely underground (BURY terrain adaptation)
        // e.g. trail ruins, ancient cities, strongholds - they won't conflict with surface tracks.
        if (isAllBuried(structureSet)) return;

        String setName = getStructureSetName(setHolder);
        // Skip structure sets known to generate underground or underwater (structureUndergroundList config).
        if (StructureUndergroundFilter.isUnderground(setName)) return;
        // Skip structure sets blacklisted in config (e.g. decorative tree structures from mods
        // like Expanded Ecosphere that use structures for individual trees).
        if (StructureAvoidanceFilter.isBlacklisted(setName)) return;

        ChunkPos chunkPos = new ChunkPos(cx, cz);
        VerifyResult verified = verifyStructurePresence(
                ctx.level(), chunkPos, structureSet, placement, ctx.trackY());

        switch (verified) {
            case ABSENT -> {
                ctx.stats().verifiedAbsent++;
                return;
            }
            case PRESENT -> {
                ctx.stats().verifiedPresent++;
            }
            case UNKNOWN -> {
                // Cache miss and we didn't force a load. Fall back to the naive seed-math
                // biome filter - same accuracy as the pre-verify scanner for chunks
                // beyond the near-horizon.
                if (!anyStructureBiomeMatches(structureSet, ctx.level(), chunkCenterX, chunkCenterZ)) return;
            }
        }

        List<String> possibleNames = getStructureNames(structureSet);
        boolean isVillage = setName.contains("village")
                || possibleNames.stream().anyMatch(n -> n.contains("village"));

        int approxY = ctx.sampler() != null
                ? ctx.sampler().getBaseHeight(chunkCenterX, chunkCenterZ) : 64;
        BlockPos approxCenter = new BlockPos(chunkCenterX, approxY, chunkCenterZ);
        ctx.predictions().add(new PredictedStructure(
                chunkPos, approxCenter, setName, possibleNames, isVillage));
    }

    private static void logScanResults(ScanContext ctx) {
        List<PredictedStructure> predictions = ctx.predictions();
        ScanStats stats = ctx.stats();
        if (predictions.isEmpty()) {
            LOGGER.debug("[STRUCTURE-SCAN] No structures predicted along path from {} to {} (verified-absent dropped {})",
                    ctx.from(), ctx.to(), stats.verifiedAbsent);
            return;
        }
    }

    /**
     * Tier 1 + Tier 2: ask vanilla's StructureCheck cache whether this chunk actually
     * hosts a structure from the set.
     *
     */
    public static VerifyResult verifyStructurePresence(
            ServerLevel level, ChunkPos chunkPos, StructureSet structureSet,
            StructurePlacement placement, int trackY) {

        boolean cacheHitPresent = false;
        boolean anyLoadNeeded = false;
        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            Structure structure = entry.structure().value();
            try {
                StructureCheckResult result = level.structureManager().checkStructurePresence(
                        chunkPos, structure, placement, false);
                if (result == StructureCheckResult.START_PRESENT) {
                    cacheHitPresent = true;
                    break;
                }
                if (result == StructureCheckResult.CHUNK_LOAD_NEEDED) {
                    anyLoadNeeded = true;
                }
                // START_NOT_PRESENT -> try the next entry in the set
            } catch (RuntimeException e) {
                // Structure check can throw on partially-initialised generator state;
                // degrade to UNKNOWN rather than dropping a legitimate candidate.
                anyLoadNeeded = true;
            }
        }

        // Cache says the structure is present here - load STRUCTURE_STARTS and
        // Y-check it. The cache only reports START_PRESENT for chunks already
        // generated to STRUCTURE_STARTS, so re-reading is mostly disk I/O (or a
        // cache hit if still resident).
        if (cacheHitPresent) {
            return loadAndCheckY(level, chunkPos, structureSet, trackY);
        }
        // CHUNK_LOAD_NEEDED: force the STRUCTURE_STARTS load and read the
        // structure's bounding box. STRUCTURE_STARTS is cheap and only the
        // sparse isStructureChunk candidates ever reach here, so every candidate
        // is verified rather than guessed by biome.
        if (anyLoadNeeded) {
            return loadAndCheckY(level, chunkPos, structureSet, trackY);
        }
        return VerifyResult.ABSENT;
    }

    /**
     * Forces a STRUCTURE_STARTS chunk load and, if a structure is present,
     * checks its bounding box against {@code trackY}. Structures whose
     * {@code maxY + Y_BUFFER_BELOW_TRACK < trackY} are treated as ABSENT
     * because they can't physically reach a surface track.
     */
    private static VerifyResult loadAndCheckY(ServerLevel level, ChunkPos chunkPos,
                                              StructureSet structureSet, int trackY) {
        try {
            ChunkAccess chunkAccess = level.getChunk(
                    chunkPos.x, chunkPos.z, ChunkStatus.STRUCTURE_STARTS);
            for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
                Structure structure = entry.structure().value();
                StructureStart start = level.structureManager().getStartForStructure(
                        SectionPos.bottomOf(chunkAccess), structure, chunkAccess);
                if (start != null && start.isValid()) {
                    if (trackY != NO_TRACK_Y) {
                        BoundingBox bb = start.getBoundingBox();
                        if (bb.maxY() + Y_BUFFER_BELOW_TRACK < trackY) {
                            // Structure is entirely below the track. Treat as
                            // absent for avoidance purposes - the track can pass
                            // over it without intersecting.
                            return VerifyResult.ABSENT;
                        }
                    }
                    return VerifyResult.PRESENT;
                }
            }
            return VerifyResult.ABSENT;
        } catch (RuntimeException e) {
            LOGGER.warn("[STRUCTURE-SCAN] STRUCTURE_STARTS load failed for chunk ({},{}): {}",
                    chunkPos.x, chunkPos.z, e.getMessage());
            return VerifyResult.UNKNOWN;
        }
    }

    /**
     * Returns true if at least one structure in the set has the biome at
     * (x, z) in its valid biome list. Uses noise biome sampling so it works
     * without loaded chunks.
     */
    private static boolean anyStructureBiomeMatches(
            StructureSet structureSet, ServerLevel level, int x, int z) {
        Holder<Biome> biomeAtPos = level.getNoiseBiome(x >> 2, 64 >> 2, z >> 2);
        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            if (entry.structure().value().biomes().contains(biomeAtPos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if ALL structures in the set use BURY terrain adaptation,
     * meaning they generate underground and won't conflict with surface tracks.
     */
    private static boolean isAllBuried(StructureSet structureSet) {
        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            Structure structure = entry.structure().value();
            if (structure.terrainAdaptation() != TerrainAdjustment.BURY) {
                return false;
            }
        }
        return true;
    }


    private static String getStructureSetName(Holder<StructureSet> holder) {
        return holder.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");
    }

    private static List<String> getStructureNames(StructureSet structureSet) {
        List<String> names = new ArrayList<>();

        for (StructureSet.StructureSelectionEntry entry : structureSet.structures()) {
            entry.structure().unwrapKey()
                    .map(key -> key.location().toString())
                    .ifPresent(names::add);
        }

        if (names.isEmpty()) {
            names.add("unknown");
        }
        return names;
    }

    private static double pointToLineDistanceSq(
            double px, double pz,
            double ax, double az, double bx, double bz) {
        double abx = bx - ax;
        double abz = bz - az;
        double apx = px - ax;
        double apz = pz - az;

        double abLenSq = abx * abx + abz * abz;
        if (abLenSq < 0.001) {
            return apx * apx + apz * apz;
        }

        double t = Math.max(0, Math.min(1, (apx * abx + apz * abz) / abLenSq));
        double projX = ax + t * abx;
        double projZ = az + t * abz;

        double dx = px - projX;
        double dz = pz - projZ;
        return dx * dx + dz * dz;
    }
}
