package com.norwood.komodo.mixin;

import net.minecraft.client.renderer.LightTexture;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface LightTextureAccessor {

    @Accessor("lightPixels")
    NativeImage komodo$getLightPixels();
}
