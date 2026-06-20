package com.vodmordia.railwaysuntold.worldgen.placement.placer;

import com.vodmordia.railwaysuntold.blocks.core.DeadEndBlock;
import com.vodmordia.railwaysuntold.blocks.core.StartBlock;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.util.block.BlockTypeUtil;
import com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.support.SupportConstants;
import com.vodmordia.railwaysuntold.worldgen.village.StationBlockProtection;
import com.vodmordia.railwaysuntold.worldgen.village.VillageTargetingSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Generates and places jigsaw structures for event placement.
 */
public class JigsawEventPlacer {

    private static final int MAX_ROTATION_ATTEMPTS = 16;
    private static final int PLACEMENT_FLAGS = 18; // UPDATE_CLIENTS (2) + UPDATE_KNOWN_SHAPE (16)

    /**
     * Result of jigsaw event placement.
     */
    public static class JigsawPlacementResult {
        public final boolean success;
        public final boolean isDeadEnd;
        public final boolean hasStart;
        @Nullable
        public final Direction startDirection;
        public final BlockPos trackStart;
        public final BlockPos trackEnd;
        public final Direction trackDirection;
        public final String failureReason;

        private JigsawPlacementResult(boolean success, boolean isDeadEnd, boolean hasStart,
                                       @Nullable Direction startDirection,
                                       BlockPos trackStart, BlockPos trackEnd,
                                       Direction trackDirection,
                                       String failureReason) {
            this.success = success;
            this.isDeadEnd = isDeadEnd;
            this.hasStart = hasStart;
            this.startDirection = startDirection;
            this.trackStart = trackStart;
            this.trackEnd = trackEnd;
            this.trackDirection = trackDirection;
            this.failureReason = failureReason;
        }

        public static JigsawPlacementResult success(BlockPos trackStart, BlockPos trackEnd,
                                                      Direction trackDirection) {
            return new JigsawPlacementResult(true, false, false, null,
                    trackStart, trackEnd, trackDirection, null);
        }

        public static JigsawPlacementResult successDeadEnd(BlockPos trackStart, BlockPos trackEnd,
                                                             Direction trackDirection) {
            return new JigsawPlacementResult(true, true, false, null,
                    trackStart, trackEnd, trackDirection, null);
        }

        public static JigsawPlacementResult successStart(BlockPos trackStart, BlockPos trackEnd,
                                                           Direction trackDirection,
                                                           Direction startDirection) {
            return new JigsawPlacementResult(true, false, true, startDirection,
                    trackStart, trackEnd, trackDirection, null);
        }

        public static JigsawPlacementResult failure(String reason) {
            return new JigsawPlacementResult(false, false, false, null,
                    BlockPos.ZERO, BlockPos.ZERO, Direction.NORTH, reason);
        }
    }

