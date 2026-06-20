package com.vodmordia.railwaysuntold.datapack;

import com.vodmordia.railwaysuntold.worldgen.placement.placer.NbtSchematicLoader;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicValidator;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Bundles all data for a selected station schematic, flowing as a parameter
 * through the station evaluation and placement pipeline.
 *
 * Created by {@link StationDefinitionLoader} (data pack stations) or by
 * loading the built-in default station from resources.
 */
public class SelectedStation {

    private final @Nullable StationDefinition definition;
    private final NbtSchematicLoader.LoadedSchematic schematic;
    private final SchematicValidator.SchematicValidationResult validation;
    private final List<BlockPos> stationBlockPositions;

    public SelectedStation(@Nullable StationDefinition definition,
                           NbtSchematicLoader.LoadedSchematic schematic,
                           SchematicValidator.SchematicValidationResult validation,
                           List<BlockPos> stationBlockPositions) {
        this.definition = definition;
        this.schematic = schematic;
        this.validation = validation;
        this.stationBlockPositions = stationBlockPositions;
    }

    /**
     * Creates a SelectedStation from a loader entry.
     */
    public static SelectedStation fromLoaderEntry(StationDefinitionLoader.ValidatedStationEntry entry) {
        return new SelectedStation(
                entry.definition(),
                entry.schematic(),
                entry.validation(),
                entry.getStationBlockPositions()
        );
    }

    /**
     * Creates a SelectedStation for the built-in default (no definition JSON).
     */
    public static SelectedStation fromDefault(NbtSchematicLoader.LoadedSchematic schematic,
                                              SchematicValidator.SchematicValidationResult validation,
                                              List<BlockPos> stationBlockPositions) {
        return new SelectedStation(null, schematic, validation, stationBlockPositions);
    }

    public NbtSchematicLoader.LoadedSchematic schematic() {
        return schematic;
    }

    public SchematicValidator.SchematicValidationResult validation() {
        return validation;
    }

    public List<BlockPos> getStationBlockPositions() {
        return stationBlockPositions;
    }

    public boolean hasStationBlocks() {
        return !stationBlockPositions.isEmpty();
    }

    public LootConfig loot() {
        return definition != null ? definition.loot() : LootConfig.EMPTY;
    }

    public int[] getDimensions() {
        return new int[]{schematic.getWidth(), schematic.getHeight(), schematic.getLength()};
    }
}
