package com.vodmordia.railwaysuntold.config;

/**
 * Configuration for custom event placement along tracks.
 */
public interface IEventConfig {

    int getEventSeparationMinDistance();
    int getEventChance();
}
