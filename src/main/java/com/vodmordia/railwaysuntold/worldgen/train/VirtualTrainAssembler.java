package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.vodmordia.railwaysuntold.datapack.LootConfig;
import com.vodmordia.railwaysuntold.util.spatial.DirectionUtil;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicLootApplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Assembles train contraptions using Create's native assembly pipeline with a virtual world.
 *
 */
public final class VirtualTrainAssembler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Result of assembling a train from a schematic.
     */
    public static class AssemblyResult {
        public final boolean success;
        public final String errorMessage;
        public final List<CarriageContraption> contraptions;
        public final List<BogeyInWorld> bogeys;
        public final List<List<Integer>> carriageGroups;
        public final boolean hasForwardControls;
        public final boolean hasBackwardControls;

        private AssemblyResult(boolean success, String errorMessage,
                               List<CarriageContraption> contraptions,
                               List<BogeyInWorld> bogeys,
                               List<List<Integer>> carriageGroups,
                               boolean hasForwardControls, boolean hasBackwardControls) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.contraptions = contraptions != null ? contraptions : Collections.emptyList();
            this.bogeys = bogeys != null ? bogeys : Collections.emptyList();
            this.carriageGroups = carriageGroups != null ? carriageGroups : Collections.emptyList();
            this.hasForwardControls = hasForwardControls;
            this.hasBackwardControls = hasBackwardControls;
        }

        static AssemblyResult failure(String reason) {
            return new AssemblyResult(false, reason, null, null, null, false, false);
        }

        static AssemblyResult success(List<CarriageContraption> contraptions,
                                       List<BogeyInWorld> bogeys,
                                       List<List<Integer>> carriageGroups,
                                       boolean hasForwardControls, boolean hasBackwardControls) {
            return new AssemblyResult(true, null, contraptions, bogeys, carriageGroups,
                    hasForwardControls, hasBackwardControls);
        }
    }

    /**
     * A bogey found in the virtual world after rotation.
     */
    public static class BogeyInWorld {
        public final BlockPos worldPos;
        public final String bogeyType;
        public final CompoundTag bogeyData;
        public final boolean upsideDown;

        BogeyInWorld(BlockPos worldPos, String bogeyType, CompoundTag bogeyData, boolean upsideDown) {
            this.worldPos = worldPos;
            this.bogeyType = bogeyType;
            this.bogeyData = bogeyData;
            this.upsideDown = upsideDown;
        }
    }

    /**
     * Assembles train contraptions from a raw schematic NBT by placing blocks into a
     * virtual world with rotation and using Create's native assembly.
     *
     * @param level        The server level (for registry access and as virtual world parent)
     * @param schematicNbt The raw schematic NBT (StructureTemplate format)
     * @param trackAD      The assembly direction on the actual track
     * @param lootConfig   Loot configuration for containers
     * @return AssemblyResult with assembled contraptions, or failure
     */
    public static AssemblyResult assembleFromSchematic(
            ServerLevel level, CompoundTag schematicNbt, Direction trackAD, LootConfig lootConfig) {

        // Step 1: Extract carriage_contraption entities from schematic
        if (!schematicNbt.contains("entities", Tag.TAG_LIST)) {
            return AssemblyResult.failure("Schematic has no entities list");
        }

        ListTag entities = schematicNbt.getList("entities", Tag.TAG_COMPOUND);
        List<CarriageEntityInfo> carriageEntities = findCarriageEntities(entities);

        if (carriageEntities.isEmpty()) {
            return AssemblyResult.failure("No carriage_contraption entities found in schematic");
        }

        // Step 2: Get the schematic assembly direction from the first contraption
        Direction schematicAD = carriageEntities.get(0).assemblyDirection;

        // Step 3: Compute rotation from schematic AD to track AD
        Rotation rotation = SchematicRotator.calculateRotation(schematicAD, trackAD, false);

        // Step 4: Assemble each carriage in its own isolated virtual world.
        // Each carriage gets a fresh virtual world so Create's flood-fill doesn't
        // cross carriage boundaries. Blocks are placed with rotation applied.
        List<CarriageContraption> contraptions = new ArrayList<>();
        List<BogeyInWorld> allBogeys = new ArrayList<>();
        List<List<Integer>> carriageGroups = new ArrayList<>();
        boolean hasForward = false;
        boolean hasBackward = false;
        int bogeyIndexOffset = 0;

        for (int c = 0; c < carriageEntities.size(); c++) {
            CarriageEntityInfo entity = carriageEntities.get(c);

            // Create isolated virtual world for this carriage
            TrainAssemblyLevel virtualWorld = new TrainAssemblyLevel(level);
            placeSingleCarriageBlocks(entity, virtualWorld, rotation, lootConfig);

            // Find bogeys in this carriage's virtual world
            List<BogeyInWorld> carriageBogeys = scanForBogeys(virtualWorld);
            if (carriageBogeys.isEmpty()) {
                LOGGER.warn("[VIRTUAL-ASSEMBLER] Carriage {} has no bogeys", c);
                return AssemblyResult.failure("Carriage " + c + " has no bogey blocks");
            }

            // Sort bogeys within this carriage along the track AD
            carriageBogeys.sort((a, b) -> {
                int posA = DirectionUtil.getSignedPositionAlongAxis(a.worldPos, trackAD);
                int posB = DirectionUtil.getSignedPositionAlongAxis(b.worldPos, trackAD);
                return Integer.compare(posA, posB);
            });

            // Assemble from the first bogey position
            BlockPos firstBogeyPos = carriageBogeys.get(0).worldPos;

            try {
                CarriageContraption contraption = new CarriageContraption(trackAD);
                boolean assembled = contraption.assemble(virtualWorld, firstBogeyPos);

                if (!assembled) {
                    return AssemblyResult.failure("Carriage " + c + " assembly failed at " + firstBogeyPos);
                }


                hasForward |= contraption.hasForwardControls();
                hasBackward |= contraption.hasBackwardControls();
                contraptions.add(contraption);

                // Track bogey indices for this carriage group
                List<Integer> group = new ArrayList<>();
                for (int i = 0; i < carriageBogeys.size(); i++) {
                    group.add(bogeyIndexOffset + i);
                }
                carriageGroups.add(group);
                allBogeys.addAll(carriageBogeys);
                bogeyIndexOffset += carriageBogeys.size();

            } catch (AssemblyException e) {
                return AssemblyResult.failure("Carriage " + c + " assembly error: " + e.component.getString());
            }
        }

        if (!hasForward && !hasBackward) {
            return AssemblyResult.failure("No train controls found in any carriage");
        }


        return AssemblyResult.success(contraptions, allBogeys, carriageGroups,
                hasForward, hasBackward);
    }

    // Carriage entity extraction

    /**
     * Info extracted from a carriage_contraption entity in the schematic.
     */
    private static class CarriageEntityInfo {
        final CompoundTag contraptionNbt;
        final Vec3 entityPos;
        final Direction assemblyDirection;

        CarriageEntityInfo(CompoundTag contraptionNbt, Vec3 entityPos, Direction assemblyDirection) {
            this.contraptionNbt = contraptionNbt;
            this.entityPos = entityPos;
            this.assemblyDirection = assemblyDirection;
        }
    }

    /**
     * Finds carriage_contraption entities in the schematic.
     * Create saves exactly one entity per carriage (not per bogey), so no
     * deduplication is needed - each entity is a distinct carriage.
     */
    private static List<CarriageEntityInfo> findCarriageEntities(ListTag entities) {
        List<CarriageEntityInfo> result = new ArrayList<>();

        for (int i = 0; i < entities.size(); i++) {
            CompoundTag entityWrapper = entities.getCompound(i);
            CompoundTag nbt = entityWrapper.getCompound("nbt");
            String id = nbt.getString("id");
            if (!"create:carriage_contraption".equals(id)) continue;

            CompoundTag contraptionTag = extractContraptionTag(nbt);
            if (contraptionTag == null) continue;

            Vec3 entityPos = extractEntityPosition(entityWrapper);
            if (entityPos == null) entityPos = Vec3.ZERO;

            Direction ad = extractAssemblyDirection(contraptionTag);
            CarriageEntityInfo info = new CarriageEntityInfo(contraptionTag, entityPos, ad);
            result.add(info);
        }

        // Sort by signed position so carriage[0] = nearest station.
        // Use the AD axis if entities have spread along it; otherwise fall back to
        // the spread axis (entities captured perpendicular to original AD).
        // Apply the AD's sign so negative-axis directions (WEST, NORTH) sort correctly.
        if (result.size() > 1) {
            Direction sortDirection = result.get(0).assemblyDirection;
            Direction.Axis adAxis = sortDirection.getAxis();
            double adSpread = getSpreadOnAxis(result, adAxis);
            Direction.Axis sortAxis = adSpread > 0.5 ? adAxis : findSpreadAxis(result);
            int sign = sortDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : -1;
            result.sort((a, b) -> Double.compare(
                    getAxisComponent(a.entityPos, sortAxis) * sign,
                    getAxisComponent(b.entityPos, sortAxis) * sign));
        }

        return result;
    }

    private static CompoundTag extractContraptionTag(CompoundTag entityNbt) {
        if (entityNbt.contains("Contraption", Tag.TAG_COMPOUND))
            return entityNbt.getCompound("Contraption");
        if (entityNbt.contains("Data", Tag.TAG_COMPOUND)) {
            CompoundTag data = entityNbt.getCompound("Data");
            if (data.contains("Contraption", Tag.TAG_COMPOUND))
                return data.getCompound("Contraption");
        }
        return null;
    }

    private static Direction extractAssemblyDirection(CompoundTag contraptionNbt) {
        if (!contraptionNbt.contains("AssemblyDirection")) return Direction.NORTH;
        try {
            return Direction.valueOf(contraptionNbt.getString("AssemblyDirection").toUpperCase());
        } catch (IllegalArgumentException e) {
            return Direction.NORTH;
        }
    }

    private static Vec3 extractEntityPosition(CompoundTag entityWrapper) {
        if (entityWrapper.contains("pos", Tag.TAG_LIST)) {
            ListTag posTag = entityWrapper.getList("pos", Tag.TAG_DOUBLE);
            if (posTag.size() >= 3)
                return new Vec3(posTag.getDouble(0), posTag.getDouble(1), posTag.getDouble(2));
        }
        if (entityWrapper.contains("blockPos", Tag.TAG_LIST)) {
            ListTag blockPosTag = entityWrapper.getList("blockPos", Tag.TAG_INT);
            if (blockPosTag.size() >= 3)
                return new Vec3(blockPosTag.getInt(0), blockPosTag.getInt(1), blockPosTag.getInt(2));
        }
        if (entityWrapper.contains("blockPos", Tag.TAG_INT_ARRAY)) {
            int[] blockPos = entityWrapper.getIntArray("blockPos");
            if (blockPos.length >= 3)
                return new Vec3(blockPos[0], blockPos[1], blockPos[2]);
        }
        return null;
    }

    private static Direction.Axis findSpreadAxis(List<CarriageEntityInfo> entities) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (CarriageEntityInfo e : entities) {
            minX = Math.min(minX, e.entityPos.x);
            maxX = Math.max(maxX, e.entityPos.x);
            minZ = Math.min(minZ, e.entityPos.z);
            maxZ = Math.max(maxZ, e.entityPos.z);
        }
        return (maxX - minX) >= (maxZ - minZ) ? Direction.Axis.X : Direction.Axis.Z;
    }

    private static double getSpreadOnAxis(List<CarriageEntityInfo> entities, Direction.Axis axis) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (CarriageEntityInfo e : entities) {
            double val = getAxisComponent(e.entityPos, axis);
            min = Math.min(min, val);
            max = Math.max(max, val);
        }
        return max - min;
    }

    static double getAxisComponent(Vec3 vec, Direction.Axis axis) {
        return switch (axis) {
            case X -> vec.x;
            case Y -> vec.y;
            case Z -> vec.z;
        };
    }


    /**
     * Places blocks from a single carriage entity into the virtual world with rotation.
     * Each carriage must be placed in its own virtual world to prevent flood-fill
     * from crossing carriage boundaries during assembly.
     */
    private static int placeSingleCarriageBlocks(
            CarriageEntityInfo entity,
            TrainAssemblyLevel virtualWorld,
            Rotation rotation,
            LootConfig lootConfig) {

        CompoundTag contraptionNbt = entity.contraptionNbt;
        if (!contraptionNbt.contains("Blocks", Tag.TAG_COMPOUND)) return 0;
        CompoundTag blocksTag = contraptionNbt.getCompound("Blocks");
        if (!blocksTag.contains("Palette", Tag.TAG_LIST) || !blocksTag.contains("BlockList", Tag.TAG_LIST))
            return 0;

        // Parse palette to BlockState objects
        ListTag palette = blocksTag.getList("Palette", Tag.TAG_COMPOUND);
        List<BlockState> paletteStates = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) {
            paletteStates.add(parsePaletteEntry(palette.getCompound(i)));
        }

        // Entity position (anchor point for this carriage's blocks)
        BlockPos entityBlockPos = BlockPos.containing(entity.entityPos);

        // Use Create's StructureTransform for block state rotation instead of vanilla
        // state.rotate(). StructureTransform checks the TransformableBlock interface,
        // which Copycats+ blocks implement for custom property rotation (octants, panel
        // quadrants, offset, etc.) that vanilla Block.rotate() does not handle.
        StructureTransform structureTransform = new StructureTransform(
                BlockPos.ZERO, Direction.Axis.Y, rotation, Mirror.NONE);

        int totalBlocks = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        ListTag blockList = blocksTag.getList("BlockList", Tag.TAG_COMPOUND);
        for (int i = 0; i < blockList.size(); i++) {
            CompoundTag blockEntry = blockList.getCompound(i);
            int stateIndex = blockEntry.getInt("State");
            if (stateIndex < 0 || stateIndex >= paletteStates.size()) continue;

            BlockState state = paletteStates.get(stateIndex);
            if (state.isAir()) continue;

            // Block position is relative to the entity anchor (first bogey)
            BlockPos relativePos = BlockPos.of(blockEntry.getLong("Pos"));
            BlockPos worldPos = entityBlockPos.offset(relativePos);

            // Apply rotation to position and state
            BlockPos rotatedPos = SchematicRotator.rotateBlockPos(worldPos, rotation);
            BlockState rotatedState = structureTransform.apply(state);

            virtualWorld.setBlock(rotatedPos, rotatedState, 0);
            totalBlocks++;

            // Track bounding box for SuperGlue
            minX = Math.min(minX, rotatedPos.getX());
            minY = Math.min(minY, rotatedPos.getY());
            minZ = Math.min(minZ, rotatedPos.getZ());
            maxX = Math.max(maxX, rotatedPos.getX());
            maxY = Math.max(maxY, rotatedPos.getY());
            maxZ = Math.max(maxZ, rotatedPos.getZ());

            // Store block entity data as raw NBT via a safe dummy BlockEntity.
            // We can't use BlockEntity.loadStatic() because partially-initialized BEs
            // crash when saveWithFullMetadata() is called (e.g., FluidTankBlockEntity).
            if (blockEntry.contains("Data", Tag.TAG_COMPOUND)) {
                CompoundTag beNbt = blockEntry.getCompound("Data").copy();
                // Apply loot table to container NBT before loading into virtual world
                if (!lootConfig.isEmpty()) {
                    String blockTypeId = BuiltInRegistries.BLOCK.getKey(rotatedState.getBlock()).toString();
                    SchematicLootApplier.applyLoot(beNbt, lootConfig, blockTypeId, rotatedPos.asLong());
                }
                virtualWorld.setRawBlockEntityNbt(rotatedPos, rotatedState, beNbt);
            }
        }

        // Create a SuperGlue entity covering all blocks so Create's flood-fill
        // can propagate through the entire carriage during assembly
        if (totalBlocks > 0) {
            net.minecraft.world.phys.AABB glueBounds = new net.minecraft.world.phys.AABB(
                    minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1);
            com.simibubi.create.content.contraptions.glue.SuperGlueEntity glue =
                    new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(
                            virtualWorld, glueBounds);
            virtualWorld.addEntity(glue);
        }

        return totalBlocks;
    }

    /**
     * Parses a palette entry ({Name, Properties}) to a BlockState.
     */
    private static BlockState parsePaletteEntry(CompoundTag entry) {
        String name = entry.getString("Name");
        ResourceLocation blockId = ResourceLocation.tryParse(name);
        if (blockId == null) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();

        Block block = BuiltInRegistries.BLOCK.get(blockId);
        BlockState state = block.defaultBlockState();

        if (entry.contains("Properties", Tag.TAG_COMPOUND)) {
            CompoundTag props = entry.getCompound("Properties");
            StateDefinition<Block, BlockState> stateDef = block.getStateDefinition();
            for (String key : props.getAllKeys()) {
                Property<?> property = stateDef.getProperty(key);
                if (property != null) {
                    state = setPropertyFromString(state, property, props.getString(key));
                }
            }
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setPropertyFromString(BlockState state, Property property, String value) {
        Optional<?> parsed = property.getValue(value);
        if (parsed.isPresent()) {
            return state.setValue(property, (Comparable) parsed.get());
        }
        return state;
    }

    // Bogey scanning

    /**
     * Scans the virtual world for all bogey blocks.
     */
    private static List<BogeyInWorld> scanForBogeys(TrainAssemblyLevel virtualWorld) {
        List<BogeyInWorld> bogeys = new ArrayList<>();

        for (BlockPos pos : virtualWorld.getAllBlockPositions()) {
            BlockState state = virtualWorld.getBlockState(pos);
            boolean isBogeyByType = state.getBlock() instanceof AbstractBogeyBlock<?>;

            // Also check block entity NBT for BogeyData - catches bogey blocks from
            // mods (e.g. Big Bogeys) that may not extend AbstractBogeyBlock or whose
            // name doesn't contain "bogey".
            CompoundTag bogeyData = null;
            boolean upsideDown = false;
            BlockEntity be = virtualWorld.getBlockEntity(pos);
            CompoundTag beNbt = null;
            if (be != null) {
                beNbt = be.saveWithFullMetadata(virtualWorld.registryAccess());
            }

            boolean isBogeyByData = beNbt != null
                    && beNbt.contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND);

            if (!isBogeyByType && !isBogeyByData) continue;

            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).toString();

            if (beNbt != null) {
                if (beNbt.contains(ContraptionNbtKeys.UPSIDE_DOWN)) {
                    upsideDown = beNbt.getBoolean(ContraptionNbtKeys.UPSIDE_DOWN);
                }
                if (beNbt.contains(ContraptionNbtKeys.BOGEY_DATA, Tag.TAG_COMPOUND)) {
                    bogeyData = beNbt.getCompound(ContraptionNbtKeys.BOGEY_DATA).copy();
                }
            }

            bogeys.add(new BogeyInWorld(pos.immutable(), blockId, bogeyData, upsideDown));
        }

        return bogeys;
    }

    private VirtualTrainAssembler() {}
}
