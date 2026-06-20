package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.vodmordia.railwaysuntold.util.core.TrackLogger;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyInfo;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.BogeyLayout;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.CarriageContraptionData;
import com.vodmordia.railwaysuntold.worldgen.train.TrainValidationData.ValidationResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and extracts data from Create mod train schematics.
 */
public class TrainSchematicValidator {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String CARRIAGE_ENTITY_ID = "create:carriage_contraption";
    private static final String SUPER_GLUE_ENTITY_ID = "create:super_glue";

    /**
     * Loads and validates a train schematic from resources.
     *
     * @param resourceManager The resource manager
     * @param location        The resource location of the .nbt file
     * @return ValidationResult with extracted train data
     */
    public static ValidationResult loadAndValidate(ResourceManager resourceManager, ResourceLocation location) {
        try {
            Resource resource = resourceManager.getResource(location)
                    .orElse(null);
            if (resource == null) {
                return ValidationResult.failure("Schematic resource not found: " + location);
            }

            CompoundTag schematicNbt;
            try (InputStream stream = resource.open()) {
                schematicNbt = NbtIo.readCompressed(stream);
            }

            return validateSchematicNbt(schematicNbt);
        } catch (IOException | RuntimeException e) {
            TrackLogger.error("[TRAIN-VALIDATOR] Failed to load schematic: {}", e.getMessage(), e);
            return ValidationResult.failure("Failed to load schematic: " + e.getMessage());
        }
    }

