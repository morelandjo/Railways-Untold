package com.vodmordia.railwaysuntold.datapack;

import com.vodmordia.railwaysuntold.worldgen.head.ExpansionHeadManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * Runtime context passed to {@link EventTrigger#test} for evaluating event prerequisites.
 * Kept separate from placement-layer classes so triggers remain decoupled.
 *
 * @param level          The server level where the event would be placed
 * @param headPosition   Current position of the expansion head
 * @param nearestPlayer  The closest online player to the head position, or null if none
 * @param headManager    The expansion head manager, or null if unavailable
 */
public record TriggerContext(
        ServerLevel level,
        BlockPos headPosition,
        @Nullable ServerPlayer nearestPlayer,
        @Nullable ExpansionHeadManager headManager
) {

    /**
     * Finds the nearest player to the given position and constructs a TriggerContext.
     */
    public static TriggerContext create(ServerLevel level, BlockPos headPosition,
                                        @Nullable ExpansionHeadManager headManager) {
        ServerPlayer nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            double distSq = player.blockPosition().distSqr(headPosition);
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return new TriggerContext(level, headPosition, nearest, headManager);
    }
}
