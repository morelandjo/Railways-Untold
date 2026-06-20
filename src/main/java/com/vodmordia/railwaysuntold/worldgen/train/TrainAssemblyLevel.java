package com.vodmordia.railwaysuntold.worldgen.train;

import com.mojang.logging.LogUtils;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Minimal virtual Level for assembling train contraptions without placing blocks
 * in the real world.
 */
public class TrainAssemblyLevel extends Level {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel parentLevel;
    private final Map<BlockPos, BlockState> blockStates = new HashMap<>();
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final DummyChunkSource chunkSource;

    public TrainAssemblyLevel(ServerLevel parentLevel) {
        super(
                (net.minecraft.world.level.storage.WritableLevelData) parentLevel.getLevelData(),
                parentLevel.dimension(),
                parentLevel.registryAccess(),
                parentLevel.dimensionTypeRegistration(),
                parentLevel::getProfiler,
                false,  // isClientSide = false
                false,  // isDebug
                0L,     // biomeZoomSeed
                0       // maxChainedNeighborUpdates
        );
        this.parentLevel = parentLevel;
        this.chunkSource = new DummyChunkSource(this);
    }

    // Block storage

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        pos = pos.immutable();
        if (state.isAir()) {
            blockStates.remove(pos);
        } else {
            blockStates.put(pos, state);
        }
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blockStates.get(pos);
        return state != null ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    /**
     * Stores block entity data at a position. For container blocks (chests, barrels, etc.),
     */
    public void setRawBlockEntityNbt(BlockPos pos, BlockState state, CompoundTag nbt) {
        pos = pos.immutable();

        // Try to create a real BlockEntity so mods that rely on their own
        // load()/getModelData() work correctly (containers for Create's
        // MountedStorageManager, FramedBlocks for camo rendering, etc.).
        // The FramedBlockEntityMixin converts network-format camo data to
        // persistent format before load() processes it.
        // Only fall back to RawNbtBlockEntity if the real one crashes.
        if (state.getBlock() instanceof EntityBlock entityBlock) {
            try {
                BlockEntity real = entityBlock.newBlockEntity(pos, state);
                if (real != null) {
                    real.setLevel(this);
                    real.loadWithComponents(nbt, parentLevel.registryAccess());
                    blockEntities.put(pos, real);
                    return;
                }
            } catch (Exception e) {
                // Fall through to RawNbtBlockEntity
                LOGGER.warn(
                        "[TrainAssemblyLevel] Real BlockEntity load failed for {}, falling back to RawNbtBlockEntity: {}",
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), e.getMessage(), e);
            }
        }

        // Fallback: use safe dummy wrapper for blocks whose real BE crashes
        RawNbtBlockEntity dummy = new RawNbtBlockEntity(pos, state, nbt);
        dummy.setLevel(this);
        blockEntities.put(pos, dummy);

