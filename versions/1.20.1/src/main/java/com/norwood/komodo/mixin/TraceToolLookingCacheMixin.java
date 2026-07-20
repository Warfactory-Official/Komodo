package com.norwood.komodo.mixin;

import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.tools.TraceTool;
import com.norwood.komodo.util.LookingEntityCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Superb shoots out like 5 raycasts per frame, hence I want to collapse and cache it
 */
@Mixin(value = TraceTool.class, remap = false)
public abstract class TraceToolLookingCacheMixin {

    @Inject(method = "findLookingEntity", at = @At("HEAD"), cancellable = true, remap = false)
    private static void komodo$returnCachedLookingEntity(Entity entity, double entityReach,
                                                         CallbackInfoReturnable<Entity> cir) {
        Object cached = LookingEntityCache.get(entity, entityReach);
        if (cached != LookingEntityCache.MISS) {
            cir.setReturnValue((Entity) cached);
        }
    }

    @Inject(method = "findLookingEntity", at = @At("RETURN"), remap = false)
    private static void komodo$storeLookingEntity(Entity entity, double entityReach,
                                                  CallbackInfoReturnable<Entity> cir) {
        LookingEntityCache.put(entity, entityReach, cir.getReturnValue());
    }
}
