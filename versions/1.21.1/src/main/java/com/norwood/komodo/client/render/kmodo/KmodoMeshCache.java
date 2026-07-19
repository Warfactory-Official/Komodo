package com.norwood.komodo.client.render.kmodo;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.Util;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.norwood.komodo.Komodo;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

public final class KmodoMeshCache {

    private KmodoMeshCache() {}

    private static final Map<ResourceLocation, ModelState> STATES = new ConcurrentHashMap<>();

    private static final class BakedMesh {
        final MeshData mesh;
        final ByteBufferBuilder arena;

        BakedMesh(MeshData mesh, ByteBufferBuilder arena) {
            this.mesh = mesh;
            this.arena = arena;
        }
    }

    private static final class ModelState {
        static final int BAKING = 0;
        static final int BUILT = 1;
        static final int READY = 2;
        static final int FAILED = 3;

        volatile int status = BAKING;
        volatile Map<String, BakedMesh> pending;
        Map<String, VertexBuffer> vbos;

        volatile Map<String, Integer> pendingVertCounts;

        ResourceLocation res;
    }

    public static VertexBuffer getBone(GeoRenderer<?> renderer, VehicleEntity entity, String boneName) {
        ResourceLocation res = modelRes(renderer, entity);
        if (res == null) {
            return null;
        }
        ModelState state = STATES.get(res);
        if (state == null) {
            state = new ModelState();
            state.res = res;
            STATES.put(res, state);
            BakedGeoModel baked = bakedModel(renderer, res);
            if (baked == null || baked.topLevelBones().isEmpty()) {
                state.status = ModelState.FAILED;
                return null;
            }
            final ModelState st = state;
            final BakedGeoModel model = baked;
            final GeoRenderer<?> geoRenderer = renderer;
            Util.backgroundExecutor().execute(() -> buildAsync(res, st, model, geoRenderer));
            return null;
        }
        if (state.status == ModelState.BUILT) {
            uploadPending(state);
        }
        if (state.status == ModelState.READY) {
            return state.vbos.get(boneName);
        }
        return null;
    }

    private static void buildAsync(ResourceLocation res, ModelState state, BakedGeoModel baked,
                                   GeoRenderer<?> renderer) {
        try {
            Map<String, BakedMesh> out = new HashMap<>();
            Map<String, Integer> vertCounts = KmodoDebug.enabled() ? new HashMap<>() : null;
            for (GeoBone top : baked.topLevelBones()) {
                buildBoneRec(renderer, top, out, vertCounts);
            }
            state.pending = out;
            state.pendingVertCounts = vertCounts;
            state.status = ModelState.BUILT;
        } catch (Throwable t) {
            Komodo.LOGGER.warn("[komodo] Kmodo async mesh bake failed for {}", res, t);
            state.status = ModelState.FAILED;
        }
    }

    private static void buildBoneRec(GeoRenderer<?> renderer, GeoBone bone,
                                     Map<String, BakedMesh> out,
                                     Map<String, Integer> vertCountsOut) {
        String name = bone.getName();

        if (name != null && !name.endsWith("_dogTag") && !bone.isHidden() && !bone.getCubes().isEmpty()) {
            ByteBufferBuilder arena = new ByteBufferBuilder(512);
            BufferBuilder builder = new BufferBuilder(arena, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);

            renderer.renderCubesOfBone(new PoseStack(), bone, builder, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            MeshData mesh = builder.build();
            if (mesh == null) {
                arena.close();
            } else {
                if (vertCountsOut != null) {
                    vertCountsOut.put(name, mesh.drawState().vertexCount());
                }
                out.put(name, new BakedMesh(mesh, arena));
            }
        }
        for (GeoBone child : bone.getChildBones()) {
            buildBoneRec(renderer, child, out, vertCountsOut);
        }
    }

    private static void uploadPending(ModelState state) {
        Map<String, BakedMesh> pending = state.pending;
        Map<String, VertexBuffer> vbos = new HashMap<>();
        if (pending != null) {
            for (Map.Entry<String, BakedMesh> entry : pending.entrySet()) {
                BakedMesh baked = entry.getValue();
                VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                vbo.bind();
                vbo.upload(baked.mesh);
                vbos.put(entry.getKey(), vbo);
                baked.mesh.close();
                baked.arena.close();
            }
            VertexBuffer.unbind();
        }
        state.vbos = vbos;

        if (KmodoDebug.enabled() && state.res != null) {
            Map<String, Integer> vertCounts = state.pendingVertCounts;
            int totalVerts = vertCounts != null
                    ? vertCounts.values().stream().mapToInt(Integer::intValue).sum() : 0;
            KmodoDebug.onRetainedBaked(state.res, vbos.size(), totalVerts);
        }

        state.pending = null;
        state.pendingVertCounts = null;
        state.status = ModelState.READY;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation modelRes(GeoRenderer<?> renderer, VehicleEntity entity) {
        try {
            GeoModel model = renderer.getGeoModel();
            return model.getModelResource((GeoAnimatable) entity);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BakedGeoModel bakedModel(GeoRenderer<?> renderer, ResourceLocation res) {
        try {
            GeoModel model = renderer.getGeoModel();
            return model.getBakedModel(res);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void invalidateAll() {
        for (ModelState state : STATES.values()) {
            if (state.vbos != null) {
                state.vbos.values().forEach(VertexBuffer::close);
            }
        }
        STATES.clear();
    }
}
