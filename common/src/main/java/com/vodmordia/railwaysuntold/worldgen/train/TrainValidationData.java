package com.vodmordia.railwaysuntold.worldgen.train;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Data classes for train schematic validation.
 */
public class TrainValidationData {

    /**
     * Represents a bogey found in the contraption.
     */
    public static class BogeyInfo {
        public final BlockPos relativePos;      // Position relative to contraption anchor
        public final String bogeyType;          // Block ID (e.g., "create:small_bogey")
        public final CompoundTag bogeyData;     // BogeyData NBT (contains style info)
        public final boolean upsideDown;

        public BogeyInfo(BlockPos relativePos, String bogeyType, CompoundTag bogeyData, boolean upsideDown) {
            this.relativePos = relativePos;
            this.bogeyType = bogeyType;
            this.bogeyData = bogeyData;
            this.upsideDown = upsideDown;
        }

        @Override
        public String toString() {
            return String.format("Bogey[%s at %s, upsideDown=%b]", bogeyType, relativePos, upsideDown);
        }
    }

    /**
     * Per-carriage contraption data: NBT, control flags, and glue boxes for one carriage.
     */
    public record CarriageContraptionData(
            CompoundTag nbt,
            boolean hasForwardControls,
            boolean hasBackwardControls,
            List<AABB> glueBoxes
    ) {}

    /**
     * Groups forward/backward control flags.
     */
    public record TrainControls(boolean forward, boolean backward) {}

    public record BogeyLayout(List<BogeyInfo> bogeys, int spacing,
                               List<Integer> bogeysPerCarriage, List<Integer> interCarriageSpacings) {
        public int numCarriages() { return bogeysPerCarriage.size(); }
    }

    /**
     * Result of validating a train schematic.
     */
    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;

        public final Direction assemblyDirection;
        public final List<CarriageContraptionData> carriageContraptions;
        public final TrainControls controls;
        public final BogeyLayout bogeyLayout;

        // Train dimensions
        public final int trainHeight;

        // Raw schematic NBT for virtual world assembly
        public final CompoundTag schematicNbt;

        private ValidationResult(boolean valid, String errorMessage,
                                 Direction assemblyDirection,
                                 List<CarriageContraptionData> carriageContraptions,
                                 TrainControls controls,
                                 BogeyLayout bogeyLayout, int trainHeight,
                                 CompoundTag schematicNbt) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.assemblyDirection = assemblyDirection;
            this.carriageContraptions = carriageContraptions != null ? carriageContraptions : new ArrayList<>();
            this.controls = controls;
            this.bogeyLayout = bogeyLayout;
            this.trainHeight = trainHeight;
            this.schematicNbt = schematicNbt;
        }

        public static ValidationResult failure(String reason) {
            return new ValidationResult(false, reason, null, null, null, null, 0, null);
        }

        public static ValidationResult success(Direction assemblyDirection,
                                               List<CarriageContraptionData> carriageContraptions,
                                               TrainControls controls,
                                               BogeyLayout bogeyLayout, int trainHeight) {
            return new ValidationResult(true, null, assemblyDirection, carriageContraptions,
                    controls, bogeyLayout, trainHeight, null);
        }

        public static ValidationResult success(Direction assemblyDirection,
                                               List<CarriageContraptionData> carriageContraptions,
                                               TrainControls controls,
                                               BogeyLayout bogeyLayout, int trainHeight,
                                               CompoundTag schematicNbt) {
            return new ValidationResult(true, null, assemblyDirection, carriageContraptions,
                    controls, bogeyLayout, trainHeight, schematicNbt);
        }

        @Override
        public String toString() {
            if (!valid) {
                return "Invalid: " + errorMessage;
            }
            return String.format("Valid train: assemblyDir=%s, bogeys=%d, spacing=%d, height=%d, carriages=%d, fwdCtrl=%b, bkwdCtrl=%b",
                    assemblyDirection, bogeyLayout.bogeys().size(), bogeyLayout.spacing(),
                    trainHeight, bogeyLayout.numCarriages(), controls.forward(), controls.backward());
        }
    }
}
