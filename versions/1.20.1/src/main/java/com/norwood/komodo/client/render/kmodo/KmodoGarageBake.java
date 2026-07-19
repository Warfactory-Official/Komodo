package com.norwood.komodo.client.render.kmodo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.texture.OverlayTexture;

import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.util.RenderUtils;

public final class KmodoGarageBake {

    private KmodoGarageBake() {}

    public static ByteBuffer bake(GeoRenderer<?> renderer, BakedGeoModel baked, PoseStack pose, int packedLight) {
        final boolean prof = KmodoProfiler.enabled();
        long t0 = prof ? System.nanoTime() : 0L;
        BufferBuilder builder = new BufferBuilder(8192);
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        boolean[] any = {false};
        for (GeoBone top : baked.topLevelBones()) {
            walk(renderer, pose, top, builder, packedLight, any, false);
        }
        if (!any[0]) {
            builder.end().release();
            return null;
        }
        BufferBuilder.RenderedBuffer rendered = builder.end();
        BufferBuilder.DrawState draw = rendered.drawState();
        int count = draw.vertexCount();
        if (count == 0) {
            rendered.release();
            return null;
        }
        int stride = draw.format().getVertexSize();
        ByteBuffer src = rendered.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int origin = src.position();
        ByteBuffer out = ByteBuffer.allocateDirect(count * KmodoGaragePool.VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            int base = origin + i * stride;
            for (int b = 0; b < KmodoGaragePool.VERTEX_STRIDE; b++) {
                out.put(i * KmodoGaragePool.VERTEX_STRIDE + b, src.get(base + b));
            }
        }
        out.position(0);
        out.limit(count * KmodoGaragePool.VERTEX_STRIDE);
        rendered.release();
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.GARAGE_BAKE, System.nanoTime() - t0);
        }
        return out;
    }

    private static void walk(GeoRenderer<?> renderer, PoseStack pose, GeoBone bone, BufferBuilder builder,
                             int packedLight, boolean[] any, boolean suppressed) {
        pose.pushPose();
        RenderUtils.prepMatrixForBone(pose, bone);
        boolean drawable = bone.getName() != null && !bone.getName().endsWith("_dogTag")
                && !bone.getCubes().isEmpty();
        if (drawable) {
            if (suppressed || bone.isHidden()) {
                emitDegenerateBone(bone, builder);
            } else {
                renderer.renderCubesOfBone(pose, bone, builder, packedLight, OverlayTexture.NO_OVERLAY,
                        1f, 1f, 1f, 1f);
            }
            any[0] = true;
        }
        boolean childSuppressed = suppressed || bone.isHidingChildren();
        for (GeoBone child : bone.getChildBones()) {
            walk(renderer, pose, child, builder, packedLight, any, childSuppressed);
        }
        pose.popPose();
    }

    private static void emitDegenerateBone(GeoBone bone, BufferBuilder builder) {
        for (GeoCube cube : bone.getCubes()) {
            for (GeoQuad quad : cube.quads()) {
                if (quad == null) {
                    continue;
                }
                for (int v = 0; v < 4; v++) {
                    builder.vertex(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f,
                            OverlayTexture.NO_OVERLAY, 0, 0f, 1f, 0f);
                }
            }
        }
    }
}
