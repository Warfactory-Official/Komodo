package com.norwood.komodo.client.render.kmodo;

import com.atsuishio.superbwarfare.client.renderer.entity.VehicleRenderer;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.norwood.komodo.client.render.kmodo.KmodoFlywheelModelCache.VehicleModels;
import com.norwood.komodo.mixin.GeoEntityRendererAccessor;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4fc;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.util.RenderUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"unchecked", "rawtypes"})
public class KmodoFlywheelVehicleVisual extends AbstractEntityVisual<GeoVehicleEntity> implements SimpleDynamicVisual {

    private static final long FNV_OFFSET = -3750763034362895579L;
    private static final long FNV_PRIME = 1099511628211L;

    private static final Matrix4f COLLAPSE = new Matrix4f().scaling(0.0f);

    private static final Map<Integer, KmodoFlywheelVehicleVisual> BY_ENTITY = new ConcurrentHashMap<>();
    private static final int LOD_DEBOUNCE_FRAMES = 15;
    private static final int RELIGHT_INTERVAL = 20;
    private static final double GARAGE_MOVE_EPS_SQ = 1.0e-4;
    private static final double GARAGE_POS_EPS = 1.0e-3;
    private static final float GARAGE_ROT_EPS = 0.05f;
    private static final float APPLY_POS_EPS = 1.0e-4f;
    private final GeoRenderer renderer;
    private final Map<String, TransformedInstance> dynamicInstances = new HashMap<>();
    private final KmodoDormancy dormancy = new KmodoDormancy();
    private TransformedInstance bodyInstance;
    private boolean instancesCreated;
    private ResourceLocation createdModelRes;
    private ResourceLocation createdTexture;
    private ResourceLocation pendingModelRes;
    private int pendingResFrames;
    private long poseHash;
    private Set<GeoBone> liveBones;
    private ResourceLocation liveRes;
    private volatile Map<String, Matrix4f> boneLocal;
    private volatile boolean dormantFlag;
    private volatile boolean hideBodyFlag;
    private volatile long poseStamp;
    private volatile float scaleW = 1.0f;
    private volatile float scaleH = 1.0f;
    private long appliedStamp = Long.MIN_VALUE;
    private float appliedX;
    private float appliedY;
    private float appliedZ;
    private boolean hasApplied;
    private boolean appliedHideBody;
    private volatile boolean pooled;
    private ResourceLocation pooledRes;
    private int pooledSlotVerts;
    private int pooledLight = Integer.MIN_VALUE;
    private int pooledOriginGen;
    private int lastRelightTick = Integer.MIN_VALUE;
    private float cullRadius;

    public KmodoFlywheelVehicleVisual(VisualizationContext ctx, GeoVehicleEntity entity, float partialTick) {
        super(ctx, entity, partialTick);
        EntityRenderer<?> er = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        this.renderer = (er instanceof GeoRenderer) ? (GeoRenderer) er : null;
        BY_ENTITY.put(entity.getId(), this);
    }

    static KmodoFlywheelVehicleVisual byEntity(int entityId) {
        return BY_ENTITY.get(entityId);
    }


    private static boolean markLive(GeoBone bone, Set<String> dynBones, Set<GeoBone> live) {
        boolean anyLive = bone.getName() != null && dynBones.contains(bone.getName());
        for (GeoBone child : bone.getChildBones()) {
            anyLive |= markLive(child, dynBones, live);
        }
        if (anyLive) {
            live.add(bone);
        }
        return anyLive;
    }

