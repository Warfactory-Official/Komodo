package com.norwood.komodo.mixin;

import java.util.IdentityHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.keyframe.BoneAnimationQueue;


@Mixin(value = AnimationController.class, remap = false)
public abstract class KmodoAnimQueuePoolMixin {

    @Unique
    private final Map<CoreGeoBone, BoneAnimationQueue> komodo$queuePool = new IdentityHashMap<>();

    @Redirect(
            method = "createInitialQueues",
            at = @At(value = "NEW",
                    target = "(Lsoftware/bernie/geckolib/core/animatable/model/CoreGeoBone;)Lsoftware/bernie/geckolib/core/keyframe/BoneAnimationQueue;"))
    private BoneAnimationQueue komodo$reuseQueue(CoreGeoBone bone) {
        BoneAnimationQueue queue = this.komodo$queuePool.get(bone);
        if (queue == null) {
            queue = new BoneAnimationQueue(bone);
            this.komodo$queuePool.put(bone, queue);
            return queue;
        }
        queue.rotationXQueue().clear();
        queue.rotationYQueue().clear();
        queue.rotationZQueue().clear();
        queue.positionXQueue().clear();
        queue.positionYQueue().clear();
        queue.positionZQueue().clear();
        queue.scaleXQueue().clear();
        queue.scaleYQueue().clear();
        queue.scaleZQueue().clear();
        return queue;
    }
}