    /**
     * Generates a jigsaw structure and places it into the world.
     * Retries with different seeds (up to 4 attempts) to find a rotation where
     * the track axis matches the expansion direction.
     */
    public static JigsawPlacementResult generateAndPlace(ServerLevel level, ResourceLocation structureId,
                                                          BlockPos anchor, Direction expandDir) {
        var structureRegistry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Structure structure = structureRegistry.get(structureId);
        if (structure == null) {
            return JigsawPlacementResult.failure("Structure not found in registry: " + structureId);
        }

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        BiomeSource biomeSource = chunkGenerator.getBiomeSource();
        RandomState randomState = level.getChunkSource().randomState();
        StructureTemplateManager templateManager = level.getStructureManager();
        long baseSeed = level.getSeed();
        ChunkPos chunkPos = new ChunkPos(anchor);

        Direction targetAxis = (expandDir == Direction.NORTH || expandDir == Direction.SOUTH)
                ? Direction.SOUTH : Direction.EAST;

        List<Direction> seenAxes = new ArrayList<>();
        for (int attempt = 0; attempt < MAX_ROTATION_ATTEMPTS; attempt++) {
            long seed = baseSeed ^ ((long) anchor.hashCode() * 31 + attempt * 7919L);

            StructureStart start = structure.generate(
                    level.registryAccess(), chunkGenerator, biomeSource, randomState,
                    templateManager, seed, chunkPos, 0, level,
                    biome -> true
            );

            if (start == null || !start.isValid()) {
                continue;
            }

            // Detect track axis from the start piece's orientation
            Direction detectedAxis = detectTrackAxisFromBoundingBox(start);

            if (detectedAxis == null) {
                continue;
            }
            seenAxes.add(detectedAxis);

            // Check if the detected axis matches our target
            boolean axisMatches = (detectedAxis == Direction.NORTH || detectedAxis == Direction.SOUTH)
                    == (targetAxis == Direction.NORTH || targetAxis == Direction.SOUTH);

            if (!axisMatches) {
                continue;
            }

            // Vanilla jigsaw placement drops the assembly at the chunk-derived
            // position and heightmap Y; the only track lives in the start piece,
            // so translate the whole assembly until that track meets the head.
            String alignFailure = alignTrackPieceToHead(level, start, anchor, expandDir);
            if (alignFailure != null) {
                return JigsawPlacementResult.failure(alignFailure);
            }

            return placePieces(level, start, anchor, expandDir);
        }

        return JigsawPlacementResult.failure("Could not find matching rotation after " + MAX_ROTATION_ATTEMPTS
                + " attempts (target axis " + targetAxis + ", saw " + seenAxes + ")");
    }

    /**
     * Translates the entire generated assembly so the start piece's rail meets the
     * head's connection point. The start piece is the only piece carrying track;
     * every other piece is jigsaw-attached to it and rides along with the move.
     *
     * Reads the rail position by probe-placing the start piece in situ, scanning
     * for the actual track, then restoring the probed blocks so only the final
     * (translated) placement lands. Returns null on success or a failure reason.
     */
    @Nullable
    private static String alignTrackPieceToHead(ServerLevel level, StructureStart start,
                                                BlockPos anchor, Direction expandDir) {
        StructurePiece startPiece = start.getPieces().get(0);
        BoundingBox bb = startPiece.getBoundingBox();

        for (int cx = bb.minX() >> 4; cx <= bb.maxX() >> 4; cx++) {
            for (int cz = bb.minZ() >> 4; cz <= bb.maxZ() >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return "Not all chunks loaded for start-piece alignment probe";
                }
            }
        }

