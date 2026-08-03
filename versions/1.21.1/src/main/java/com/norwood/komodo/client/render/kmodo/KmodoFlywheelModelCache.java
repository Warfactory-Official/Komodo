package com.norwood.komodo.client.render.kmodo;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import com.atsuishio.superbwarfare.client.model.entity.VehicleModel;
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
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.util.RenderUtil;

public final class KmodoFlywheelModelCache {

    private KmodoFlywheelModelCache() {}

    private static final Set<String> DYNAMIC_PATTERNS = Set.of(
            "wheel", "track", "turret", "barrel", "cannon", "gun", "muzzle", "recoil", "rotor", "prop", "blade",
            "mantlet", "elevation", "traverse", "hatch", "rudder", "elevator", "aileron", "flap", "steer",
            "suspension", "radar", "antenna", "launcher", "missile", "gear", "swivel", "dish");


    private static final Set<String> STRUCTURAL_BONES = Set.of("root", "base");

    private static final int BAKE_LIGHT = 0;

    private static final Map<String, ModelState> STATES = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, Object> LOCKS = new ConcurrentHashMap<>();
    // Reflective collectTransform lookup, cached per model class (Optional.empty() = no such method). Only
    // populated during a model bake, which is itself one-time-per-model (STATES cache), so this never runs
    // on a render frame; the cache just collapses repeat lookups across skins/textures of the same model.
    private static final Map<Class<?>, Optional<Method>> COLLECT_TRANSFORM_CACHE = new ConcurrentHashMap<>();

    public static final class VehicleModels {
        public final Model body;
        public final Model hull;
        public final GeoBone hullBone;
        public final Map<String, Model> dynamicBones;