    /**
     * Validates a Create schematic NBT tag containing one or more carriage_contraption entities.
     * Supports multi-carriage trains where each entity is a separate carriage.
     */
    public static ValidationResult validateSchematicNbt(CompoundTag schematicNbt) {
        if (!schematicNbt.contains(ContraptionNbtKeys.ENTITIES, Tag.TAG_LIST)) {
            return ValidationResult.failure("Schematic has no entities list");
        }

        ListTag entities = schematicNbt.getList(ContraptionNbtKeys.ENTITIES, Tag.TAG_COMPOUND);
        List<CarriageEntityData> carriageEntities = findAllCarriageContraptionEntities(entities);

        if (carriageEntities.isEmpty()) {
            return ValidationResult.failure("No carriage_contraption entity found in schematic");
        }

        // Sort by signed position so carriage[0] = nearest station.
        // Use the AD axis if entities have spread along it; otherwise fall back to
        // the spread axis (entities captured perpendicular to original AD).
        // Apply the AD's sign so negative-axis directions (WEST, NORTH) sort correctly.
        if (carriageEntities.size() > 1) {
            CompoundTag firstContraption = extractContraptionTag(carriageEntities.get(0).nbt());
            Direction sortDirection = firstContraption != null
                    ? extractAssemblyDirection(firstContraption) : Direction.NORTH;
            Direction.Axis adAxis = sortDirection.getAxis();
            List<Vec3> prelimPositions = new ArrayList<>();
            for (CarriageEntityData ced : carriageEntities) {
                prelimPositions.add(ced.entityPos());
            }
            double adSpread = CarriageGeometryCalculator.spreadOnAxis(prelimPositions, adAxis);
            Direction.Axis sortAxis = adSpread > 0.5 ? adAxis : CarriageGeometryCalculator.findSpreadAxis(prelimPositions);
            int sign = sortDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            carriageEntities.sort((a, b) -> {
                double posA = CarriageGeometryCalculator.componentAlongAxis(a.entityPos(), sortAxis) * sign;
                double posB = CarriageGeometryCalculator.componentAlongAxis(b.entityPos(), sortAxis) * sign;
                return Double.compare(posA, posB);
            });
        }

        // Extract glue boxes from the schematic
        List<AABB> allGlueBoxes = extractGlueEntities(entities);

        // Collect data across all carriage entities, preserving per-carriage bogey grouping
        List<BogeyInfo> allBogeys = new ArrayList<>();
        List<Integer> bogeysPerCarriage = new ArrayList<>();
        List<CarriageContraptionData> carriageContraptions = new ArrayList<>();
        Direction assemblyDirection = null;
        boolean hasAnyForward = false;
        boolean hasAnyBackward = false;
        int maxHeight = 0;

        for (int entityIdx = 0; entityIdx < carriageEntities.size(); entityIdx++) {
            CarriageEntityData entityData = carriageEntities.get(entityIdx);
            CompoundTag entityNbt = entityData.nbt();
            CompoundTag contraptionNbt = extractContraptionTag(entityNbt);
            if (contraptionNbt == null) {
                return ValidationResult.failure("Entity missing 'Contraption' tag");
            }

            // Recalculate seat positions from actual seat blocks in the BlockList.
            // Create saves the same Seats list for all per-bogey entities, but seat
            // positions are only correct relative to one bogey's anchor. Rebuilding
            // from blocks ensures they always match.
            recalculateSeatsFromBlocks(contraptionNbt);

            Direction entityDirection = extractAssemblyDirection(contraptionNbt);
            if (assemblyDirection == null) {
                assemblyDirection = entityDirection;
            }

            boolean hasForwardControls = contraptionNbt.getBoolean(ContraptionNbtKeys.FRONT_CONTROLS);
            boolean hasBackwardControls = contraptionNbt.getBoolean(ContraptionNbtKeys.BACK_CONTROLS);
            hasAnyForward |= hasForwardControls;
            hasAnyBackward |= hasBackwardControls;

            // extractBogeys already sorts within-carriage by axis position
            List<BogeyInfo> entityBogeys = extractBogeys(contraptionNbt, assemblyDirection);
            if (entityBogeys.isEmpty()) {
                return ValidationResult.failure("Carriage entity has no bogeys");
            }
            bogeysPerCarriage.add(entityBogeys.size());
            allBogeys.addAll(entityBogeys);

            int entityHeight = CarriageGeometryCalculator.trainHeight(contraptionNbt);
            maxHeight = Math.max(maxHeight, entityHeight);

            logContraptionPalette(contraptionNbt, entityIdx);

            carriageContraptions.add(new CarriageContraptionData(
                    contraptionNbt, hasForwardControls, hasBackwardControls, new ArrayList<>()));
        }

        if (!hasAnyForward && !hasAnyBackward) {
            return ValidationResult.failure("Train has no controls in any carriage");
        }

        // Bogeys are already grouped per carriage; don't globally sort

        int bogeySpacing = CarriageGeometryCalculator.bogeySpacing(allBogeys, assemblyDirection);

        // Entity positions in carriage order (nearest station first), used for inter-carriage
        // spacing and glue distribution.
        List<Vec3> entityPositions = new ArrayList<>();
        for (CarriageEntityData ced : carriageEntities) {
            entityPositions.add(ced.entityPos());
        }

        // Inter-carriage spacing: distance between nearest bogeys of adjacent carriages.
        List<Integer> interCarriageSpacings = CarriageGeometryCalculator.interCarriageSpacings(
                entityPositions, bogeysPerCarriage, allBogeys, assemblyDirection);

        // Distribute glue boxes to carriages, transforming from schematic-space to anchor-relative
        if (!allGlueBoxes.isEmpty() && carriageContraptions.size() > 1) {
            carriageContraptions = CarriageGeometryCalculator.distributeGlueToCarriages(carriageContraptions, allGlueBoxes,
                    allBogeys, bogeysPerCarriage, assemblyDirection, entityPositions);
        } else if (!allGlueBoxes.isEmpty()) {
            Vec3 entityPos = carriageEntities.get(0).entityPos();
            List<AABB> transformed = new ArrayList<>();
            for (AABB box : allGlueBoxes) {
                transformed.add(box.move(-entityPos.x, -entityPos.y, -entityPos.z));
            }
            CarriageContraptionData original = carriageContraptions.get(0);
            carriageContraptions.set(0, new CarriageContraptionData(
                    original.nbt(), original.hasForwardControls(), original.hasBackwardControls(), transformed));
        }

        return ValidationResult.success(
                assemblyDirection,
                carriageContraptions,
                new TrainValidationData.TrainControls(hasAnyForward, hasAnyBackward),
                new BogeyLayout(allBogeys, bogeySpacing, bogeysPerCarriage, interCarriageSpacings),
                maxHeight);
    }

