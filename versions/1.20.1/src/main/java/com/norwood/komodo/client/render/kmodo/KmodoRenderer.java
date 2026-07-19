package com.norwood.komodo.client.render.kmodo;

import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Matrix4f;

public final class KmodoRenderer {

    private KmodoRenderer() {}

    public static void drawBatch(List<VertexBuffer> buffers, List<Matrix4f> bonePoses, ResourceLocation texture,
                                 Level level, int packedLight) {
        RenderType renderType = RenderType.entityCutoutNoCull(texture);
        Matrix4f view = RenderSystem.getModelViewMatrix();
        Matrix4f projection = RenderSystem.getProjectionMatrix();

        renderType.setupRenderState();
        RenderSystem.setShaderTexture(2, KmodoLight.worldLightLightmap(packedLight));
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ShaderInstance shader = RenderSystem.getShader();
        try {
            for (int i = 0; i < buffers.size(); i++) {
                Matrix4f modelView = new Matrix4f(view).mul(bonePoses.get(i));
                VertexBuffer vbo = buffers.get(i);
                vbo.bind();
                vbo.drawWithShader(modelView, projection, shader);
            }
            VertexBuffer.unbind();
        } finally {
            renderType.clearRenderState();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            KmodoLight.finishRawDraw();
        }
    }
}