        VehicleModels(Model body, Model hull, GeoBone hullBone, Map<String, Model> dynamicBones) {
            this.body = body;
            this.hull = hull;
            this.hullBone = hullBone;
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

    private static boolean isDynamicFor(String boneName, boolean lodModel, TransformProbe probe) {
        if (boneName == null) {
            return false;
        }
        if (lodModel && boneName.toLowerCase(Locale.ROOT).contains("track")) {
            return false;
        }
        if (isDynamic(boneName)) {
            return true;
        }
        return isModelDynamic(probe, boneName);
    }

    private static boolean isModelDynamic(TransformProbe probe, String boneName) {
        if (probe == null || STRUCTURAL_BONES.contains(boneName)) {
            return false;
        }
        return probe.transforms(boneName);
    }

    // Ground truth for "this bone moves each tick" is the model's own collectTransform. Abstracted so it
    // works for both SBW's VehicleModel and third-party clones (e.g. AshVehicle) that copy the class
    // instead of extending it.
    @FunctionalInterface
    private interface TransformProbe {
        boolean transforms(String boneName);
    }

    private static TransformProbe transformProbe(GeoRenderer<?> renderer) {
        GeoModel<?> model;
        try {
            model = renderer.getGeoModel();
        } catch (Throwable t) {
            return null;
        }
        if (model == null) {
            return null;
        }
        if (model instanceof VehicleModel<?> vm) {
            return name -> {
                try {
                    return vm.collectTransform(name) != null;
                } catch (Throwable t) {
                    return false;
                }
            };
        }
        // AshVehicle (and similar) ship their OWN VehicleModel<T> that extends GeoModel directly instead of
        // SBW's VehicleModel, so the instanceof above misses it — every Ash bone would fall back to keyword
        // matching, freezing collectTransform-driven bones whose names match no pattern (rotors like "VINT",
        // "Tyre", control surfaces, "bone2", ...). These clones still expose the same public
        // collectTransform(String); invoke it reflectively so their code-driven bones classify dynamic too.
        final GeoModel<?> geoModel = model;
        final Method collect = collectTransformMethod(model.getClass());
        if (collect == null) {
            return null;
        }
        return name -> {
            try {
                return collect.invoke(geoModel, name) != null;
            } catch (Throwable t) {
                return false;
            }
        };
    }

    private static Method collectTransformMethod(Class<?> cls) {
        return COLLECT_TRANSFORM_CACHE.computeIfAbsent(cls, c -> {
            try {
                Method m = c.getMethod("collectTransform", String.class);
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                return Optional.of(m);
            } catch (Throwable t) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static boolean isLodModel(ResourceLocation res) {
        return res != null && res.getPath().contains("_lod");
    }

    public static VehicleModels getModels(GeoRenderer<?> renderer, GeoVehicleEntity entity) {
        ResourceLocation res = modelRes(renderer, entity);
        if (res == null) {
            return null;
        }
        ResourceLocation texture = texture(renderer, entity);
        if (texture == null) {
            return null;
        }
        String key = key(res, texture);
        ModelState state = STATES.get(key);
        if (state == null) {
            if (!RenderSystem.isOnRenderThread()) {
                return null;
            }
            state = new ModelState();
            STATES.put(key, state);
            BakedGeoModel baked = bakedModel(renderer, res);
            if (baked == null || baked.topLevelBones().isEmpty()) {
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
            ResourceLocation texture = texture(renderer, vehicle);
            ModelState state = (res == null || texture == null) ? null : STATES.get(key(res, texture));
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

            ByteBufferBuilder bodyAlloc = new ByteBufferBuilder(4096);
            BufferBuilder body = new BufferBuilder(bodyAlloc, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            ByteBufferBuilder hullAlloc = new ByteBufferBuilder(4096);
            BufferBuilder hull = new BufferBuilder(hullAlloc, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            Map<String, Model> dynamicBones = new HashMap<>();

            Map<String, Integer> dynamicBoneVertCounts = new HashMap<>();
            boolean[] anyBody = {false};
            boolean[] anyHull = {false};
            GeoBone[] hullBoneRef = {null};

            boolean lodModel = isLodModel(res);

            Map<ByteBuffer, Model> meshDedup = new HashMap<>();
            TransformProbe probe = transformProbe(renderer);
            PoseStack pose = new PoseStack();
            for (GeoBone top : baked.topLevelBones()) {
                bakeWalk(renderer, pose, top, false, true, false, body, hull, dynamicBones, material, state.blocks,
                        anyBody, anyHull, hullBoneRef, dynamicBoneVertCounts, lodModel, meshDedup, probe);
            }

            Model bodyModel = null;
            int bodyVertices = 0;
            if (anyBody[0]) {
                MeshData rendered = body.build();
                if (rendered != null) {
                    bodyVertices = rendered.drawState().vertexCount();
                    bodyModel = toModel(rendered, material, "body", state.blocks);
                    rendered.close();
                }
            }
            bodyAlloc.close();

            Model hullModel = null;
            if (anyHull[0]) {
                MeshData rendered = hull.build();
                if (rendered != null) {
                    bodyVertices += rendered.drawState().vertexCount();
                    hullModel = toModel(rendered, material, "hull", state.blocks);
                    rendered.close();
                }
            }
            hullAlloc.close();

            state.models = new VehicleModels(bodyModel, hullModel, hullBoneRef[0], dynamicBones);
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
                                 boolean topLevel, boolean underHull, BufferBuilder body, BufferBuilder hull,
                                 Map<String, Model> dynamicBones, Material material,
                                 List<MemoryBlock> blocks, boolean[] anyBody, boolean[] anyHull,
                                 GeoBone[] hullBoneRef, Map<String, Integer> dynamicBoneVertCounts, boolean lodModel,
                                 Map<ByteBuffer, Model> meshDedup, TransformProbe probe) {
        boolean dynamic = dynamicAncestor || isDynamicFor(bone.getName(), lodModel, probe);
        // A non-top-level structural bone (e.g. "base") roots a runtime-hideable static subtree: SBW toggles its
        // isHidden to simulate a firing-port "window". Its static cubes go to the separate `hull` mesh so they can
        // be collapsed independently, while sibling top-level statics (e.g. "firePort") stay in `body`. GeckoLib's
        // setHidden also hides children, so the whole subtree collapses as a unit.
        boolean hullRoot = !topLevel && bone.getName() != null && STRUCTURAL_BONES.contains(bone.getName());
        boolean hullPart = underHull || hullRoot;
        if (hullRoot && hullBoneRef[0] == null) {
            hullBoneRef[0] = bone;
        }
        boolean drawable = bone.getName() != null && !bone.getName().endsWith("_dogTag")
                && !bone.isHidden() && !bone.getCubes().isEmpty();

        pose.pushPose();
        RenderUtil.prepMatrixForBone(pose, bone);

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
            if (hullPart) {
                renderer.renderCubesOfBone(pose, bone, hull, BAKE_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                anyHull[0] = true;
            } else {
                renderer.renderCubesOfBone(pose, bone, body, BAKE_LIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
                anyBody[0] = true;
            }
        }

        for (GeoBone child : bone.getChildBones()) {
            bakeWalk(renderer, pose, child, dynamic, false, hullPart, body, hull, dynamicBones, material, blocks,
                    anyBody, anyHull, hullBoneRef, dynamicBoneVertCounts, lodModel, meshDedup, probe);
        }
        pose.popPose();
    }

    private static Model bakeBoneLocal(GeoRenderer<?> renderer, GeoBone bone, Material material,
                                       List<MemoryBlock> blocks, int[] vertCountOut,
                                       Map<ByteBuffer, Model> meshDedup) {
        ByteBufferBuilder alloc = new ByteBufferBuilder(512);
        try {
            BufferBuilder builder = new BufferBuilder(alloc, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
            renderer.renderCubesOfBone(new PoseStack(), bone, builder, BAKE_LIGHT,
                    OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
            MeshData rendered = builder.build();
            if (rendered == null) {
                return null;
            }
            if (vertCountOut != null) {
                vertCountOut[0] = rendered.drawState().vertexCount();
            }
            ByteBuffer signature = meshSignature(rendered);
            Model shared = signature == null ? null : meshDedup.get(signature);
            if (shared != null) {
                rendered.close();
                return shared;
            }
            Model model = toModel(rendered, material, bone.getName(), blocks);
            rendered.close();
            if (model != null && signature != null) {
                meshDedup.put(signature, model);
            }
            return model;
        } catch (Throwable t) {
            return null;
        } finally {
            alloc.close();
        }
    }

    private static ByteBuffer meshSignature(MeshData rendered) {
        MeshData.DrawState draw = rendered.drawState();
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

    private static Model toModel(MeshData rendered, Material material, String name,
                                 List<MemoryBlock> blocks) {
        MeshData.DrawState draw = rendered.drawState();
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

    private static String key(ResourceLocation res, ResourceLocation texture) {
        return res.toString() + ' ' + texture.toString();
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
