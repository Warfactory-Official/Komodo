package com.norwood.komodo.mixin;

import java.util.Map;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VehicleEntity.class, remap = false)
public abstract class VehicleGunDataCacheMixin {

    @Shadow
    @Final
    private static EntityDataAccessor<Map<String, GunData>> GUN_DATA_MAP;

    @Unique
    private Object komodo$cachedRawGunMap;

    @Unique
    private Map<String, GunData> komodo$cachedGunDataMap;

    @Inject(method = "getGunDataMap", at = @At("HEAD"), cancellable = true)
    private void komodo$returnCachedGunDataMap(CallbackInfoReturnable<Map<String, GunData>> cir) {
        Object raw = ((Entity) (Object) this).getEntityData().get(GUN_DATA_MAP);
        if (this.komodo$cachedGunDataMap != null && raw == this.komodo$cachedRawGunMap) {
            cir.setReturnValue(this.komodo$cachedGunDataMap);
        }
    }

    @Inject(method = "getGunDataMap", at = @At("RETURN"))
    private void komodo$storeCachedGunDataMap(CallbackInfoReturnable<Map<String, GunData>> cir) {
        this.komodo$cachedRawGunMap = ((Entity) (Object) this).getEntityData().get(GUN_DATA_MAP);
        this.komodo$cachedGunDataMap = cir.getReturnValue();
    }
}
