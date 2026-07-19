package com.norwood.komodo.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

@Mixin(value = GeoEntityRenderer.class, remap = false)
public interface GeoEntityRendererAccessor {

    @Accessor("scaleWidth")
    float komodo$getScaleWidth();

    @Accessor("scaleHeight")
    float komodo$getScaleHeight();
}
