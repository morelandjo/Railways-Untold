package com.vodmordia.railwaysuntold.worldgen.head.state;

import com.vodmordia.railwaysuntold.datapack.SelectedStation;
import com.vodmordia.railwaysuntold.worldgen.placement.placer.SchematicPlacer;
import com.vodmordia.railwaysuntold.worldgen.village.VillageEdgeFinder;

import javax.annotation.Nullable;

/**
 * Tracks runway confirmation and station placement state for village targeting.
 */
public class RunwayConfirmationState {

    private boolean villageConfirmed;
    private boolean runwayConfirmed;
    private VillageEdgeFinder.StationZone confirmedRunway;

    private boolean stationPlaced;
    private SchematicPlacer.SchematicPlacementResult placedStation;
    @Nullable
    private SelectedStation selectedStation;

    public boolean isVillageConfirmed() {
        return villageConfirmed;
    }

    public boolean hasConfirmedRunway() {
        return runwayConfirmed;
    }

    public VillageEdgeFinder.StationZone getConfirmedRunway() {
        return confirmedRunway;
    }

    public void setPlacedStation(SchematicPlacer.SchematicPlacementResult result) {
        this.placedStation = result;
        this.stationPlaced = result != null && result.success;
    }

    public boolean isStationPlaced() {
        return stationPlaced;
    }

    public SchematicPlacer.SchematicPlacementResult getPlacedStation() {
        return placedStation;
    }

    public void setSelectedStation(@Nullable SelectedStation station) {
        this.selectedStation = station;
    }

    @Nullable
    public SelectedStation getSelectedStation() {
        return selectedStation;
    }

    public void clear() {
        this.villageConfirmed = false;
        this.runwayConfirmed = false;
        this.confirmedRunway = null;
        this.stationPlaced = false;
        this.placedStation = null;
        this.selectedStation = null;
    }

    public void restore(boolean villageConfirmed, boolean runwayConfirmed, boolean stationPlaced,
                        VillageEdgeFinder.StationZone runway) {
        this.villageConfirmed = villageConfirmed;
        this.runwayConfirmed = runwayConfirmed;
        this.stationPlaced = stationPlaced;
        this.confirmedRunway = runway;
    }
}
