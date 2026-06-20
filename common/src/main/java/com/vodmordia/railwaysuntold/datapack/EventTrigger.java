package com.vodmordia.railwaysuntold.datapack;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A condition that must be met for an event to be eligible for placement.
 * Parsed from the {@code "triggers"} array in event JSON definitions.
 * Follows the same interface pattern as {@link BiomeFilter}.
 */
public interface EventTrigger {

    boolean test(TriggerContext ctx);

    /**
     * Returns true if all triggers pass, or the list is empty (no prerequisites).
     * Short-circuits on first failure.
     */
    static boolean allMatch(List<EventTrigger> triggers, TriggerContext ctx) {
        if (triggers.isEmpty()) return true;
        for (EventTrigger trigger : triggers) {
            if (!trigger.test(ctx)) return false;
        }
        return true;
    }

    /**
     * Parses a trigger from a JSON object with a {@code "type"} field.
     */
    static EventTrigger parse(JsonObject json) {
        String type = json.get("type").getAsString();
        switch (type) {
            case "advancement": return new AdvancementTrigger(new ResourceLocation(json.get("advancement").getAsString()));
            case "min_active_heads": return new MinActiveHeads(json.get("count").getAsInt());
            case "item_in_inventory": return new ItemInInventory(new ResourceLocation(json.get("item").getAsString()));
            case "game_time": return new GameTime(json.get("min_ticks").getAsLong());
            case "dimension": return new Dimension(new ResourceLocation(json.get("dimension").getAsString()));
            case "difficulty": return new Difficulty(json.get("min_level").getAsInt());
            case "moon_phase": return new MoonPhase(json.get("phase").getAsInt());
            case "weather": return new Weather(json.get("condition").getAsString());
            case "train_count": return new TrainCount(json.get("min_count").getAsInt());
            case "distance_from_spawn": return new DistanceFromSpawn(json.get("min_distance").getAsInt());
            default: throw new IllegalArgumentException("Unknown event trigger type: " + type);
        }
    }

    // ---- Player-specific triggers (use nearest player) ----

    record AdvancementTrigger(ResourceLocation advancementId) implements EventTrigger {
        private static final Logger LOGGER = LogUtils.getLogger();
        private static final Set<ResourceLocation> WARNED_MISSING = ConcurrentHashMap.newKeySet();

        @Override
        public boolean test(TriggerContext ctx) {
            ServerPlayer player = ctx.nearestPlayer();
            if (player == null) return false;

            MinecraftServer server = ctx.level().getServer();
            Advancement advancement = server.getAdvancements().getAdvancement(advancementId);
            if (advancement == null) {
                if (WARNED_MISSING.add(advancementId)) {
                    LOGGER.warn("[EVENT-TRIGGER] Advancement '{}' does not exist, trigger will always fail", advancementId);
                }
                return false;
            }

            PlayerAdvancements playerAdvancements = player.getAdvancements();
            AdvancementProgress progress = playerAdvancements.getOrStartProgress(advancement);
            return progress.isDone();
        }
    }

    record ItemInInventory(ResourceLocation itemId) implements EventTrigger {
        private static final Logger LOGGER = LogUtils.getLogger();
        private static final Set<ResourceLocation> WARNED_MISSING = ConcurrentHashMap.newKeySet();

        @Override
        public boolean test(TriggerContext ctx) {
            ServerPlayer player = ctx.nearestPlayer();
            if (player == null) return false;

            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item == Items.AIR) {
                if (WARNED_MISSING.add(itemId)) {
                    LOGGER.warn("[EVENT-TRIGGER] Item '{}' does not exist in registry, trigger will always fail", itemId);
                }
                return false;
            }

            return player.getInventory().contains(item.getDefaultInstance());
        }
    }

    // ---- World-state triggers ----

    record MinActiveHeads(int count) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            if (ctx.headManager() == null) return true;
            return ctx.headManager().getActiveHeadCount() >= count;
        }
    }

    record GameTime(long minTicks) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            return ctx.level().getGameTime() >= minTicks;
        }
    }

    record Dimension(ResourceLocation dimension) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            return ctx.level().dimension().location().equals(dimension);
        }
    }

    record Difficulty(int minLevel) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            return ctx.level().getDifficulty().getId() >= minLevel;
        }
    }

    record MoonPhase(int phase) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            return ctx.level().getMoonPhase() == phase;
        }
    }

    record Weather(String condition) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            switch (condition) {
                case "thunder": return ctx.level().isThundering();
                case "rain": return ctx.level().isRaining() && !ctx.level().isThundering();
                case "clear": return !ctx.level().isRaining();
                default: return false;
            }
        }
    }

    // ---- Create / Railways Untold triggers ----

    record TrainCount(int minCount) implements EventTrigger {
        private static final Logger LOGGER = LogUtils.getLogger();

        @Override
        public boolean test(TriggerContext ctx) {
            try {
                if (com.simibubi.create.Create.RAILWAYS == null ||
                    com.simibubi.create.Create.RAILWAYS.trains == null) {
                    return false;
                }
                return com.simibubi.create.Create.RAILWAYS.trains.size() >= minCount;
            } catch (Exception e) {
                LOGGER.warn("[EVENT-TRIGGER] TrainCount predicate failed (trigger will fail-closed): {}", e.getMessage(), e);
                return false;
            }
        }
    }

    record DistanceFromSpawn(int minDistance) implements EventTrigger {
        @Override
        public boolean test(TriggerContext ctx) {
            BlockPos spawn = ctx.level().getSharedSpawnPos();
            return ctx.headPosition().distManhattan(spawn) >= minDistance;
        }
    }
}
