package com.norwood.komodo.client.render.kmodo;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.norwood.komodo.Komodo;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

public final class KmodoAccumulator {

    private KmodoAccumulator() {}

    private static final List<VertexBuffer> BUFFERS = new ArrayList<>();
    private static final List<Matrix4f> MATRICES = new ArrayList<>();
    private static volatile boolean logged;

    private static ResourceLocation currentRes;

    public static boolean tryRecord(GeoRenderer<?> renderer, Entity animatable, PoseStack pose, GeoBone bone) {
        if (!(animatable instanceof VehicleEntity vehicle)) {
            return false;
        }
        if (!KmodoConfig.retainEnabled() || !KmodoConfig.rawDrawAllowed()) {
            return false;
        }
        VertexBuffer vbo = KmodoMeshCache.getBone(renderer, vehicle, bone.getName());
        if (vbo == null) {
            return false;
        }
        BUFFERS.add(vbo);
        MATRICES.add(new Matrix4f(pose.last().pose()));

        if (KmodoDebug.enabled() && currentRes == null && animatable instanceof GeoAnimatable) {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                GeoModel model = ((GeoRenderer) renderer).getGeoModel();
                currentRes = model.getModelResource((GeoAnimatable) animatable);
            } catch (Throwable ignored) {}
        }
        return true;
    }

    public static boolean isEmpty() {
        return BUFFERS.isEmpty();
    }

    public static void clear() {
        BUFFERS.clear();
        MATRICES.clear();
        currentRes = null;
    }

    public static void flush(ResourceLocation texture, Level level, int packedLight) {
        if (BUFFERS.isEmpty()) {
            return;
        }
        if (!logged) {
            logged = true;
            Komodo.LOGGER.info("[komodo] Kmodo Accelerator engaged — retained vehicle rendering "
                    + "({} bone buffers on first vehicle)", BUFFERS.size());
        }

        if (KmodoDebug.enabled()) {
            KmodoDebug.onRetainedFlush(currentRes);
        }
        try {
            KmodoRenderer.drawBatch(BUFFERS, MATRICES, texture, level, packedLight);
        } catch (Throwable t) {
            Komodo.LOGGER.warn("[komodo] Kmodo retained vehicle draw failed", t);
        } finally {
            clear();
        }
    }
}
