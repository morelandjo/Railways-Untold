package com.vodmordia.railwaysuntold.mixin;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mixin to fix train controls not working on steep inclines.
 */
@Mixin(value = CarriageContraptionEntity.class, remap = false)
public abstract class CarriageContraptionEntityMixin {

    private static final double LENIENT_CONTROL_DISTANCE = 24.0;

    @ModifyArg(
            method = "control(Lnet/minecraft/core/BlockPos;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;)Z",
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
