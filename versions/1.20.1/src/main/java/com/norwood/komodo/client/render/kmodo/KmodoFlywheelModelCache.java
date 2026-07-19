package com.norwood.komodo.client.render.kmodo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.norwood.komodo.Komodo;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.util.RenderUtils;

public final class KmodoFlywheelModelCache {

    private KmodoFlywheelModelCache() {}

    private static final Set<String> DYNAMIC_PATTERNS = Set.of(
            "wheel", "track", "turret", "barrel", "cannon", "gun", "muzzle", "recoil", "rotor", "prop", "blade",
            "mantlet", "elevation", "traverse", "hatch", "rudder", "elevator", "aileron", "flap", "steer",
            "suspension", "radar", "antenna", "launcher", "missile", "gear", "swivel", "dish");

    private static final int BAKE_LIGHT = 0;

    private static final Map<ResourceLocation, ModelState> STATES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Object> LOCKS = new ConcurrentHashMap<>();

    public static final class VehicleModels {
        public final Model body;
        public final Map<String, Model> dynamicBones;

        VehicleModels(Model body, Map<String, Model> dynamicBones) {
            this.body = body;
            this.dynamicBones = dynamicBones;
        }
    }

    private static final class ModelState {
        static final int BAKING = 0;
        static final int READY = 1;
        static final int FAILED = 2;

        volatile int status = BAKING;
        volatile VehicleModels models;
        final List<MemoryBlock> blocks = new ArrayList<>();
    }

    public static Object lockFor(ResourceLocation res) {
        return LOCKS.computeIfAbsent(res, k -> new Object());
    }