        // Validate: for copycat blocks, ensure material data is preserved
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (blockId.contains("copycat")) {
            boolean hasMaterial = nbt.contains("Material") || nbt.contains("material_data");
            if (!hasMaterial) {
                LOGGER.warn(
                        "[VIRTUAL-ASSEMBLER] Copycat block {} at {} has NO material data in stored NBT! Keys: {}",
                        blockId, pos, nbt.getAllKeys());
            }
            // Verify round-trip through saveWithFullMetadata
            CompoundTag saved = dummy.saveWithFullMetadata(parentLevel.registryAccess());
            boolean savedHasMaterial = saved.contains("Material") || saved.contains("material_data");
            if (hasMaterial && !savedHasMaterial) {
                LOGGER.warn(
                        "[VIRTUAL-ASSEMBLER] MATERIAL DATA LOST in saveWithFullMetadata! block={}, pos={}, storedKeys={}, savedKeys={}",
                        blockId, pos, nbt.getAllKeys(), saved.getAllKeys());
            }
            // Verify getUpdateTag round-trip
            CompoundTag updateTag = dummy.getUpdateTag(parentLevel.registryAccess());
            boolean updateHasMaterial = updateTag.contains("Material") || updateTag.contains("material_data");
            if (hasMaterial && !updateHasMaterial) {
                LOGGER.warn(
                        "[VIRTUAL-ASSEMBLER] MATERIAL DATA LOST in getUpdateTag! block={}, pos={}, storedKeys={}, updateKeys={}",
                        blockId, pos, nbt.getAllKeys(), updateTag.getAllKeys());
            }
        }
    }

    // Chunk/loading - always loaded

    @Override
    public boolean isLoaded(BlockPos pos) {
        return true;
    }

    public boolean isAreaLoaded(BlockPos pos, int range) {
        return true;
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkSource;
    }

    // Build height - generous range

    @Override
    public int getMinBuildHeight() {
        return -64;
    }

    @Override
    public int getHeight() {
        return 384;
    }

    // Delegated to parent

    @Override
    public RegistryAccess registryAccess() {
        return parentLevel.registryAccess();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return parentLevel.enabledFeatures();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return parentLevel.potionBrewing();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return parentLevel.getRecipeManager();
    }

    @Override
    public Scoreboard getScoreboard() {
        return parentLevel.getScoreboard();
    }

    @Override
    public TickRateManager tickRateManager() {
        return parentLevel.tickRateManager();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 1.0f;
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return parentLevel.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .getOrThrow(Biomes.PLAINS);
    }

    // No-op stubs - not needed for assembly

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return parentLevel.getLightEngine();
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, double x, double y, double z,
                                 Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void playSeededSound(@Nullable Player player, Entity entity,
                                 Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) {
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
    }

    @Override
    public void setDayTimeFraction(float fraction) {
    }

    @Override
    public void setDayTimePerTick(float perTick) {
    }

    @Override
    public float getDayTimeFraction() {
        return parentLevel.getDayTimeFraction();
    }

    @Override
    public float getDayTimePerTick() {
        return parentLevel.getDayTimePerTick();
    }

    @Override
    public String gatherChunkSourceStats() {
        return "TrainAssemblyLevel";
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id) {
        return null;
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData data) {
    }

    @Override
    public MapId getFreeMapId() {
        return parentLevel.getFreeMapId();
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public List<? extends Player> players() {
        return Collections.emptyList();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return new SimpleEntityGetter<>(entities);
    }

    @Override
    public void updateNeighbourForOutputSignal(BlockPos pos, Block block) {
    }

    // Utility

    /**
     * Returns all positions with non-air blocks.
     */
    public Set<BlockPos> getAllBlockPositions() {
        return Collections.unmodifiableSet(blockStates.keySet());
    }

    /**
     * Adds an entity to this virtual world (e.g., SuperGlue entities for assembly).
     */
    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    // Inner classes

    /**
     * Minimal ChunkSource that reports all chunks as loaded.
     * Returns EmptyLevelChunk instances to satisfy mods (e.g. TooManyOrigins)
     * that query block/fluid state through the chunk rather than through Level directly.
     */
    private static class DummyChunkSource extends ChunkSource {
        private final TrainAssemblyLevel level;

        DummyChunkSource(TrainAssemblyLevel level) {
            this.level = level;
        }

        @Override
        public LevelChunk getChunk(int x, int z, boolean load) {
            return new net.minecraft.world.level.chunk.EmptyLevelChunk(level, new net.minecraft.world.level.ChunkPos(x, z),
                    level.parentLevel.registryAccess()
                            .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .getOrThrow(Biomes.PLAINS));
        }

        @Override
        public ChunkAccess getChunk(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean load) {
            return getChunk(x, z, false);
        }

        @Override
        public String gatherStats() {
            return "TrainAssemblyLevel";
        }

        @Override
        public int getLoadedChunksCount() {
            return 0;
        }

        @Override
        public Level getLevel() {
            return level;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return level.getLightEngine();
        }

        @Override
        public void tick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks) {
        }
    }

    /**
     * Simple entity getter backed by a list. Supports getEntitiesOfClass lookups
     * needed by SuperGlueEntity.isGlued() during assembly.
     */
    private static class SimpleEntityGetter<T extends Entity> implements LevelEntityGetter<T> {
        private final List<? extends Entity> entities;

        SimpleEntityGetter(List<? extends Entity> entities) {
            this.entities = entities;
        }

        @Nullable
        @Override
        public T get(int id) {
            return null;
        }

        @Nullable
        @Override
        public T get(java.util.UUID uuid) {
            return null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Iterable<T> getAll() {
            return (Iterable<T>) Collections.unmodifiableList(entities);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <U extends T> void get(net.minecraft.world.level.entity.EntityTypeTest<T, U> test,
                                       net.minecraft.util.AbortableIterationConsumer<U> consumer) {
            for (Entity entity : entities) {
                T asT = (T) entity;
                U typed = test.tryCast(asT);
                if (typed != null) {
                    consumer.accept(typed);
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void get(net.minecraft.world.phys.AABB area,
                        java.util.function.Consumer<T> consumer) {
            for (Entity entity : entities) {
                if (entity.getBoundingBox().intersects(area)) {
                    consumer.accept((T) entity);
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public <U extends T> void get(net.minecraft.world.level.entity.EntityTypeTest<T, U> test,
                                       net.minecraft.world.phys.AABB area,
                                       net.minecraft.util.AbortableIterationConsumer<U> consumer) {
            for (Entity entity : entities) {
                if (entity.getBoundingBox().intersects(area)) {
                    T asT = (T) entity;
                    U typed = test.tryCast(asT);
                    if (typed != null) {
                        consumer.accept(typed);
                    }
                }
            }
        }
    }

    /**
     * A dummy BlockEntity that stores raw NBT and returns it verbatim from
     * {@link #saveWithFullMetadata}. This avoids instantiating real block entities
     * (like FluidTankBlockEntity) that crash when their fields are uninitialized.
     *
     */
    public static class RawNbtBlockEntity extends BlockEntity {
        private final CompoundTag storedNbt;

        public RawNbtBlockEntity(BlockPos pos, BlockState state, CompoundTag nbt) {
            super(findBlockEntityType(state), pos, state);
            this.storedNbt = nbt.copy();
        }

        /**
         * Finds the BlockEntityType that the block declares via its EntityBlock interface.
         * Falls back to a registry scan if newBlockEntity() throws (some mods like
         * FramedBlocks fail outside a proper world context), then to COMMAND_BLOCK.
         */
        private static net.minecraft.world.level.block.entity.BlockEntityType<?> findBlockEntityType(BlockState state) {
            if (state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock entityBlock) {
                try {
                    BlockEntity temp = entityBlock.newBlockEntity(BlockPos.ZERO, state);
                    if (temp != null) {
                        return temp.getType();
                    }
                } catch (Exception e) {
                    // newBlockEntity() failed - try registry scan fallback
                    net.minecraft.resources.ResourceLocation blockId =
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    LOGGER.warn(
                            "[RawNbtBlockEntity] newBlockEntity() failed for {}, trying registry scan: {}",
                            blockId, e.getMessage(), e);
                    for (net.minecraft.world.level.block.entity.BlockEntityType<?> type :
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE) {
                        if (type.isValid(state)) {
                            return type;
                        }
                    }
                    LOGGER.warn(
                            "[RawNbtBlockEntity] Registry scan found no type for {}, falling back to COMMAND_BLOCK",
                            blockId);
                }
            }
            return net.minecraft.world.level.block.entity.BlockEntityType.COMMAND_BLOCK;
        }

        @Override
        protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            // No-op: we don't initialize any fields from NBT
        }

        @Override
        protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
            // Merge all stored NBT keys into the output tag
            for (String key : storedNbt.getAllKeys()) {
                tag.put(key, storedNbt.get(key).copy());
            }
        }

        @Override
        public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
            // Return stored NBT as the update tag - Create uses this for rendering
            // copycat materials (Material, material_data), bogey styles, etc.
            CompoundTag tag = super.getUpdateTag(registries);
            for (String key : storedNbt.getAllKeys()) {
                tag.put(key, storedNbt.get(key).copy());
            }
            return tag;
        }
    }
}
