package com.norwood.komodo.client.render.kmodo;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;

import org.joml.Matrix4f;

public final class KmodoGarage {

    private KmodoGarage() {}

    private static final Map<ResourceLocation, KmodoGaragePool> POOLS = new HashMap<>();
    private static Vec3i bufferOrigin = Vec3i.ZERO;
    private static int originGen;

    public static Vec3i bufferOrigin() {
        return bufferOrigin;
    }

    public static int originGen() {
        return originGen;
    }

    public static boolean contains(int entityId, ResourceLocation res) {
        KmodoGaragePool pool = POOLS.get(res);
        return pool != null && pool.contains(entityId);
    }

    public static void onDormant(int entityId, ResourceLocation res, ResourceLocation texture, int slotVerts,
                                 ByteBuffer sliceVerts, int packedLight) {
        KmodoGaragePool pool = POOLS.get(res);
        if (pool == null) {
            pool = KmodoGaragePool.create(res, texture, slotVerts);
            if (pool == null) {
                return;
            }
            POOLS.put(res, pool);
        }
        if (pool.slotVerts() != slotVerts) {
            return;
        }
        pool.alloc(entityId, sliceVerts, packedLight);
    }

    public static void onWake(int entityId, ResourceLocation res) {
        KmodoGaragePool pool = POOLS.get(res);
        if (pool != null) {
            pool.free(entityId);
        }
    }

    public static void relight(int entityId, ResourceLocation res, int packedLight, ByteBuffer sliceVerts) {
        KmodoGaragePool pool = POOLS.get(res);
        if (pool != null) {
            pool.relight(entityId, packedLight, sliceVerts);
        }
    }

    public static void syncOrigin(Vec3i newOrigin) {
        if (newOrigin.equals(bufferOrigin)) {
            return;
        }
        for (KmodoGaragePool pool : POOLS.values()) {
            pool.delete();
        }
        POOLS.clear();
        bufferOrigin = newOrigin;
        originGen++;
    }

    public static void freeMissing(it.unimi.dsi.fastutil.ints.IntSet liveIds) {
        for (KmodoGaragePool pool : POOLS.values()) {
            pool.freeMissing(liveIds);
        }
    }

    public static void compactStep() {
        final boolean prof = KmodoProfiler.enabled();
        long t0 = prof ? System.nanoTime() : 0L;
        for (KmodoGaragePool pool : POOLS.values()) {
            pool.compactStep();
        }
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.GARAGE_COMPACT, System.nanoTime() - t0);
        }
    }

    public static void drawAll(Matrix4f cameraView, Matrix4f projection, double camX, double camY, double camZ) {
        if (POOLS.isEmpty()) {
            return;
        }
        boolean any = false;
        for (KmodoGaragePool pool : POOLS.values()) {
            if (pool.highWater() > 0) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }

        final boolean prof = KmodoProfiler.enabled();
        long drawStart = prof ? System.nanoTime() : 0L;
        if (prof) {
            KmodoGpuTimer.begin();
        }
        ResourceLocation setupTex = MissingTextureAtlasSprite.getLocation();
        RenderType renderType = RenderType.entityCutoutNoCull(setupTex);
        renderType.setupRenderState();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        ShaderInstance shader = RenderSystem.getShader();
        try {
            float dx = (float) (bufferOrigin.getX() - camX);
            float dy = (float) (bufferOrigin.getY() - camY);
            float dz = (float) (bufferOrigin.getZ() - camZ);
            Matrix4f modelView = new Matrix4f(cameraView).translate(dx, dy, dz);
            for (KmodoGaragePool pool : POOLS.values()) {
                if (pool.highWater() == 0) {
                    continue;
                }
                RenderSystem.setShaderTexture(0, pool.texture());
                pool.draw(shader, modelView, projection);
            }
        } finally {
            renderType.clearRenderState();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            KmodoLight.finishRawDraw();
            if (prof) {
                KmodoGpuTimer.end();
                KmodoProfiler.addPhase(KmodoProfiler.Phase.GARAGE_DRAW, System.nanoTime() - drawStart);
            }
        }
    }

    public static int poolCount() {
        return POOLS.size();
    }

    public static int liveSlices() {
        int total = 0;
        for (KmodoGaragePool pool : POOLS.values()) {
            total += pool.liveCount();
        }
        return total;
    }

    public static int holes() {
        int total = 0;
        for (KmodoGaragePool pool : POOLS.values()) {
            total += pool.holes();
        }
        return total;
    }

    public static long gpuBytes() {
        long total = 0L;
        for (KmodoGaragePool pool : POOLS.values()) {
            total += pool.gpuBytes();
        }
        return total;
    }

    public static void invalidateAll() {
        for (KmodoGaragePool pool : POOLS.values()) {
            pool.delete();
        }
        POOLS.clear();
        bufferOrigin = Vec3i.ZERO;
        KmodoGpuTimer.dispose();
    }
}
