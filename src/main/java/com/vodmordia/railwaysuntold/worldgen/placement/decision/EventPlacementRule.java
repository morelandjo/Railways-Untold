package com.vodmordia.railwaysuntold.worldgen.placement.decision;

import com.mojang.logging.LogUtils;
import com.vodmordia.railwaysuntold.config.RailwaysUntoldConfig;
import com.vodmordia.railwaysuntold.datapack.EventDefinitionLoader;
import com.vodmordia.railwaysuntold.datapack.TriggerContext;
import com.vodmordia.railwaysuntold.worldgen.placement.PlacementDecision;
import com.vodmordia.railwaysuntold.worldgen.terrain.TerrainHeightUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Rule that handles placement of custom event schematics along tracks.
 */
public class EventPlacementRule implements PlacementRule {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int VILLAGE_PROXIMITY_THRESHOLD = 300;
    private static final int MIN_BLOCKS_SINCE_BRANCH = 50;
    private static final int GROUND_LEVEL_TOLERANCE = 5;

    private static int evaluationCount = 0;
    private static final int LOG_INTERVAL = 50;

    @Override
    public Optional<PlacementDecision> decide(DeciderContext context) {
        evaluationCount++;
        boolean shouldLog = evaluationCount % LOG_INTERVAL == 1;

        // Guard: events enabled and valid events loaded
        if (!RailwaysUntoldConfig.areCustomEventsEnabled()) {
            if (shouldLog) {
                LOGGER.debug("[EVENT-RULE] Skipped: custom events disabled in config");
            }
            return Optional.empty();
        }
        if (!EventDefinitionLoader.INSTANCE.hasValidEntries()) {
            if (shouldLog) {
                LOGGER.debug("[EVENT-RULE] Skipped: no valid event schematics loaded");
            }
            return Optional.empty();
        }

        int blocksSinceLastEvent = context.head().getBlocksSinceLastEvent();
        int minDistance = context.config().EVENT_SEPARATION_MIN_DISTANCE;

        // Guard: minimum distance since last event
        if (blocksSinceLastEvent < minDistance) {
            if (shouldLog) {
                LOGGER.debug("[EVENT-RULE] Skipped: distance since last event {} < min {}", blocksSinceLastEvent, minDistance);
            }
            return Optional.empty();
        }

        // Guard: not during village approach/exit
        if (context.head().getVillageState().hasStationApproachPath()) {
            LOGGER.debug("[EVENT-RULE] Skipped: station approach in progress");
            return Optional.empty();
        }

        // Guard: head must be on a cardinal straight. Events lay the structure's rail
        // on a cardinal axis and resume the head cardinally; firing mid-diagonal leaves
        // a broken, curve-less join at both ends.
        if (context.head().isDiagonal()) {
            LOGGER.debug("[EVENT-RULE] Skipped: head on a diagonal run (events require a cardinal straight)");
            return Optional.empty();
        }

        // Roll random chance
        int roll = context.random().nextInt(100);
        int eventChance = context.config().EVENT_CHANCE;
        if (roll >= eventChance) {
            LOGGER.debug("[EVENT-RULE] Skipped: random roll {} >= chance {} (distance was {})", roll, eventChance, blocksSinceLastEvent);
            return Optional.empty();
        }

        // Check no village target within proximity threshold
        if (context.head().getVillageState().hasTargetVillage()) {
            BlockPos villageCenter = context.head().getVillageState().getTargetVillageCenter();
            if (villageCenter != null) {
                int distToVillage = context.start().distManhattan(villageCenter);
                if (distToVillage < VILLAGE_PROXIMITY_THRESHOLD) {
                    LOGGER.debug("[EVENT-RULE] Skipped: too close to village target ({} < {})", distToVillage, VILLAGE_PROXIMITY_THRESHOLD);
                    return Optional.empty();
                }
            }
        }

        // Check no recent branch
        if (context.head().getBlocksSinceLastBranch() < MIN_BLOCKS_SINCE_BRANCH) {
            LOGGER.debug("[EVENT-RULE] Skipped: too close to last branch ({} < {})", context.head().getBlocksSinceLastBranch(), MIN_BLOCKS_SINCE_BRANCH);
            return Optional.empty();
        }

        // Pick a random event to check ground level for its footprint
        var biome = context.level().getBiome(context.start());
        TriggerContext triggerCtx = TriggerContext.create(context.level(), context.start(), context.headManager());
        EventDefinitionLoader.ValidatedEventEntry event = EventDefinitionLoader.INSTANCE.getWeightedRandomEvent(context.random(), biome, triggerCtx);
        if (event == null) {
            LOGGER.debug("[EVENT-RULE] Skipped: getWeightedRandomEvent returned null");
            return Optional.empty();
        }

        // Check ground level at start, mid, and end of footprint
        Direction dir = context.direction();
        int footprintLength = getFootprintLength(event, dir);
        int trackY = context.start().getY();

        BlockPos startPos = context.start();
        BlockPos midPos = startPos.relative(dir, footprintLength / 2);
        BlockPos endPos = startPos.relative(dir, footprintLength);

        if (!isGroundLevelAcceptable(context, startPos, trackY) ||
            !isGroundLevelAcceptable(context, midPos, trackY) ||
            !isGroundLevelAcceptable(context, endPos, trackY)) {
            LOGGER.debug("[EVENT-RULE] Skipped: ground level unacceptable for '{}' at {} (trackY={}, footprint={})",
                    event.definition().id(), context.start(), trackY, footprintLength);
            return Optional.empty();
        }

        return Optional.of(PlacementDecision.event(context.start(), context.direction()));
    }

    private int getFootprintLength(EventDefinitionLoader.ValidatedEventEntry event, Direction dir) {
        return switch (event) {
            case EventDefinitionLoader.ValidatedEventEntry.NbtEntry nbt -> {
                if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                    yield nbt.schematic().getLength();
                }
                yield nbt.schematic().getWidth();
            }
            case EventDefinitionLoader.ValidatedEventEntry.JigsawEntry jigsaw ->
                    jigsaw.definition().estimatedFootprint();
        };
    }

    private boolean isGroundLevelAcceptable(DeciderContext context, BlockPos pos, int trackY) {
        int groundLevel = TerrainHeightUtil.getRawGroundLevel(context.level(), pos.getX(), pos.getZ());
        return Math.abs(groundLevel - trackY) <= GROUND_LEVEL_TOLERANCE;
    }

    @Override
    public String getName() {
        return "EventPlacement";
    }

    @Override
    public int getPriority() {
        return 55;
    }
}