    private static boolean isDynamic(String boneName) {
        if (boneName == null) {
            return false;
        }
        String name = boneName.toLowerCase(Locale.ROOT);
        for (String pattern : DYNAMIC_PATTERNS) {
            if (name.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDynamicFor(String boneName, boolean lodModel) {
        if (lodModel && boneName != null && boneName.toLowerCase(Locale.ROOT).contains("track")) {
            return false;
        }
        return isDynamic(boneName);
    }

    private static boolean isLodModel(ResourceLocation res) {
        return res != null && res.getPath().contains("_lod");
    }

    public static VehicleModels getModels(GeoRenderer<?> renderer, GeoVehicleEntity entity) {
        ResourceLocation res = modelRes(renderer, entity);
        if (res == null) {
            return null;
        }
        ModelState state = STATES.get(res);
        if (state == null) {
            if (!RenderSystem.isOnRenderThread()) {
                return null;
            }
            state = new ModelState();
            STATES.put(res, state);
            BakedGeoModel baked = bakedModel(renderer, res);
            ResourceLocation texture = texture(renderer, entity);
            if (baked == null || baked.topLevelBones().isEmpty() || texture == null) {
                state.status = ModelState.FAILED;
                return null;
            }
            buildModels(res, state, baked, renderer, texture);
        }
        return state.status == ModelState.READY ? state.models : null;
    }

    public static boolean isReady(Entity entity) {
        try {
            EntityRenderer<?> er = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            if (!(er instanceof GeoRenderer<?> renderer) || !(entity instanceof GeoVehicleEntity vehicle)) {
                return false;
            }
            ResourceLocation res = modelRes(renderer, vehicle);
            ModelState state = res == null ? null : STATES.get(res);
            return state != null && state.status == ModelState.READY;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void buildModels(ResourceLocation res, ModelState state, BakedGeoModel baked,
                                    GeoRenderer<?> renderer, ResourceLocation texture) {
        try {
            Material material = new SimpleMaterial.Builder().copyFrom(Materials.CUTOUT_MIPPED_BLOCK)
                    .cardinalLightingMode(CardinalLightingMode.ENTITY)
                    .diffuse(true)
                    .texture(texture).build();

            BufferBuilder body = new BufferBuilder(4096);
            body.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            Map<String, Model> dynamicBones = new HashMap<>();

            Map<String, Integer> dynamicBoneVertCounts = new HashMap<>();
            boolean[] anyBody = {false};

            boolean lodModel = isLodModel(res);

            Map<ByteBuffer, Model> meshDedup = new HashMap<>();
            PoseStack pose = new PoseStack();
            for (GeoBone top : baked.topLevelBones()) {
                bakeWalk(renderer, pose, top, false, body, dynamicBones, material, state.blocks, anyBody,
                        dynamicBoneVertCounts, lodModel, meshDedup);
            }

            Model bodyModel = null;
            int bodyVertices = 0;
            if (anyBody[0]) {
                BufferBuilder.RenderedBuffer rendered = body.end();
                bodyVertices = rendered.drawState().vertexCount();
                bodyModel = toModel(rendered, material, "body", state.blocks);
                rendered.release();
            }

            state.models = new VehicleModels(bodyModel, dynamicBones);
            state.status = ModelState.READY;

            if (KmodoDebug.enabled()) {
                int dynVerts = dynamicBoneVertCounts.values().stream().mapToInt(Integer::intValue).sum();
                long gpuBytes = state.blocks.stream().mapToLong(dev.engine_room.flywheel.lib.memory.MemoryBlock::size).sum();
                KmodoDebug.onFlywheelBaked(res, bodyVertices, dynamicBones.size(), dynVerts, gpuBytes);
                Komodo.LOGGER.info("[Kmodo] {} baked: {} dynamic bones -> {} unique meshes/instancers{}",
                        res, dynamicBones.size(), meshDedup.size(), lodModel ? " (LOD, tracks baked)" : "");
            }
        } catch (Throwable t) {
            Komodo.LOGGER.warn("[komodo] Kmodo Flywheel model bake failed for {}", res, t);
            state.status = ModelState.FAILED;
        }
    }

    private static void bakeWalk(GeoRenderer<?> renderer, PoseStack pose, GeoBone bone, boolean dynamicAncestor,
                                 BufferBuilder body, Map<String, Model> dynamicBones, Material material,
                                 List<MemoryBlock> blocks, boolean[] anyBody,
                                 Map<String, Integer> dynamicBoneVertCounts, boolean lodModel,
                                 Map<ByteBuffer, Model> meshDedup) {
        boolean dynamic = dynamicAncestor || isDynamicFor(bone.getName(), lodModel);
        boolean drawable = bone.getName() != null && !bone.getName().endsWith("_dogTag")
                && !bone.isHidden() && !bone.getCubes().isEmpty();

        pose.pushPose();
        RenderUtils.prepMatrixForBone(pose, bone);

        if (dynamic) {
            if (drawable) {
                int[] vertCount = {0};
                Model model = bakeBoneLocal(renderer, bone, material, blocks, vertCount, meshDedup);
                if (model != null) {
                    dynamicBones.put(bone.getName(), model);
                    if (KmodoDebug.enabled()) {
                        dynamicBoneVertCounts.put(bone.getName(), vertCount[0]);
                    }
                }
            }
        } else if (drawable) {

            renderer.renderCubesOfBone(pose, bone, body, BAKE_LIGHT, OverlayTexture.NO_OVERLAY,
                    1f, 1f, 1f, 1f);
            anyBody[0] = true;
        }

        for (GeoBone child : bone.getChildBones()) {
            bakeWalk(renderer, pose, child, dynamic, body, dynamicBones, material, blocks, anyBody,
                    dynamicBoneVertCounts, lodModel, meshDedup);
        }
        pose.popPose();
    }

    private static Model bakeBoneLocal(GeoRenderer<?> renderer, GeoBone bone, Material material,
                                       List<MemoryBlock> blocks, int[] vertCountOut,
                                       Map<ByteBuffer, Model> meshDedup) {
        try {
            BufferBuilder builder = new BufferBuilder(512);
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            renderer.renderCubesOfBone(new PoseStack(), bone, builder, BAKE_LIGHT,
                    OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
            BufferBuilder.RenderedBuffer rendered = builder.end();
            if (vertCountOut != null) {
                vertCountOut[0] = rendered.drawState().vertexCount();
            }
            ByteBuffer signature = meshSignature(rendered);
            Model shared = signature == null ? null : meshDedup.get(signature);
            if (shared != null) {

                rendered.release();
                return shared;
            }
            Model model = toModel(rendered, material, bone.getName(), blocks);
            rendered.release();
            if (model != null && signature != null) {
                meshDedup.put(signature, model);
            }
            return model;
        } catch (Throwable t) {
            return null;
        }
    }

    private static ByteBuffer meshSignature(BufferBuilder.RenderedBuffer rendered) {
        BufferBuilder.DrawState draw = rendered.drawState();
        int count = draw.vertexCount();
        if (count == 0) {
            return null;
        }
        int stride = draw.format().getVertexSize();
        ByteBuffer src = rendered.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int origin = src.position();
        int total = count * stride;
        ByteBuffer sig = ByteBuffer.allocate(total);
        for (int i = 0; i < total; i++) {
            sig.put(src.get(origin + i));
        }
        sig.flip();
        return sig;
    }

    private static Model toModel(BufferBuilder.RenderedBuffer rendered, Material material, String name,
                                 List<MemoryBlock> blocks) {
        BufferBuilder.DrawState draw = rendered.drawState();
        int count = draw.vertexCount();
        if (count == 0) {
            return null;
        }
        int stride = draw.format().getVertexSize();
        ByteBuffer bytes = rendered.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
        int origin = bytes.position();

        MemoryBlock memory = MemoryBlock.mallocTracked(FullVertexView.STRIDE * count);
        blocks.add(memory);
        FullVertexView view = new FullVertexView();
        view.ptr(memory.ptr());
        view.vertexCount(count);

        for (int i = 0; i < count; i++) {
            int base = origin + i * stride;

            view.x(i, bytes.getFloat(base));
            view.y(i, bytes.getFloat(base + 4));
            view.z(i, bytes.getFloat(base + 8));
            view.r(i, (bytes.get(base + 12) & 0xFF) / 255f);
            view.g(i, (bytes.get(base + 13) & 0xFF) / 255f);
            view.b(i, (bytes.get(base + 14) & 0xFF) / 255f);
            view.a(i, (bytes.get(base + 15) & 0xFF) / 255f);
            view.u(i, bytes.getFloat(base + 16));
            view.v(i, bytes.getFloat(base + 20));
            view.overlay(i, (bytes.getShort(base + 24) & 0xFFFF) | ((bytes.getShort(base + 26) & 0xFFFF) << 16));
            view.light(i, (bytes.getShort(base + 28) & 0xFFFF) | ((bytes.getShort(base + 30) & 0xFFFF) << 16));
            view.normalX(i, bytes.get(base + 32) / 127f);
            view.normalY(i, bytes.get(base + 33) / 127f);
            view.normalZ(i, bytes.get(base + 34) / 127f);
        }

        Mesh mesh = new SimpleQuadMesh(view, "komodo_vehicle:" + name);
        return new SingleMeshModel(mesh, material);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation modelRes(GeoRenderer<?> renderer, GeoVehicleEntity entity) {
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResourceLocation texture(GeoRenderer<?> renderer, GeoVehicleEntity entity) {
        try {
            return ((EntityRenderer) renderer).getTextureLocation(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void invalidateAll() {
        for (ModelState state : STATES.values()) {
            for (MemoryBlock block : state.blocks) {
                if (!block.isFreed()) {
                    block.free();
                }
            }
        }
        STATES.clear();
        LOCKS.clear();
    }
}
