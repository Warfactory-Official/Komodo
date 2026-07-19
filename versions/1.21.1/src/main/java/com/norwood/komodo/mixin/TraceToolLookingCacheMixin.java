package com.norwood.komodo.mixin;

import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.tools.TraceTool;
import com.norwood.komodo.util.LookingEntityCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Memoizes {@link TraceTool#findLookingEntity(Entity, double)} per game tick. Superb Warfare's HUD
 * overlays each call this independently every frame, and each call is a full entity raycast; this
 * collapses them into one raycast per tick. See {@link LookingEntityCache} for the rationale.
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
