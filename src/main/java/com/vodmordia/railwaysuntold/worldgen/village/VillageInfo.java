package com.vodmordia.railwaysuntold.worldgen.village;

import com.vodmordia.railwaysuntold.worldgen.village.util.VillageConfig;
import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Information about a discovered or predicted village.
 */
public class VillageInfo {
    public final BlockPos center;
    public final UUID villageId;
    public final String villageType;

    public VillageInfo(BlockPos center, String villageType) {
        this(center, villageType, VillageConfig.DEFAULT_VILLAGE_SPACING, "village");
    }

    public VillageInfo(BlockPos center, String villageType, int spacing, String structureSetName) {
        this.center = center;
        this.villageType = villageType;
        this.villageId = generateVillageId(center, spacing, structureSetName);
    }

    private static UUID generateVillageId(BlockPos pos, int spacing, String structureSetName) {
        // Normalize position to structure region to ensure same physical structure gets same ID
        // regardless of whether discovered via prediction (chunk center) or locator (bounding box center).
        int regionBlockSize = spacing * 16;
        int regionX = Math.floorDiv(pos.getX(), regionBlockSize);
        int regionZ = Math.floorDiv(pos.getZ(), regionBlockSize);

        long mostSig = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
        long leastSig = structureSetName.hashCode();
        return new UUID(mostSig, leastSig);
    }
}
