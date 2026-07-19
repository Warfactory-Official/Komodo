package com.norwood.komodo.client.render.kmodo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.norwood.komodo.Komodo;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.visualization.VisualManager;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoRenderer;

@EventBusSubscriber(modid = Komodo.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class KmodoFlywheelEvents {

    private KmodoFlywheelEvents() {}

    @SubscribeEvent
    public static void onRenderTick(RenderFrameEvent.Pre event) {
        if (!KmodoConfig.flywheelEnabled() || !BackendManager.isBackendOn()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof GeoVehicleEntity vehicle)) {
                continue;
            }
            KmodoFlywheelVehicleVisual visual = KmodoFlywheelVehicleVisual.byEntity(vehicle.getId());
            if (visual != null) {
                visual.renderThreadUpdate(partialTick);
            }
        }
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        boolean justRegistered = KmodoFlywheelRegistrar.ensureRegistered();

        if (!KmodoConfig.flywheelEnabled() || !BackendManager.isBackendOn()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        VisualManager<Entity> entities = null;
        if (justRegistered && VisualizationManager.supportsVisualization(mc.level)) {
            VisualizationManager manager = VisualizationManager.get(mc.level);
            if (manager != null) {
                entities = manager.entities();
            }
        }

        boolean drawHitboxes = mc.getEntityRenderDispatcher().shouldRenderHitBoxes()
                && !mc.showOnlyReducedInfo();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drewHitbox = false;

        boolean garage = KmodoConfig.garageEnabled() && KmodoConfig.rawDrawAllowed();
        IntOpenHashSet liveIds = garage ? new IntOpenHashSet() : null;

        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof GeoVehicleEntity vehicle)) {
                continue;
            }
            if (liveIds != null) {
                liveIds.add(vehicle.getId());
            }
            EntityRenderer<?> er = mc.getEntityRenderDispatcher().getRenderer(vehicle);
            if (er instanceof GeoRenderer<?> renderer) {
                KmodoFlywheelModelCache.getModels(renderer, vehicle);
            }
            if (entities != null) {
                entities.queueAdd(vehicle);
            }
            if (drawHitboxes && !vehicle.isInvisible() && VisualizationHelper.skipVanillaRender(vehicle)) {
                double ex = Mth.lerp(partialTick, vehicle.xOld, vehicle.getX());
                double ey = Mth.lerp(partialTick, vehicle.yOld, vehicle.getY());
                double ez = Mth.lerp(partialTick, vehicle.zOld, vehicle.getZ());
                pose.pushPose();
                pose.translate(ex - cam.x, ey - cam.y, ez - cam.z);
                renderHitbox(pose, buffers.getBuffer(RenderType.lines()), vehicle, partialTick);
                pose.popPose();
                drewHitbox = true;
            }
        }

        if (drewHitbox) {
            buffers.endBatch(RenderType.lines());
        }

        if (garage) {
            drawGarage(mc, event, cam, liveIds);
        } else if (KmodoGarage.poolCount() > 0) {
            KmodoGarage.invalidateAll();
        }
    }

    private static void drawGarage(Minecraft mc, RenderLevelStageEvent event, Vec3 cam, IntOpenHashSet liveIds) {
        VisualizationManager manager = VisualizationManager.supportsVisualization(mc.level)
                ? VisualizationManager.get(mc.level) : null;
        if (manager == null) {
            return;
        }
        KmodoGarage.syncOrigin(manager.renderOrigin());
        KmodoGarage.freeMissing(liveIds);
        KmodoGarage.compactStep();
        Matrix4f cameraView = new Matrix4f(event.getPoseStack().last().pose());
        Matrix4f projection = event.getProjectionMatrix();
        KmodoGarage.drawAll(cameraView, projection, cam.x, cam.y, cam.z);
    }

    private static void renderHitbox(PoseStack pose, VertexConsumer lines, Entity entity, float partialTick) {
        AABB box = entity.getBoundingBox().move(-entity.getX(), -entity.getY(), -entity.getZ());
        LevelRenderer.renderLineBox(pose, lines, box, 1.0F, 1.0F, 1.0F, 1.0F);

        if (entity.isMultipartEntity() && entity.getParts() != null) {
            double px = -Mth.lerp(partialTick, entity.xOld, entity.getX());
            double py = -Mth.lerp(partialTick, entity.yOld, entity.getY());
            double pz = -Mth.lerp(partialTick, entity.zOld, entity.getZ());
            for (PartEntity<?> part : entity.getParts()) {
                pose.pushPose();
                pose.translate(px + Mth.lerp(partialTick, part.xOld, part.getX()),
                        py + Mth.lerp(partialTick, part.yOld, part.getY()),
                        pz + Mth.lerp(partialTick, part.zOld, part.getZ()));
                LevelRenderer.renderLineBox(pose, lines,
                        part.getBoundingBox().move(-part.getX(), -part.getY(), -part.getZ()),
                        0.25F, 1.0F, 0.0F, 1.0F);
                pose.popPose();
            }
        }

        Vec3 view = entity.getViewVector(partialTick);
        Matrix4f matrix = pose.last().pose();
        float eye = entity.getEyeHeight();
        lines.addVertex(matrix, 0.0F, eye, 0.0F).setColor(0, 0, 255, 255)
                .setNormal(pose.last(), (float) view.x, (float) view.y, (float) view.z);
        lines.addVertex(matrix, (float) (view.x * 2.0), (float) (eye + view.y * 2.0), (float) (view.z * 2.0))
                .setColor(0, 0, 255, 255)
                .setNormal(pose.last(), (float) view.x, (float) view.y, (float) view.z);
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel().isClientSide()) {
            purge();
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            purge();
        }
    }

    private static void purge() {
        KmodoMeshCache.invalidateAll();
        KmodoFlywheelModelCache.invalidateAll();
        KmodoGarage.invalidateAll();
        KmodoDebug.invalidateAll();
    }
}
