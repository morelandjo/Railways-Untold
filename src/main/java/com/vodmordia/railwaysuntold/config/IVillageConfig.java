package com.vodmordia.railwaysuntold.config;

/**
 * Configuration for structure targeting and station placement.
 */
public interface IVillageConfig {

    boolean isVillageTargetingEnabled();
    int getVillageTargetingChance();
    int getVillageSearchRadius();
    int getVillageMinDistanceFromSpawn();
}
