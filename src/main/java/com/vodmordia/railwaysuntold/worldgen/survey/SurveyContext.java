package com.vodmordia.railwaysuntold.worldgen.survey;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Set;

/**
 * Everything an extractor needs while it runs. Constructed by {@link SurveyManager} on the server
 * thread once every region chunk has reached the extractor's required ChunkStatus, so chunk reads
 * inside {@link SurveyExtractor#extract} are safe and non-blocking.
 */
public record SurveyContext(ServerLevel level, RegionKey region, Set<ChunkPos> chunks) {
}
