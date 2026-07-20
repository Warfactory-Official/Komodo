package com.norwood.komodo.mixin;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;


@Mixin(value = AnimationProcessor.class, remap = false)
public abstract class KmodoAnimResetMixin {

    @Unique
    private final Set<GeoBone> komodo$animatedBones =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private boolean komodo$reduceReset;

    @Inject(method = "tickAnimation", at = @At("HEAD"))
    private void komodo$gateReset(GeoAnimatable animatable, GeoModel<?> model, AnimatableManager<?> manager,
                                  double animTime, AnimationState<?> state, boolean crashWhenCantFindBone,
                                  CallbackInfo ci) {
        this.komodo$reduceReset = animatable instanceof GeoVehicleEntity;
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/cache/object/GeoBone;markRotationAsChanged()V"))
    private void komodo$trackRotation(GeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markRotationAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/cache/object/GeoBone;markPositionAsChanged()V"))
    private void komodo$trackPosition(GeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markPositionAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/cache/object/GeoBone;markScaleAsChanged()V"))
    private void komodo$trackScale(GeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markScaleAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/animation/AnimationProcessor;getRegisteredBones()Ljava/util/Collection;"))
    private Collection<GeoBone> komodo$settleOnlyAnimatedBones(AnimationProcessor<?> self) {
        return this.komodo$reduceReset ? this.komodo$animatedBones : self.getRegisteredBones();
    }

    @Inject(method = "setActiveModel", at = @At("HEAD"))
    private void komodo$clearOnModelSwap(CallbackInfo ci) {
        this.komodo$animatedBones.clear();
    }
}
