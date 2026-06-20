package com.vodmordia.railwaysuntold.worldgen.terrain.harness;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A from-scratch chunk generator whose surface is a closed-form {@link TerrainAtlas}, so gametests
 * can exercise the planner and placement pipeline against controllable, non-flat terrain. It is
 * swapped into the gametest world's overworld dimension by GameTestTerrainMixin; it is never used in
 * normal play.
 *
 * The atlas reports the ground height and any standing water per column. Both halves of the seam are
 * served: the planner reads terrain through {@link #getBaseHeight} (the chunk generator, not placed
 * blocks), and the placement pipeline reads real stone and water that {@link #fillFromNoise} writes
 * into the chunk.
 */
public class TestTerrainChunkGenerator extends ChunkGenerator {

    /** Round-trippable codec keyed only on the biome source; the surface shape is the fixed atlas. */
    public static final MapCodec<TestTerrainChunkGenerator> CODEC = BiomeSource.CODEC
            .fieldOf("biome_source")
            .xmap(TestTerrainChunkGenerator::new, ChunkGenerator::getBiomeSource);

    private final TerrainAtlas atlas;

    public TestTerrainChunkGenerator(BiomeSource biomeSource) {
        // Wrap the closed-form base atlas so gametests can register per-region overlays (e.g. a
        // ReplayTerrainAtlas) at runtime. With no overlay registered this is the BandedTerrainAtlas.
        this(biomeSource, new OverlayTerrainAtlas(new BandedTerrainAtlas()));
    }

    public TestTerrainChunkGenerator(BiomeSource biomeSource, TerrainAtlas atlas) {
        super(biomeSource);
        this.atlas = atlas;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        // Heightmap convention: the Y of the first block above the column that the heightmap's type
        // does not retain = retained top + 1. The planner reads terrain through getBaseHeight, so it
        // sees plannerHeight (which a drift band can make under-report the real ground).
        int ground = atlas.plannerHeight(x, z);
        switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG:
                // Passes through fluid: stops at the solid bed.
                return ground + 1;
            default:
                // WORLD_SURFACE* / MOTION_BLOCKING* retain water, so they stop at the water surface.
                return (atlas.isWater(x, z) ? atlas.waterSurface(x, z) : ground) + 1;
        }
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        int ground = atlas.groundHeight(x, z);
        boolean water = atlas.isWater(x, z);
        int waterSurface = water ? atlas.waterSurface(x, z) : Integer.MIN_VALUE;
        TerrainAtlas.VoidBand voidBand = atlas.voidBandAt(x, z);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState waterState = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] column = new BlockState[height];
        for (int i = 0; i < height; i++) {
            int y = minY + i;
            if (y <= ground) {
                column[i] = (voidBand != null && voidBand.contains(y))
                        ? (voidBand.fluid() ? waterState : air)
                        : stone;
            } else if (water && y <= waterSurface) {
                column[i] = waterState;
            } else {
                column[i] = air;
            }
        }
        return new NoiseColumn(minY, column);
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

        int minY = chunk.getMinBuildHeight();
        int chunkMinX = chunk.getPos().getMinBlockX();
        int chunkMinZ = chunk.getPos().getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int worldX = chunkMinX + lx;
                int worldZ = chunkMinZ + lz;
                int ground = atlas.groundHeight(worldX, worldZ);
                for (int y = minY; y <= ground; y++) {
                    chunk.setBlockState(cursor.set(lx, y, lz), stone, false);
                    oceanFloor.update(lx, y, lz, stone);
                    worldSurface.update(lx, y, lz, stone);
                }
                if (atlas.isWater(worldX, worldZ)) {
                    int waterSurface = atlas.waterSurface(worldX, worldZ);
                    for (int y = ground + 1; y <= waterSurface; y++) {
                        chunk.setBlockState(cursor.set(lx, y, lz), water, false);
                        // OCEAN_FLOOR_WG passes through fluid, so its update is a no-op for water;
                        // WORLD_SURFACE_WG retains it and rises to the water surface.
                        oceanFloor.update(lx, y, lz, water);
                        worldSurface.update(lx, y, lz, water);
                    }
                }
                // Carve a sub-surface void into the already-placed stone column. The heightmaps keep
                // reporting the full surface: the void is below ground and the route planner, which
                // reads only the surface heightmap, never sees it.
                TerrainAtlas.VoidBand voidBand = atlas.voidBandAt(worldX, worldZ);
                if (voidBand != null) {
                    BlockState fill = voidBand.fluid() ? water : air;
                    int loY = Math.max(minY, voidBand.loY());
                    int hiY = Math.min(ground, voidBand.hiY());
                    for (int y = loY; y <= hiY; y++) {
                        chunk.setBlockState(cursor.set(lx, y, lz), fill, false);
                    }
                }
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public int getGenDepth() {
        return 384;
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public int getMinY() {
        return -64;
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
    }

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
        // No-op: skip biome features (ores, trees, grass, lakes) so the terrain stays pure stone and
        // water. The harness needs fully controllable ground, not decoration the tests must read past.
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
    }
}