    public void renderThreadUpdate(float partialTick) {
        if (renderer == null || !KmodoConfig.flywheelEnabled() || !BackendManager.isBackendOn()) {
            return;
        }
        VehicleModels models = KmodoFlywheelModelCache.getModels(renderer, entity);
        if (models == null) {
            return;
        }
        ResourceLocation res = modelRes();
        if (res == null) {
            return;
        }
        final boolean prof = KmodoProfiler.enabled();
        long updateStart = prof ? System.nanoTime() : 0L;
        if (prof) {
            KmodoProfiler.countProcessed();
        }

        long dormStart = prof ? System.nanoTime() : 0L;
        boolean needsUpdate = dormancy.needsUpdate(entity, false);
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.DORMANCY, System.nanoTime() - dormStart);
            KmodoProfiler.countState(dormancy.state());
        }
        if (pooled) {
            boolean garageOk = KmodoConfig.garageEnabled() && KmodoConfig.rawDrawAllowed();
            if (!garageOk || !garageStationary() || pooledOriginGen != KmodoGarage.originGen()
                    || !KmodoGarage.contains(entity.getId(), pooledRes)) {
                unpool(res);
            }
        }

        if (!needsUpdate) {
            dormantFlag = true;
            if (prof) {
                KmodoProfiler.countSkipped();
            }
            return;
        }

        GeoModel geoModel = renderer.getGeoModel();
        BakedGeoModel baked = bakedModel(res);
        if (baked == null) {
            return;
        }

        long animStart = prof ? System.nanoTime() : 0L;
        geoModel.handleAnimations(entity, renderer.getInstanceId(entity),
                new AnimationState<>(entity, 0f, 0f, partialTick, false));
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.ANIMATE, System.nanoTime() - animStart);
        }

        try {
            renderer.preRender(new PoseStack(), entity, baked, null, null, false, partialTick, 0,
                    OverlayTexture.NO_OVERLAY, 1f, 1f, 1f, 1f);
            scaleW = ((GeoEntityRendererAccessor) renderer).komodo$getScaleWidth();
            scaleH = ((GeoEntityRendererAccessor) renderer).komodo$getScaleHeight();
        } catch (Throwable ignored) {
        }

        Set<String> dynBones = models.dynamicBones.keySet();
        if (liveBones == null || !res.equals(liveRes)) {
            Set<GeoBone> built = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object top : baked.topLevelBones()) {
                markLive((GeoBone) top, dynBones, built);
            }
            liveBones = built;
            liveRes = res;
        }
        Set<GeoBone> live = liveBones;

        Map<String, Matrix4f> local = new HashMap<>();
        poseHash = FNV_OFFSET;
        long walkStart = prof ? System.nanoTime() : 0L;
        PoseStack pose = new PoseStack();
        boolean hideBody = false;
        for (Object top : baked.topLevelBones()) {
            GeoBone topBone = (GeoBone) top;
            if (topBone.isHidden()) {
                hideBody = true;
            }
            if (live.contains(topBone)) {
                walkLocal(pose, topBone, dynBones, local, false, live);
            }
        }
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.WALK, System.nanoTime() - walkStart);
        }

        boneLocal = local;
        hideBodyFlag = hideBody;
        poseStamp++;
        dormancy.recordPose(poseHash, entity.tickCount);
        boolean dormant = dormancy.isDormant();
        dormantFlag = dormant;

        if (KmodoConfig.garageEnabled() && KmodoConfig.rawDrawAllowed()) {
            garageTransition(dormant, res, baked, partialTick);
        } else if (pooled) {
            unpool(res);
        }

        if (prof) {
            KmodoProfiler.countUpdated();
            KmodoProfiler.addUpdatedTotal(System.nanoTime() - updateStart);
        }
    }

    private void garageTransition(boolean dormant, ResourceLocation res, BakedGeoModel baked, float partialTick) {
        if (pooled && (pooledOriginGen != KmodoGarage.originGen()
                || !KmodoGarage.contains(entity.getId(), pooledRes))) {
            pooled = false;
            pooledRes = null;
            pooledSlotVerts = 0;
            pooledLight = Integer.MIN_VALUE;
        }
        if (dormant && garageStationary() && !pooled) {
            ResourceLocation texture = ((net.minecraft.client.renderer.entity.EntityRenderer) renderer)
                    .getTextureLocation(entity);
            if (texture == null) {
                return;
            }
            PoseStack root = garagePose(partialTick);
            int light = computePackedLight(partialTick);
            java.nio.ByteBuffer slice = KmodoGarageBake.bake(renderer, baked, root, light);
            if (slice == null) {
                return;
            }
            int slotVerts = slice.remaining() / KmodoGaragePool.VERTEX_STRIDE;
            KmodoGarage.onDormant(entity.getId(), res, texture, slotVerts, slice, light);
            if (KmodoGarage.contains(entity.getId(), res)) {
                pooledRes = res;
                pooledSlotVerts = slotVerts;
                pooledLight = light;
                pooledOriginGen = KmodoGarage.originGen();
                lastRelightTick = entity.tickCount;
                pooled = true;
            }
        } else if (!dormant && pooled) {
            unpool(res);
        } else if (dormant && pooled) {
            garageRelight(baked, partialTick);
        }
    }

    private void garageRelight(BakedGeoModel baked, float partialTick) {
        if (pooledRes == null) {
            return;
        }
        int now = entity.tickCount;
        if (now - lastRelightTick < RELIGHT_INTERVAL) {
            return;
        }
        lastRelightTick = now;
        int light = computePackedLight(partialTick);
        if (light == pooledLight) {
            return;
        }
        PoseStack root = garagePose(partialTick);
        java.nio.ByteBuffer slice = KmodoGarageBake.bake(renderer, baked, root, light);
        if (slice == null || slice.remaining() != pooledSlotVerts * KmodoGaragePool.VERTEX_STRIDE) {
            return;
        }
        KmodoGarage.relight(entity.getId(), pooledRes, light, slice);
        pooledLight = light;
    }

    private boolean garageStationary() {
        if (entity.isVehicle() || entity.getFirstPassenger() != null) {
            return false;
        }
        if (Math.abs(entity.getX() - entity.xOld) > GARAGE_POS_EPS
                || Math.abs(entity.getY() - entity.yOld) > GARAGE_POS_EPS
                || Math.abs(entity.getZ() - entity.zOld) > GARAGE_POS_EPS) {
            return false;
        }
        if (Math.abs(Mth.degreesDifference(entity.yRotO, entity.getYRot())) > GARAGE_ROT_EPS
                || Math.abs(Mth.degreesDifference(entity.xRotO, entity.getXRot())) > GARAGE_ROT_EPS) {
            return false;
        }
        return Math.abs(Mth.degreesDifference(entity.getPrevRoll(), entity.getRoll())) <= GARAGE_ROT_EPS;
    }

    private void unpool(ResourceLocation res) {
        ResourceLocation freeRes = pooledRes != null ? pooledRes : res;
        if (freeRes != null) {
            KmodoGarage.onWake(entity.getId(), freeRes);
        }
        pooled = false;
        pooledRes = null;
        pooledSlotVerts = 0;
        pooledLight = Integer.MIN_VALUE;
    }

    private PoseStack garagePose(float partialTick) {
        Vector3f visualPos = getVisualPosition(partialTick);
        PoseStack pose = new PoseStack();
        pose.translate(visualPos.x(), visualPos.y(), visualPos.z());
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        ((VehicleRenderer) renderer).vehicleAxis(entity, pose, yaw, partialTick);
        pose.scale(scaleW, scaleH, scaleW);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(0.0F, 0.01F, 0.0F);
        return pose;
    }

    private void walkLocal(PoseStack pose, GeoBone bone, Set<String> dynBones, Map<String, Matrix4f> out,
                           boolean hidden, Set<GeoBone> live) {
        pose.pushPose();
        RenderUtils.prepMatrixForBone(pose, bone);
        // Respect GeckoLib's hidden flags. SBW's setCustomAnimations (run inside handleAnimations, just
        // before this walk) hides bones like "root"/"passengerWeaponStation" while the local player is
        // zooming in a turret/weapon-station seat (and hides e.g. the turret on a wreck). A hidden dynamic
        // bone is simply left out of the matrix map, so beginFrame collapses its instance; the static body
        // is handled separately via hideBodyFlag.
        boolean boneHidden = hidden || bone.isHidden();
        if (!boneHidden && dynBones.contains(bone.getName())) {
            Matrix4f m = new Matrix4f(pose.last().pose());
            out.put(bone.getName(), m);
            foldMatrix(m);
        }
        for (GeoBone child : bone.getChildBones()) {
            // Skip subtrees that lead to no dynamic bone. Output is identical to a full walk because those
            // subtrees never contribute a matrix; we still descend through every ancestor of a dynamic bone.
            if (live.contains(child)) {
                walkLocal(pose, child, dynBones, out, boneHidden, live);
            }
        }
        pose.popPose();
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        if (renderer == null || !KmodoConfig.flywheelEnabled() || !BackendManager.isBackendOn()) {
            return;
        }
        if (pooled) {
            if (instancesCreated) {
                deleteInstances();
            }
            return;
        }
        final boolean prof = KmodoProfiler.enabled();
        long totalStart = prof ? System.nanoTime() : 0L;

        float partialTick = ctx.partialTick();

        VehicleModels models = KmodoFlywheelModelCache.getModels(renderer, entity);
        if (models == null) {
            return;
        }
        ResourceLocation res = modelRes();
        if (res == null) {
            return;
        }
        ResourceLocation tex = textureRes();
        if (instancesCreated && tex != null && !tex.equals(createdTexture)) {
            deleteInstances();
        }
        if (instancesCreated && !res.equals(createdModelRes)) {
            if (res.equals(pendingModelRes)) {
                pendingResFrames++;
            } else {
                pendingModelRes = res;
                pendingResFrames = 1;
            }
            if (pendingResFrames >= LOD_DEBOUNCE_FRAMES) {
                deleteInstances();
                pendingModelRes = null;
                pendingResFrames = 0;
            }
        } else if (pendingModelRes != null) {
            pendingModelRes = null;
            pendingResFrames = 0;
        }
        if (!instancesCreated) {
            long bakeStart = prof ? System.nanoTime() : 0L;
            if (prof) {
                KmodoProfiler.countBake();
            }
            if (models.body != null) {
                bodyInstance = instancer(models.body).createInstance();
            }
            for (Map.Entry<String, Model> e : models.dynamicBones.entrySet()) {
                dynamicInstances.put(e.getKey(), instancer(e.getValue()).createInstance());
            }
            instancesCreated = true;
            createdModelRes = res;
            createdTexture = tex;
            cullRadius = computeCullRadius(models);
            if (prof) {
                KmodoProfiler.addPhase(KmodoProfiler.Phase.BAKE, System.nanoTime() - bakeStart);
            }

            KmodoDebug.onFlywheelInstanceCreated(res, dynamicInstances.size());
        }
        if (bodyInstance == null && dynamicInstances.isEmpty()) {
            return;
        }
        if (hasApplied && !frustumVisible(ctx.frustum(), partialTick)) {
            if (prof) {
                KmodoProfiler.addPhase(KmodoProfiler.Phase.TOTAL, System.nanoTime() - totalStart);
            }
            return;
        }

        boolean dormant = dormantFlag;
        if (KmodoDebug.enabled()) {
            KmodoDebug.onDormancy(res, dormant);
        }

        Vector3f visualPos = getVisualPosition(partialTick);
        long stamp = poseStamp;
        boolean hideBody = hideBodyFlag;
        if (hasApplied && stamp == appliedStamp && hideBody == appliedHideBody
                && Math.abs(visualPos.x() - appliedX) < APPLY_POS_EPS
                && Math.abs(visualPos.y() - appliedY) < APPLY_POS_EPS
                && Math.abs(visualPos.z() - appliedZ) < APPLY_POS_EPS) {
            if (prof) {
                KmodoProfiler.addPhase(KmodoProfiler.Phase.TOTAL, System.nanoTime() - totalStart);
            }
            return;
        }

        Map<String, Matrix4f> local = boneLocal;

        PoseStack pose = new PoseStack();
        pose.translate(visualPos.x(), visualPos.y(), visualPos.z());
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        ((VehicleRenderer) renderer).vehicleAxis(entity, pose, yaw, partialTick);
        pose.scale(scaleW, scaleH, scaleW);
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(0.0F, 0.01F, 0.0F);
        Matrix4f root = pose.last().pose();

        if (bodyInstance != null) {
            bodyInstance.setTransform(hideBody ? COLLAPSE : root);
            bodyInstance.setChanged();
            if (prof) {
                KmodoProfiler.countInstances(1);
            }
        }
        if (!dynamicInstances.isEmpty()) {
            for (Map.Entry<String, TransformedInstance> e : dynamicInstances.entrySet()) {
                Matrix4f lm = local == null ? null : local.get(e.getKey());

                e.getValue().setTransform(lm != null ? new Matrix4f(root).mul(lm) : COLLAPSE);
                e.getValue().setChanged();
                if (prof) {
                    KmodoProfiler.countInstances(1);
                }
            }
        }

        List<FlatLit> lit = new ArrayList<>(dynamicInstances.values());
        if (bodyInstance != null) {
            lit.add(bodyInstance);
        }
        long relightStart = prof ? System.nanoTime() : 0L;
        relight(partialTick, lit.toArray(new FlatLit[0]));
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.RELIGHT, System.nanoTime() - relightStart);
        }

        appliedStamp = stamp;
        appliedX = visualPos.x();
        appliedY = visualPos.y();
        appliedZ = visualPos.z();
        appliedHideBody = hideBody;
        hasApplied = true;

        if (KmodoDebug.enabled()) {
            KmodoDebug.onFlywheelFrameDrawing(res);
        }
        if (prof) {
            KmodoProfiler.addPhase(KmodoProfiler.Phase.TOTAL, System.nanoTime() - totalStart);
        }
    }

    private void foldMatrix(Matrix4f m) {
        foldFloat(m.m00());
        foldFloat(m.m01());
        foldFloat(m.m02());
        foldFloat(m.m03());
        foldFloat(m.m10());
        foldFloat(m.m11());
        foldFloat(m.m12());
        foldFloat(m.m13());
        foldFloat(m.m20());
        foldFloat(m.m21());
        foldFloat(m.m22());
        foldFloat(m.m23());
        foldFloat(m.m30());
        foldFloat(m.m31());
        foldFloat(m.m32());
        foldFloat(m.m33());
    }

    private void foldFloat(float v) {
        int q = Float.isNaN(v) ? Integer.MIN_VALUE : Math.round(v * 1024.0F);
        poseHash = poseHash * FNV_PRIME + q;
    }

    @Override
    public boolean isVisible(FrustumIntersection frustum) {
        return frustumVisible(frustum, 1.0f);
    }

    private boolean frustumVisible(FrustumIntersection frustum, float partialTick) {
        if (cullRadius <= 0.0f || !KmodoConfig.frustumCullTrusted()) {
            return true;
        }
        Vector3f p = getVisualPosition(partialTick);
        double dx = entity.getX() - entity.xOld;
        double dy = entity.getY() - entity.yOld;
        double dz = entity.getZ() - entity.zOld;
        float margin = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float r = cullRadius * Math.max(scaleW, scaleH) + margin;
        return frustum.testSphere(p.x(), p.y(), p.z(), r);
    }

    private static float computeCullRadius(VehicleModels models) {
        float r = 0.0f;
        if (models.body != null) {
            r = sphereExtent(models.body);
        }
        for (Model m : models.dynamicBones.values()) {
            r = Math.max(r, sphereExtent(m));
        }
        return r * 1.5f;
    }

    private static float sphereExtent(Model model) {
        Vector4fc s = model.boundingSphere();
        float cx = s.x();
        float cy = s.y();
        float cz = s.z();
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz) + s.w();
    }

    private Instancer<TransformedInstance> instancer(Model model) {
        return instancerProvider().instancer(InstanceTypes.TRANSFORMED, model);
    }

    private void deleteInstances() {
        if (instancesCreated && createdModelRes != null) {
            KmodoDebug.onFlywheelInstanceDeleted(createdModelRes);
        }
        if (bodyInstance != null) {
            bodyInstance.delete();
            bodyInstance = null;
        }
        dynamicInstances.values().forEach(TransformedInstance::delete);
        dynamicInstances.clear();
        instancesCreated = false;
        createdModelRes = null;
        createdTexture = null;
        hasApplied = false;
        appliedStamp = Long.MIN_VALUE;
    }

    @Override
    protected void _delete() {
        BY_ENTITY.remove(entity.getId(), this);

        if (instancesCreated && createdModelRes != null) {
            KmodoDebug.onFlywheelInstanceDeleted(createdModelRes);
        }
        if (bodyInstance != null) {
            bodyInstance.delete();
            bodyInstance = null;
        }
        dynamicInstances.values().forEach(TransformedInstance::delete);
        dynamicInstances.clear();
    }

    private ResourceLocation modelRes() {
        try {
            GeoModel model = renderer.getGeoModel();
            return model.getModelResource(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private ResourceLocation textureRes() {
        try {
            return ((EntityRenderer) renderer).getTextureLocation(entity);
        } catch (Throwable t) {
            return null;
        }
    }

    private BakedGeoModel bakedModel(ResourceLocation res) {
        try {
            return renderer.getGeoModel().getBakedModel(res);
        } catch (Throwable t) {
            return null;
        }
    }
}