    private static void logContraptionPalette(CompoundTag contraptionNbt, int carriageIndex) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)) {
            return;
        }
        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);

        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            String name = entry.getString(ContraptionNbtKeys.NAME);
            ResourceLocation blockId = ResourceLocation.tryParse(name);
            boolean exists = blockId != null
                    && BuiltInRegistries.BLOCK.get(blockId) != net.minecraft.world.level.block.Blocks.AIR;
            if (!exists && !"minecraft:air".equals(name)) {
                LOGGER.warn("[TRAIN-VALIDATOR]   [{}] {} -> MISSING (will be air)", i, name);
            }
        }
    }

    private record CarriageEntityData(CompoundTag nbt, Vec3 entityPos) {}

    /**
     * Finds all carriage_contraption entities in the entities list, with their positions.
     * Create saves exactly one entity per carriage (not per bogey), so each entity
     * is a distinct carriage - no deduplication needed.
     */
    private static List<CarriageEntityData> findAllCarriageContraptionEntities(ListTag entities) {
        List<CarriageEntityData> result = new ArrayList<>();

        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entity = entities.getCompound(i);
            CompoundTag nbt = entity.getCompound(ContraptionNbtKeys.NBT);
            String id = nbt.getString(ContraptionNbtKeys.ID);
            if (!CARRIAGE_ENTITY_ID.equals(id)) {
                continue;
            }

            Vec3 pos = extractEntityPosition(entity);
            if (pos == null) pos = Vec3.ZERO;

            result.add(new CarriageEntityData(nbt, pos));
        }
        return result;
    }

    @Nullable
    private static CompoundTag extractContraptionTag(CompoundTag entityNbt) {
        if (entityNbt.contains(ContraptionNbtKeys.CONTRAPTION, Tag.TAG_COMPOUND)) {
            return entityNbt.getCompound(ContraptionNbtKeys.CONTRAPTION);
        }
        if (entityNbt.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)) {
            CompoundTag dataTag = entityNbt.getCompound(ContraptionNbtKeys.DATA);
            if (dataTag.contains(ContraptionNbtKeys.CONTRAPTION, Tag.TAG_COMPOUND)) {
                return dataTag.getCompound(ContraptionNbtKeys.CONTRAPTION);
            }
        }
        return null;
    }

    private static Direction extractAssemblyDirection(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.ASSEMBLY_DIRECTION)) {
            return Direction.NORTH;
        }
        String dirStr = contraptionNbt.getString(ContraptionNbtKeys.ASSEMBLY_DIRECTION);
        try {
            return Direction.valueOf(dirStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[TRAIN-VALIDATOR] Invalid assembly direction: {}, defaulting to NORTH", dirStr);
            return Direction.NORTH;
        }
    }

    /**
     * Extracts bogey information from the contraption's Blocks compound.
     */
    private static List<BogeyInfo> extractBogeys(CompoundTag contraptionNbt, Direction assemblyDirection) {
        List<BogeyInfo> bogeys = new ArrayList<>();

        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) {
            LOGGER.warn("[TRAIN-VALIDATOR] Contraption has no Blocks compound tag");
            return bogeys;
        }

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);

        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST) || !blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) {
            LOGGER.warn("[TRAIN-VALIDATOR] Blocks missing Palette or BlockList");
            return bogeys;
        }

        // Build palette lookup
        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        List<String> paletteNames = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            paletteNames.add(entry.getString(ContraptionNbtKeys.NAME));
        }

        // Find bogeys in block list
        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockEntry = blockList.getCompound(i);

            int stateIndex = blockEntry.getInt(ContraptionNbtKeys.STATE);
            if (stateIndex < 0 || stateIndex >= paletteNames.size()) {
                continue;
            }

            String blockName = paletteNames.get(stateIndex);
            boolean hasBogeyData = blockEntry.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)
                    && blockEntry.getCompound(ContraptionNbtKeys.DATA)
                            .contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND);
            if (!hasBogeyData && !isBogeyBlock(blockName)) {
                continue;
            }

            // Decode packed BlockPos
            long packedPos = blockEntry.getLong(ContraptionNbtKeys.POS);
            BlockPos relativePos = unpackBlockPos(packedPos);

            // Extract bogey data
            CompoundTag bogeyData = null;
            boolean upsideDown = false;
            if (blockEntry.contains(ContraptionNbtKeys.DATA, Tag.TAG_COMPOUND)) {
                CompoundTag blockDataTag = blockEntry.getCompound(ContraptionNbtKeys.DATA);
                if (blockDataTag.contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND)) {
                    bogeyData = blockDataTag.getCompound(ContraptionNbtKeys.BOGEY_DATA);
                    upsideDown = bogeyData.getBoolean(ContraptionNbtKeys.UPSIDE_DOWN);
                }
            }

            bogeys.add(new BogeyInfo(relativePos, blockName, bogeyData, upsideDown));
        }

        // Sort bogeys by signed position along assembly axis.
        // Signed sort ensures the bogey closest to the station (Create's "leading" bogey)
        // comes first, regardless of whether the axis direction is positive or negative.
        bogeys.sort((a, b) -> {
            int posA = DirectionUtil.getSignedPositionAlongAxis(a.relativePos, assemblyDirection);
            int posB = DirectionUtil.getSignedPositionAlongAxis(b.relativePos, assemblyDirection);
            return Integer.compare(posA, posB);
        });

        return bogeys;
    }

    /**
     * Unpacks a Minecraft packed BlockPos (long) into a BlockPos.
     */
    private static BlockPos unpackBlockPos(long packed) {
        return BlockPos.of(packed);
    }

    /**
     * Checks if a block name is a bogey block.
     */
    private static boolean isBogeyBlock(String blockName) {
        if (blockName == null) return false;
        ResourceLocation blockId = ResourceLocation.tryParse(blockName);
        if (blockId != null) {
            var block = BuiltInRegistries.BLOCK.get(blockId);
            if (block instanceof AbstractBogeyBlock<?>) {
                return true;
            }
        }
        return blockName.contains("bogey");
    }

    /**
     * Recalculates the Seats and ConductorSeats lists from actual seat blocks in the BlockList.
     * Create saves one entity per bogey with the same Seats list, but those positions are only
     * correct relative to one bogey's anchor. This rebuilds them from the block data so they
     * always match the block positions in the kept entity.
     */
    private static void recalculateSeatsFromBlocks(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains(ContraptionNbtKeys.BLOCKS, Tag.TAG_COMPOUND)) return;

        CompoundTag blocksTag = contraptionNbt.getCompound(ContraptionNbtKeys.BLOCKS);
        if (!blocksTag.contains(ContraptionNbtKeys.PALETTE, Tag.TAG_LIST)
                || !blocksTag.contains(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_LIST)) return;

        // Find seat palette indices
        ListTag palette = blocksTag.getList(ContraptionNbtKeys.PALETTE, Tag.TAG_COMPOUND);
        java.util.Set<Integer> seatPaletteIndices = new java.util.HashSet<>();
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString(ContraptionNbtKeys.NAME);
            ResourceLocation blockId = ResourceLocation.tryParse(name);
            if (blockId != null) {
                var block = BuiltInRegistries.BLOCK.get(blockId);
                if (block instanceof SeatBlock) {
                    seatPaletteIndices.add(i);
                }
            }
        }
        if (seatPaletteIndices.isEmpty()) return;

        // Collect seat block positions
        ListTag blockList = blocksTag.getList(ContraptionNbtKeys.BLOCK_LIST, Tag.TAG_COMPOUND);
        ListTag newSeats = new ListTag();
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag entry = blockList.getCompound(i);
            int stateIdx = entry.getInt(ContraptionNbtKeys.STATE);
            if (seatPaletteIndices.contains(stateIdx) && entry.contains(ContraptionNbtKeys.POS, Tag.TAG_LONG)) {
                BlockPos pos = BlockPos.of(entry.getLong(ContraptionNbtKeys.POS));
                CompoundTag seatTag = new CompoundTag();
                seatTag.putInt("X", pos.getX());
                seatTag.putInt("Y", pos.getY());
                seatTag.putInt("Z", pos.getZ());
                newSeats.add(seatTag);
            }
        }

        contraptionNbt.put(ContraptionNbtKeys.SEATS, newSeats);
    }

    /**
     * Extracts SuperGlue entity bounding boxes from the schematic's entities list.
     * The AABBs are stored relative to the entity position in the schematic.
     */
    private static List<AABB> extractGlueEntities(ListTag entities) {
        List<AABB> glueBoxes = new ArrayList<>();

        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entityWrapper = entities.getCompound(i);
            CompoundTag nbt = entityWrapper.getCompound(ContraptionNbtKeys.NBT);
            String id = nbt.getString(ContraptionNbtKeys.ID);

            if (!SUPER_GLUE_ENTITY_ID.equals(id)) {
                continue;
            }

            Vec3 entityPos = extractEntityPosition(entityWrapper);
            if (entityPos == null) {
                LOGGER.warn("[TRAIN-VALIDATOR] SuperGlue entity missing position data");
                continue;
            }

            if (!nbt.contains(ContraptionNbtKeys.FROM, Tag.TAG_LIST) || !nbt.contains(ContraptionNbtKeys.TO, Tag.TAG_LIST)) {
                LOGGER.warn("[TRAIN-VALIDATOR] SuperGlue entity missing From/To bounding box data");
                continue;
            }

            ListTag fromTag = nbt.getList(ContraptionNbtKeys.FROM, Tag.TAG_DOUBLE);
            ListTag toTag = nbt.getList(ContraptionNbtKeys.TO, Tag.TAG_DOUBLE);

            if (fromTag.size() < 3 || toTag.size() < 3) {
                LOGGER.warn("[TRAIN-VALIDATOR] SuperGlue entity has invalid From/To data");
                continue;
            }

            Vec3 from = new Vec3(fromTag.getDouble(0), fromTag.getDouble(1), fromTag.getDouble(2));
            Vec3 to = new Vec3(toTag.getDouble(0), toTag.getDouble(1), toTag.getDouble(2));

            AABB box = new AABB(
                    entityPos.x + from.x, entityPos.y + from.y, entityPos.z + from.z,
                    entityPos.x + to.x, entityPos.y + to.y, entityPos.z + to.z
            );

            glueBoxes.add(box);
        }

        return glueBoxes;
    }

    /**
     * Extracts entity position from an entity wrapper tag.
     * Tries "pos" (double list) and "blockPos" (int list/array) formats.
     */
    @Nullable
    private static Vec3 extractEntityPosition(CompoundTag entityWrapper) {
        if (entityWrapper.contains(ContraptionNbtKeys.ENTITY_POS, Tag.TAG_LIST)) {
            ListTag posTag = entityWrapper.getList(ContraptionNbtKeys.ENTITY_POS, Tag.TAG_DOUBLE);
            if (posTag.size() >= 3) {
                return new Vec3(posTag.getDouble(0), posTag.getDouble(1), posTag.getDouble(2));
            }
        }

        if (entityWrapper.contains(ContraptionNbtKeys.BLOCK_POS, Tag.TAG_LIST)) {
            ListTag blockPosTag = entityWrapper.getList(ContraptionNbtKeys.BLOCK_POS, Tag.TAG_INT);
            if (blockPosTag.size() >= 3) {
                return new Vec3(blockPosTag.getInt(0), blockPosTag.getInt(1), blockPosTag.getInt(2));
            }
        }

        if (entityWrapper.contains(ContraptionNbtKeys.BLOCK_POS, Tag.TAG_INT_ARRAY)) {
            int[] blockPos = entityWrapper.getIntArray(ContraptionNbtKeys.BLOCK_POS);
            if (blockPos.length >= 3) {
                return new Vec3(blockPos[0], blockPos[1], blockPos[2]);
            }
        }

        return null;
    }

    /**
     * Loads and validates a train schematic, trying entity-based format first,
     * then falling back to raw StructureTemplate assembly.
     *
     * @param schematicNbt The schematic NBT data
     * @return ValidationResult with train data, or failure if invalid
     */
    public static ValidationResult loadAndValidateFlexible(CompoundTag schematicNbt) {

        // First try to find carriage_contraption entity (Create schematic format)
        ValidationResult entityResult = validateSchematicNbt(schematicNbt);
        if (entityResult.valid) {
            // Store the raw schematic NBT for virtual world assembly
            return ValidationResult.success(
                    entityResult.assemblyDirection,
                    entityResult.carriageContraptions,
                    entityResult.controls,
                    entityResult.bogeyLayout,
                    entityResult.trainHeight,
                    schematicNbt);
        }

        // Check if this is a disassembled (raw StructureTemplate) train schematic
        NbtSchematicLoader.LoadedSchematic rawSchematic =
                NbtSchematicLoader.parseStructureTemplateNbt(schematicNbt, "custom_train");

        if (rawSchematic != null) {
            LOGGER.warn("[TRAIN-VALIDATOR] Schematic appears to be a disassembled (raw block) train. " +
                    "Only assembled train schematics are currently supported. " +
                    "Please save your train schematic while the train is assembled on track.");
            return ValidationResult.failure("Disassembled train schematics are not currently supported. " +
                    "Please use a schematic captured from an assembled train (with carriage_contraption entities).");
        }

        return ValidationResult.failure("Could not parse schematic as Create format. " +
                "Original error: " + entityResult.errorMessage);

    }
}
