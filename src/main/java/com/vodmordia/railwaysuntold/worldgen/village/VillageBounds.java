package com.vodmordia.railwaysuntold.worldgen.village;

/**
 * Village boundary data in chunk coordinates.
 */
public class VillageBounds {
    public final int minChunkX;  // West edge (most negative X)
    public final int maxChunkX;  // East edge (most positive X)
    public final int minChunkZ;  // North edge (most negative Z)
    public final int maxChunkZ;  // South edge (most positive Z)

    public VillageBounds(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        this.minChunkX = minChunkX;
        this.maxChunkX = maxChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkZ = maxChunkZ;
    }

    /**
     * Get a formatted string for chat display.
     */
    public String toChatString() {
        int widthX = maxChunkX - minChunkX + 1;
        int widthZ = maxChunkZ - minChunkZ + 1;
        return String.format("N:%d S:%d E:%d W:%d | Size: %dx%d chunks",
                minChunkZ, maxChunkZ, maxChunkX, minChunkX, widthX, widthZ);
    }

    @Override
    public String toString() {
        int widthX = maxChunkX - minChunkX + 1;
        int widthZ = maxChunkZ - minChunkZ + 1;
        return String.format("VillageBounds[X:%d-%d, Z:%d-%d, size:%dx%d chunks]",
                minChunkX, maxChunkX, minChunkZ, maxChunkZ, widthX, widthZ);
    }
}
