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

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animatable.model.CoreGeoModel;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.core.animation.AnimationState;


@Mixin(value = AnimationProcessor.class, remap = false)
public abstract class KmodoAnimResetMixin {

    @Unique
    private final Set<CoreGeoBone> komodo$animatedBones =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @Unique
    private boolean komodo$reduceReset;

    @Inject(method = "tickAnimation", at = @At("HEAD"))
    private void komodo$gateReset(GeoAnimatable animatable, CoreGeoModel<?> model, AnimatableManager<?> manager,
                                  double animTime, AnimationState<?> state, boolean crashWhenCantFindBone,
                                  CallbackInfo ci) {
        this.komodo$reduceReset = animatable instanceof GeoVehicleEntity;
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/core/animatable/model/CoreGeoBone;markRotationAsChanged()V"))
    private void komodo$trackRotation(CoreGeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markRotationAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/core/animatable/model/CoreGeoBone;markPositionAsChanged()V"))
    private void komodo$trackPosition(CoreGeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markPositionAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/core/animatable/model/CoreGeoBone;markScaleAsChanged()V"))
    private void komodo$trackScale(CoreGeoBone bone) {
        this.komodo$animatedBones.add(bone);
        bone.markScaleAsChanged();
    }

    @Redirect(
            method = "tickAnimation",
            at = @At(value = "INVOKE",
                    target = "Lsoftware/bernie/geckolib/core/animation/AnimationProcessor;getRegisteredBones()Ljava/util/Collection;"))
    private Collection<CoreGeoBone> komodo$settleOnlyAnimatedBones(AnimationProcessor<?> self) {
        return this.komodo$reduceReset ? this.komodo$animatedBones : self.getRegisteredBones();
    }

    @Inject(method = "setActiveModel", at = @At("HEAD"))
    private void komodo$clearOnModelSwap(CallbackInfo ci) {
        this.komodo$animatedBones.clear();
    }
}
