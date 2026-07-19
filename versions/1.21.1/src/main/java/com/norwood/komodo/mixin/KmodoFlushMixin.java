package com.norwood.komodo.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.norwood.komodo.client.render.kmodo.KmodoAccumulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VehicleRenderer.class, remap = false)
public abstract class KmodoFlushMixin {

    @Inject(
            method = "render(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"),
            require = 0
    )
    private void kmodo$beginRetain(VehicleEntity entity, float yaw, float partialTick, PoseStack pose,
                                   MultiBufferSource buffers, int light, CallbackInfo ci) {
        KmodoAccumulator.clear();
    }

    @Inject(
            method = "render(Lcom/atsuishio/superbwarfare/entity/vehicle/base/VehicleEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL"),
            require = 0
    )
    private void kmodo$flushRetain(VehicleEntity entity, float yaw, float partialTick, PoseStack pose,
                                   MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (KmodoAccumulator.isEmpty()) {
            return;
        }
        ResourceLocation texture;
        try {
            texture = ((VehicleRenderer) (Object) this).getTextureLocation(entity);
        } catch (Throwable t) {
            KmodoAccumulator.clear();
            return;
        }
        KmodoAccumulator.flush(texture, entity.level(), light);
    }
}
