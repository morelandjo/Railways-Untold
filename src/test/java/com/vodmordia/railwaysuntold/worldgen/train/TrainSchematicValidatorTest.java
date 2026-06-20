package com.vodmordia.railwaysuntold.worldgen.train;

import com.vodmordia.railwaysuntold.worldgen.planner.noise.PlannerTestConfig;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterizes {@link TrainSchematicValidator}'s input-validation contract - the early rejections a
 * schematic must clear before any block parsing - and the public {@link
 * TrainSchematicValidator#loadAndValidateFlexible} entry's behavior on input it cannot parse. The full
 * happy-path parse (assembly direction, per-carriage bogey grouping, controls, height) needs a realistic
 * captured-train fixture and is better exercised by an in-world gametest against a real schematic; this
 * pins the failure surface, which is pure and fixture-light. (Bootstraps Minecraft because the deeper
 * block parsing resolves block ids via BuiltInRegistries.)
 */
class TrainSchematicValidatorTest {

    @BeforeAll
    static void setUp() {
        PlannerTestConfig.bootstrapDefaults();
    }

    @Test
    void aSchematicWithNoEntitiesListIsRejected() {
        ValidationResult result = TrainSchematicValidator.validateSchematicNbt(new CompoundTag());
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("no entities"), result.errorMessage);
    }

    @Test
    void entitiesWithoutACarriageContraptionAreRejected() {
        CompoundTag schematic = new CompoundTag();
        ListTag entities = new ListTag();
        CompoundTag pig = new CompoundTag();
        pig.putString("id", "minecraft:pig");
        entities.add(pig);
        schematic.put(ContraptionNbtKeys.ENTITIES, entities);

        ValidationResult result = TrainSchematicValidator.validateSchematicNbt(schematic);
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("carriage_contraption"), result.errorMessage);
    }

    @Test
    void flexibleEntryOnUnparseableNbtFailsAsNotCreateFormat() {
        // Not a Create entity schematic and not a raw structure template -> the catch-all failure.
        ValidationResult result = TrainSchematicValidator.loadAndValidateFlexible(new CompoundTag());
        assertFalse(result.valid);
        assertTrue(result.errorMessage.contains("Could not parse schematic as Create format"),
                result.errorMessage);
    }
}
