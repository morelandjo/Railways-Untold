package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.*;

/**
 * Post-processing of an assembled {@link CarriageContraption}: populating Create's interactor /
 * control / conductor-seat state via reflection, fixing block-entity ids, and seeding the
 * contraption's updateTags. Reflection-field caches are idempotent.
 */
final class ContraptionPostProcessor {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile java.lang.reflect.Field cachedConductorSeatsField;
    private static volatile java.lang.reflect.Field cachedForwardControlsField;
    private static volatile java.lang.reflect.Field cachedBackwardControlsField;
    private static volatile java.lang.reflect.Field cachedUpdateTagsField;
    private static volatile java.lang.reflect.Field cachedInteractorsField;

    private ContraptionPostProcessor() {
    }

    @SuppressWarnings("unchecked")
    static void populateInteractorsAndControls(CarriageContraption contraption) {
        try {
            if (cachedInteractorsField == null) {
                cachedInteractorsField = com.simibubi.create.content.contraptions.Contraption.class
                        .getDeclaredField("interactors");
                cachedInteractorsField.setAccessible(true);
            }
            ensureConductorFieldsCached();

            Map<BlockPos, Object> interactors =
                    (Map<BlockPos, Object>) cachedInteractorsField.get(contraption);

            Direction assemblyDir = contraption.getAssemblyDirection();
            boolean hasForward = false;
            boolean hasBackward = false;

            Map<BlockPos, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> blocks =
                    contraption.getBlocks();

            Set<BlockPos> existingActorPositions = new HashSet<>();
            for (var pair : contraption.getActors()) {
                existingActorPositions.add(pair.left.pos());
            }

            for (var entry : blocks.entrySet()) {
                BlockPos pos = entry.getKey();
                net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo info =
                        entry.getValue();
                net.minecraft.world.level.block.state.BlockState state = info.state();

                com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour interactionBehaviour =
                        com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour.REGISTRY.get(state);
                if (interactionBehaviour != null) {
                    interactors.put(pos, interactionBehaviour);
                }

                if (state.getBlock() instanceof com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock) {
                    if (state.hasProperty(com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock.OPEN)
                            && !state.getValue(com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock.OPEN)) {
                        state = state.setValue(
                                com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock.OPEN, true);
                        info = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                                info.pos(), state, info.nbt());
                        blocks.put(pos, info);
                    }
                    Direction facing = state.getValue(
                            com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock.FACING);
                    if (assemblyDir != null && facing.getAxis() == assemblyDir.getAxis()) {
                        if (facing == assemblyDir) {
                            hasForward = true;
                        } else {
                            hasBackward = true;
                        }
                    }
                }

                if (!existingActorPositions.contains(pos)) {
                    com.simibubi.create.api.behaviour.movement.MovementBehaviour movementBehaviour =
                            com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(state);
                    if (movementBehaviour != null) {
                        contraption.getActors().add(org.apache.commons.lang3.tuple.MutablePair.of(info, null));
                        existingActorPositions.add(pos);
                    }
                }
            }

            cachedForwardControlsField.set(contraption, hasForward);
            cachedBackwardControlsField.set(contraption, hasBackward);

            LOGGER.debug("[CONTRAPTION] Populated {} interactors, {} actors, controls: forward={}, backward={}",
                    interactors.size(), contraption.getActors().size(), hasForward, hasBackward);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[CONTRAPTION] Failed to populate interactors/controls: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    static void recalculateConductorSeats(CarriageContraption contraption) {
        try {
            java.lang.reflect.Method coupleCreateMethod = com.vodmordia.railwaysuntold.worldgen.integration.create.util.CreateTrackUtil.getCoupleCreateMethod();
            if (coupleCreateMethod == null) {
                LOGGER.error("[CONTRAPTION] Couple.create method not cached");
                return;
            }

            ensureConductorFieldsCached();

            boolean hasForward = (boolean) cachedForwardControlsField.get(contraption);
            boolean hasBackward = (boolean) cachedBackwardControlsField.get(contraption);

            java.util.Map<BlockPos, Object> conductorSeats = (java.util.Map<BlockPos, Object>) cachedConductorSeatsField.get(contraption);
            conductorSeats.clear();

            List<BlockPos> seats = contraption.getSeats();

            for (BlockPos seatPos : seats) {
                Object couple = coupleCreateMethod.invoke(null, hasForward, hasBackward);
                conductorSeats.put(seatPos, couple);
            }

            cachedForwardControlsField.set(contraption, hasForward);
            cachedBackwardControlsField.set(contraption, hasBackward);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.error("[CONTRAPTION] Failed to recalculate conductor seats: {}", e.getMessage());
        }
    }

    private static void ensureConductorFieldsCached() throws ReflectiveOperationException {
        if (cachedConductorSeatsField == null) {
            cachedConductorSeatsField = CarriageContraption.class.getDeclaredField("conductorSeats");
            cachedConductorSeatsField.setAccessible(true);
            cachedForwardControlsField = CarriageContraption.class.getDeclaredField("forwardControls");
            cachedForwardControlsField.setAccessible(true);
            cachedBackwardControlsField = CarriageContraption.class.getDeclaredField("backwardControls");
            cachedBackwardControlsField.setAccessible(true);
        }
    }

    /**
     * Ensures all block entity blocks in the contraption have the correct {@code id} field
     * in their nbt.
     */
    static void ensureBlockEntityIds(CarriageContraption contraption) {
        Map<BlockPos, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo> blocks =
                contraption.getBlocks();

        for (var entry : new ArrayList<>(blocks.entrySet())) {
            net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo info = entry.getValue();
            CompoundTag nbt = info.nbt();
            if (nbt == null || nbt.isEmpty()) continue;

            BlockState state = info.state();
            if (!(state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock)) continue;

            try {
                net.minecraft.world.level.block.entity.BlockEntity temp = entityBlock.newBlockEntity(BlockPos.ZERO, state);
                if (temp == null) continue;
                ResourceLocation correctId = net.minecraft.world.level.block.entity.BlockEntityType.getKey(temp.getType());
                if (correctId == null) continue;

                String currentId = nbt.getString("id");
                if (!correctId.toString().equals(currentId)) {
                    CompoundTag fixedNbt = nbt.copy();
                    fixedNbt.putString("id", correctId.toString());
                    blocks.put(entry.getKey(), new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(
                            info.pos(), state, fixedNbt));
                }
            } catch (Exception e) {
                String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                LOGGER.warn("[TRAIN-BUILD] Failed to get BE type for {}: {}", blockName, e.getMessage(), e);
            }
        }
    }

    static void populateUpdateTags(CarriageContraption contraption) {
        try {
            Map<BlockPos, CompoundTag> updateTags = getContraptionUpdateTags(contraption);
            if (updateTags == null) return;

            for (var entry : contraption.getBlocks().entrySet()) {
                BlockPos pos = entry.getKey();
                CompoundTag nbt = entry.getValue().nbt();
                if (nbt != null && !updateTags.containsKey(pos)) {
                    updateTags.put(pos, CopycatContraptionHandler.stripMetadataKeys(nbt));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.warn("[TRAIN-BUILDER] Failed to populate updateTags: {}", e.getMessage());
        }
    }

    /**
     * Reflects into Create's Contraption to return the internal updateTags map.
     * Returns null if the field is inaccessible (logged once).
     */
    @SuppressWarnings("unchecked")
    static Map<BlockPos, CompoundTag> getContraptionUpdateTags(CarriageContraption contraption) {
        try {
            if (cachedUpdateTagsField == null) {
                cachedUpdateTagsField = com.simibubi.create.content.contraptions.Contraption.class
                        .getDeclaredField("updateTags");
                cachedUpdateTagsField.setAccessible(true);
            }
            return (Map<BlockPos, CompoundTag>) cachedUpdateTagsField.get(contraption);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("[TRAIN-BUILD] Could not access Contraption.updateTags: {}", e.getMessage());
            return null;
        }
    }
}
