package com.vodmordia.railwaysuntold.util.spatial;

import net.minecraft.world.phys.Vec3;

/**
 * A pair of unit vectors pointing to the left and right of a track segment, both
 * already perpendicular to the direction of travel.
 */
public record DirectionOffsets(Vec3 left, Vec3 right) {
    /**
     * Splits a cross-track perpendicular into symmetric left / right offsets.
     * Callers must pass a vector that IS already perpendicular to the track
     */
    public static DirectionOffsets fromNormal(Vec3 normal) {
        Vec3 normalized = normal.normalize();
        return new DirectionOffsets(normalized, normalized.scale(-1));
    }
}