        Map<BlockPos, BlockState> snapshot = new HashMap<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    snapshot.put(p, level.getBlockState(p));
                }
            }
        }

        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        RandomSource random = RandomSource.create(level.getSeed() ^ anchor.hashCode());
        ChunkPos pieceChunkPos = new ChunkPos(
                (bb.minX() + bb.maxX()) / 2 >> 4,
                (bb.minZ() + bb.maxZ()) / 2 >> 4
        );
        startPiece.postProcess(level, level.structureManager(), chunkGenerator, random, bb, pieceChunkPos, anchor);

        List<BlockPos> track = new ArrayList<>();
        int nonAirPlaced = 0;
        Set<String> placedBlocks = new HashSet<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    BlockState before = snapshot.get(p);
                    if (!s.isAir() && s != before) {
                        nonAirPlaced++;
                        placedBlocks.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
                    }
                    if (CreateTrackUtil.isTrackBlock(s)) {
                        track.add(p.immutable());
                    }
                }
            }
        }

        for (Map.Entry<BlockPos, BlockState> e : snapshot.entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), PLACEMENT_FLAGS);
        }

        if (track.isEmpty()) {
            String element = startPiece instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe
                    ? String.valueOf(pe.getElement()) : startPiece.getClass().getSimpleName();
            return "Start piece contains no track to align to the head (element=" + element
                    + " bb=[" + bb.minX() + "," + bb.minY() + "," + bb.minZ() + ".."
                    + bb.maxX() + "," + bb.maxY() + "," + bb.maxZ() + "] nonAirPlaced=" + nonAirPlaced
                    + " placed=" + placedBlocks + ")";
        }

        // Entry endpoint = the rail end the head reaches first (head-side along the travel axis).
        boolean alongX = expandDir.getAxis() == Direction.Axis.X;
        boolean positive = expandDir.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        BlockPos entry = track.get(0);
        for (BlockPos p : track) {
            int a = alongX ? p.getX() : p.getZ();
            int ea = alongX ? entry.getX() : entry.getZ();
            if (positive ? a < ea : a > ea) {
                entry = p;
            }
        }

        int dx = anchor.getX() - entry.getX();
        int dy = anchor.getY() - entry.getY();
        int dz = anchor.getZ() - entry.getZ();
        for (StructurePiece piece : start.getPieces()) {
            piece.move(dx, dy, dz);
        }

        return null;
    }

    /**
     * Detects the track axis by examining the start piece's template for track blocks.
     * Returns null if no track blocks are found.
     */
    @Nullable
    private static Direction detectTrackAxisFromBoundingBox(StructureStart start) {
        if (start.getPieces().isEmpty()) return null;
        StructurePiece startPiece = start.getPieces().get(0);
        BoundingBox bb = startPiece.getBoundingBox();

        int xLen = bb.maxX() - bb.minX() + 1;
        int zLen = bb.maxZ() - bb.minZ() + 1;

        // The track axis runs along the longer dimension of the start piece
        // This is a heuristic; data pack authors should design start pieces accordingly
        if (xLen > zLen) {
            return Direction.EAST;
        } else if (zLen > xLen) {
            return Direction.SOUTH;
        }

        // Square start piece - cannot determine axis from dimensions alone.
        // Default to SOUTH (N-S axis)
        return Direction.SOUTH;
    }

    /**
     * Places all structure pieces and scans for track endpoints.
     */
    private static JigsawPlacementResult placePieces(ServerLevel level, StructureStart start,
                                                      BlockPos anchor, Direction expandDir) {
        BoundingBox totalBB = start.getBoundingBox();

        // Verify all chunks are loaded
        int minCX = totalBB.minX() >> 4;
        int maxCX = totalBB.maxX() >> 4;
        int minCZ = totalBB.minZ() >> 4;
        int maxCZ = totalBB.maxZ() >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) {
                    return JigsawPlacementResult.failure("Not all chunks loaded for jigsaw structure (need " +
                            (maxCX - minCX + 1) + "x" + (maxCZ - minCZ + 1) + " chunks)");
                }
            }
        }

        // Place each piece using postProcess
        ChunkGenerator chunkGenerator = level.getChunkSource().getGenerator();
        RandomSource random = RandomSource.create(level.getSeed() ^ anchor.hashCode());
        // Use total bounding box so blocks aren't clipped to a single chunk
        BoundingBox placementBounds = totalBB;

        for (StructurePiece piece : start.getPieces()) {
            BoundingBox pieceBB = piece.getBoundingBox();
            ChunkPos pieceChunkPos = new ChunkPos(
                    (pieceBB.minX() + pieceBB.maxX()) / 2 >> 4,
                    (pieceBB.minZ() + pieceBB.maxZ()) / 2 >> 4
            );

            piece.postProcess(
                    level,
                    level.structureManager(),
                    chunkGenerator,
                    random,
                    placementBounds,
                    pieceChunkPos,
                    anchor
            );
        }


        // Scan for track endpoints on the exposed outer perimeter of the assembly.
        JigsawPlacementResult result = scanForTrackEndpoints(level, start, anchor, expandDir);

        if (result.success) {
            // Apply foundation fill and clearing
            RailwaysUntoldConfig config = RailwaysUntoldConfig.getDefault();
            placeFoundationForBoundingBox(level, totalBB);
            clearAboveBoundingBox(level, totalBB, config.STATION_CLEARING_VERTICAL_HEIGHT);
        }

        return result;
    }

    /**
     * Scans every piece's vertical faces for Create track blocks, DeadEndBlocks, and
     * StartBlocks, then picks the two markers farthest apart along the rail axis.
     */
    private record ScanMarkers(List<BlockPos> trackPositions,
                               List<BlockPos> deadEndPositions,
                               List<BlockPos> startPositions,
                               List<Direction> startDirections) {
        List<BlockPos> allMarkers() {
            List<BlockPos> all = new ArrayList<>(trackPositions);
            all.addAll(deadEndPositions);
            all.addAll(startPositions);
            return all;
        }
    }

    private static JigsawPlacementResult scanForTrackEndpoints(ServerLevel level, StructureStart start,
                                                                BlockPos anchor, Direction expandDir) {
        ScanMarkers markers = collectPieceFaceMarkers(level, start);
        List<BlockPos> allMarkers = markers.allMarkers();

        if (allMarkers.size() < 2) {
            return JigsawPlacementResult.failure("Found " + allMarkers.size() +
                    " track/marker blocks on piece faces, need at least 2 " +
                    "(tracks=" + markers.trackPositions().size() +
                    " deadEnds=" + markers.deadEndPositions().size() +
                    " starts=" + markers.startPositions().size() + ")");
        }

        // The axis-matching retry loop in generateAndPlace has already ensured
        // the start piece's bbox axis aligns with expandDir, so trust expandDir
        // for the rail axis..
        boolean trackDirIsEW = (expandDir == Direction.EAST || expandDir == Direction.WEST);
        Direction trackDir = trackDirIsEW ? Direction.EAST : Direction.SOUTH;

        if (!markers.deadEndPositions().isEmpty()) {
            BlockPos trackEnd = markers.deadEndPositions().get(0);
            BlockPos trackEntry = markers.trackPositions().isEmpty() ? allMarkers.get(0) : markers.trackPositions().get(0);
            return JigsawPlacementResult.successDeadEnd(trackEntry, trackEnd, trackDir);
        }

        if (!markers.startPositions().isEmpty()) {
            Direction startDir = markers.startDirections().isEmpty() ? Direction.NORTH : markers.startDirections().get(0);
            BlockPos trackEntry = markers.trackPositions().isEmpty() ? allMarkers.get(0) : markers.trackPositions().get(0);
            BlockPos startPoint = markers.startPositions().get(0);
            return JigsawPlacementResult.successStart(trackEntry, startPoint, trackDir, startDir);
        }

        return pickPassThroughEndpoints(allMarkers, anchor, trackDirIsEW, trackDir);
    }

    private static ScanMarkers collectPieceFaceMarkers(ServerLevel level, StructureStart start) {
        List<BlockPos> trackPositions = new ArrayList<>();
        List<BlockPos> deadEndPositions = new ArrayList<>();
        List<BlockPos> startPositions = new ArrayList<>();
        List<Direction> startDirections = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();

        for (StructurePiece p : start.getPieces()) {
            BoundingBox pbb = p.getBoundingBox();
            for (int y = pbb.minY(); y <= pbb.maxY(); y++) {
                for (int x = pbb.minX(); x <= pbb.maxX(); x++) {
                    for (int z = pbb.minZ(); z <= pbb.maxZ(); z++) {
                        boolean onPieceSurface = x == pbb.minX() || x == pbb.maxX()
                                || z == pbb.minZ() || z == pbb.maxZ();
                        if (!onPieceSurface) continue;

                        BlockPos pos = new BlockPos(x, y, z);
                        if (!seen.add(pos)) continue;

                        scanBlock(level, pos, trackPositions, deadEndPositions, startPositions, startDirections);
                    }
                }
            }
        }

        return new ScanMarkers(trackPositions, deadEndPositions, startPositions, startDirections);
    }

    private static JigsawPlacementResult pickPassThroughEndpoints(List<BlockPos> allMarkers, BlockPos anchor,
                                                                   boolean trackDirIsEW, Direction trackDir) {
        int perpExpected = trackDirIsEW ? anchor.getZ() : anchor.getX();
        List<BlockPos> aligned = new ArrayList<>();
        for (BlockPos p : allMarkers) {
            int perp = trackDirIsEW ? p.getZ() : p.getX();
            if (Math.abs(perp - perpExpected) <= 1) {
                aligned.add(p);
            }
        }

        if (aligned.size() < 2) {
            return JigsawPlacementResult.failure("Found " + allMarkers.size() +
                    " markers but fewer than 2 sit on head's rail row (perp="
                    + perpExpected + ", axis=" + (trackDirIsEW ? "X" : "Z") + ")");
        }

        BlockPos endpoint1 = aligned.get(0);
        BlockPos endpoint2 = aligned.get(0);
        for (BlockPos p : aligned) {
            int coord = trackDirIsEW ? p.getX() : p.getZ();
            int minCoord = trackDirIsEW ? endpoint1.getX() : endpoint1.getZ();
            int maxCoord = trackDirIsEW ? endpoint2.getX() : endpoint2.getZ();
            if (coord < minCoord) endpoint1 = p;
            if (coord > maxCoord) endpoint2 = p;
        }

        return JigsawPlacementResult.success(endpoint1, endpoint2, trackDir);
    }

    private static void scanBlock(ServerLevel level, BlockPos pos,
                                    List<BlockPos> trackPositions,
                                    List<BlockPos> deadEndPositions,
                                    List<BlockPos> startPositions,
                                    List<Direction> startDirections) {
        BlockState state = level.getBlockState(pos);
        if (CreateTrackUtil.isTrackBlock(state)) {
            trackPositions.add(pos.immutable());
        } else if (state.getBlock() instanceof DeadEndBlock) {
            deadEndPositions.add(pos.immutable());
        } else if (state.getBlock() instanceof StartBlock) {
            startPositions.add(pos.immutable());
            startDirections.add(state.getValue(HorizontalDirectionalBlock.FACING));
        }
    }

    /**
     * Fills foundation blocks below the jigsaw structure.
     */
    private static void placeFoundationForBoundingBox(ServerLevel level, BoundingBox bb) {
        BlockPos center = new BlockPos((bb.minX() + bb.maxX()) / 2, bb.minY(), (bb.minZ() + bb.maxZ()) / 2);
        BlockState foundationBlock = SupportConstants.getStationFoundationBlock(level, center);
        if (foundationBlock == null) return;

        StationBlockProtection protection = VillageTargetingSavedData.get(level).getStationBlockProtection();
        int maxDepth = 64;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                for (int depth = 1; depth <= maxDepth; depth++) {
                    int y = bb.minY() - depth;
                    if (y < level.getMinBuildHeight()) break;

                    mutable.set(x, y, z);
                    BlockState existing = level.getBlockState(mutable);

                    if (!existing.isAir() && !existing.canBeReplaced()
                            && !BlockTypeUtil.isTreeOrWood(existing)) {
                        break;
                    }

                    level.setBlock(mutable.immutable(), foundationBlock, PLACEMENT_FLAGS);
                    protection.protect(mutable.immutable());
                }
            }
        }
    }

    /**
     * Clears blocks above the jigsaw structure.
     */
    private static void clearAboveBoundingBox(ServerLevel level, BoundingBox bb, int clearHeight) {
        int topY = bb.maxY() + 1;
        for (int y = topY; y < topY + clearHeight; y++) {
            for (int x = bb.minX(); x <= bb.maxX(); x++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACEMENT_FLAGS);
                    }
                }
            }
        }
    }
}
