package com.norwood.komodo.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.world.entity.Entity;

import com.norwood.komodo.client.render.kmodo.KmodoAccumulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

@Mixin(value = GeoEntityRenderer.class, remap = false)
public abstract class KmodoCubeRedirectMixin {

    @Redirect(
            method = "renderRecursively(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/Entity;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZFIIFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/renderer/GeoEntityRenderer;renderCubesOfBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V"
            ),
            require = 0
    )
    private void kmodo$retainCubes(GeoEntityRenderer<?> self, PoseStack pose, GeoBone bone, VertexConsumer buffer,
                                   int packedLight, int packedOverlay, float red, float green, float blue,
                                   float alpha,

                                   PoseStack enclosingPose, Entity animatable) {
        if (!KmodoAccumulator.tryRecord(self, animatable, pose, bone)) {
            self.renderCubesOfBone(pose, bone, buffer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }
}
