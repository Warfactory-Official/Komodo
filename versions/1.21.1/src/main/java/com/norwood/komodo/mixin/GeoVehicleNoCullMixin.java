package com.norwood.komodo.mixin;

import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GeoVehicleEntity.class, remap = false)
public abstract class GeoVehicleNoCullMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void komodo$disableCulling(CallbackInfo ci) {
        ((Entity) (Object) this).noCulling = true;
    }
}
