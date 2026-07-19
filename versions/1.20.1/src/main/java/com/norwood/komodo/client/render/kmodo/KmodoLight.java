package com.norwood.komodo.client.render.kmodo;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;

import com.norwood.komodo.mixin.LightTextureAccessor;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

public final class KmodoLight {

    private KmodoLight() {}

    private static final int LIGHTMAP_SIZE = 16;

    private static DynamicTexture worldLightTexture;

    public static int worldLightLightmap(int packedLight) {
        if (worldLightTexture == null) {
            worldLightTexture = new DynamicTexture(new NativeImage(LIGHTMAP_SIZE, LIGHTMAP_SIZE, false));
        }
        int color = 0xFFFFFFFF;
        NativeImage pixels = ((LightTextureAccessor) Minecraft.getInstance().gameRenderer.lightTexture())
                .komodo$getLightPixels();
        if (pixels != null) {
            color = pixels.getPixelRGBA(LightTexture.block(packedLight), LightTexture.sky(packedLight));
        }
        NativeImage image = worldLightTexture.getPixels();
        for (int y = 0; y < LIGHTMAP_SIZE; y++) {
            for (int x = 0; x < LIGHTMAP_SIZE; x++) {
                image.setPixelRGBA(x, y, color);
            }
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, worldLightTexture.getId());
        image.upload(0, 0, 0, false);
        return worldLightTexture.getId();
    }

    public static void finishRawDraw() {
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        BufferUploader.invalidate();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
    }
}
