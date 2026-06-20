package com.vodmordia.railwaysuntold.mixin;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mixin to fix train controls not working on steep inclines.
 *
 * On steep inclines, the train's pitch rotation causes toGlobalVector() to return
 * a position that is further from player.position() than expected, even when the
 * player is seated right next to the controls.
 */
@Mixin(CarriageContraptionEntity.class)
public abstract class CarriageContraptionEntityMixin {

    private static final double LENIENT_CONTROL_DISTANCE = 24.0;

    @ModifyArg(
        method = "control",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/phys/Vec3;closerThan(Lnet/minecraft/core/Position;D)Z"
        ),
        index = 1,
        require = 0,
        expect = 0
    )
    private double railwaysuntold$lenientDistance(double originalDistance) {
        return LENIENT_CONTROL_DISTANCE;
    }
}
