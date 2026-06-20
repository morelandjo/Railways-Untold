package com.vodmordia.railwaysuntold.worldgen.head;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;

/**
 * Selects the initial chunk and track direction based on world seed.
 * The initial chunk is 1 chunk adjacent to the world spawn point and determines the main track line.
 */
public class InitialChunkSelector {

    private final long worldSeed;
    private final BlockPos worldSpawn;
    private ChunkPos initialChunk;
    private Direction initialDirection; // NS or EW
    private boolean initialized = false;

    public InitialChunkSelector(long worldSeed, BlockPos worldSpawn) {
        this.worldSeed = worldSeed;
        this.worldSpawn = worldSpawn;
    }

    /**
     * Initializes the initial chunk and direction based on world seed.
     */
    private void initialize() {
        if (initialized) {
            return;
        }

        RandomSource random = RandomSource.create(worldSeed);

        ChunkPos spawnChunk = new ChunkPos(worldSpawn);

        // Pick which axis to offset the initial chunk from spawn - 50/50 chance
        // Track direction is always perpendicular to the offset so the train
        // never extends toward the spawn point (protects the bonus chest).
        boolean offsetInZ = random.nextBoolean();
        boolean positiveDirection = random.nextBoolean();

        if (offsetInZ) {
            // Chunk is north/south of spawn, track runs EW (perpendicular)
            initialChunk = new ChunkPos(spawnChunk.x, spawnChunk.z + (positiveDirection ? 1 : -1));
            initialDirection = Direction.EAST;
        } else {
            // Chunk is east/west of spawn, track runs NS (perpendicular)
            initialChunk = new ChunkPos(spawnChunk.x + (positiveDirection ? 1 : -1), spawnChunk.z);
            initialDirection = Direction.NORTH;
        }

        initialized = true;
    }

    /**
     * Gets the initial chunk position.
     */
    public ChunkPos getInitialChunk() {
        initialize();
        return initialChunk;
    }

    /**
     * Gets the initial track direction (NORTH for NS tracks, EAST for EW tracks).
     */
    public Direction getInitialDirection() {
        initialize();
        return initialDirection;
    }
}
