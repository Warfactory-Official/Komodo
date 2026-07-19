package com.norwood.komodo.client.render.kmodo;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.resources.ResourceLocation;

import com.norwood.komodo.Komodo;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;

public final class KmodoDebug {

    private KmodoDebug() {}

    private static volatile boolean ENABLED = false;

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean toggle() {
        ENABLED = !ENABLED;
        return ENABLED;
    }

    public static void setEnabled(boolean value) {
        ENABLED = value;
    }

    public enum Mode {

        FLYWHEEL,

        RETAINED,

        VANILLA
    }

    public static final class ModelStats {

        public final ResourceLocation res;

        public volatile int flywheelBodyVertices = 0;
        public volatile int flywheelDynamicBoneCount = 0;

        public volatile int flywheelDynamicVertices = 0;

        public volatile long flywheelGpuBytes = 0L;

        public volatile int retainedVboCount = 0;
        public volatile int retainedTotalVertices = 0;

        public final AtomicInteger flywheelLiveInstances = new AtomicInteger(0);

        public final AtomicInteger retainedFrameVehicles = new AtomicInteger(0);

        public volatile int retainedFrameLast = 0;

        public final AtomicInteger dormantThisFrame = new AtomicInteger(0);

        public final AtomicInteger activeThisFrame = new AtomicInteger(0);

        public volatile int dormantLastFrame = 0;

        public volatile int activeLastFrame = 0;

        public volatile Mode lastMode = null;

        volatile boolean flywheelBakeSummarised = false;
        volatile boolean retainedBakeSummarised = false;

        ModelStats(ResourceLocation res) {
            this.res = res;
        }
    }

    private static final Map<ResourceLocation, ModelStats> MODELS = new ConcurrentHashMap<>();

    static final Set<ResourceLocation> RETAINED_THIS_FRAME = ConcurrentHashMap.newKeySet();

    static ModelStats statsFor(ResourceLocation res) {
        return MODELS.computeIfAbsent(res, ModelStats::new);
    }

    public static Collection<ModelStats> allStats() {
        return MODELS.values();
    }

    public static void onFlywheelBaked(ResourceLocation res, int bodyVertices, int dynamicBoneCount,
                                       int dynamicVertices, long memoryBlockBytes) {
        if (!ENABLED) return;
        ModelStats s = statsFor(res);
        s.flywheelBodyVertices = bodyVertices;
        s.flywheelDynamicBoneCount = dynamicBoneCount;
        s.flywheelDynamicVertices = dynamicVertices;
        s.flywheelGpuBytes = memoryBlockBytes;

        if (!s.flywheelBakeSummarised) {
            s.flywheelBakeSummarised = true;
            int totalVerts = bodyVertices + dynamicVertices;
            Komodo.LOGGER.info(
                    "[KmodoDebug] Flywheel bake done: {} | body={} verts | dynamic {} bone(s)={} verts"
                            + " | GPU mem={}B (stride {}×{})",
                    res, bodyVertices, dynamicBoneCount, dynamicVertices,
                    memoryBlockBytes, FullVertexView.STRIDE, totalVerts);
        }
    }

    public static void onFlywheelInstanceCreated(ResourceLocation res, int dynamicBoneCount) {
        statsFor(res).flywheelLiveInstances.incrementAndGet();
    }

    public static void onFlywheelInstanceDeleted(ResourceLocation res) {
        ModelStats s = MODELS.get(res);
        if (s != null) {
            s.flywheelLiveInstances.updateAndGet(v -> v > 0 ? v - 1 : 0);
        }
    }

    public static void onFlywheelFrameDrawing(ResourceLocation res) {
        if (!ENABLED) return;
        recordMode(res, Mode.FLYWHEEL);
    }

    public static void onDormancy(ResourceLocation res, boolean dormant) {
        if (!ENABLED) return;
        if (res == null) return;
        ModelStats s = statsFor(res);
        (dormant ? s.dormantThisFrame : s.activeThisFrame).incrementAndGet();
    }

    public static void onRetainedBaked(ResourceLocation res, int vboCount, int totalVertices) {
        if (!ENABLED) return;
        ModelStats s = statsFor(res);
        s.retainedVboCount = vboCount;
        s.retainedTotalVertices = totalVertices;

        if (!s.retainedBakeSummarised) {
            s.retainedBakeSummarised = true;
            Komodo.LOGGER.info("[KmodoDebug] Retained bake done: {} | {} VBOs | {} total verts",
                    res, vboCount, totalVertices);
        }
    }

    public static void onRetainedFlush(ResourceLocation res) {
        if (!ENABLED) return;
        if (res == null) return;
        RETAINED_THIS_FRAME.add(res);
        statsFor(res).retainedFrameVehicles.incrementAndGet();
        recordMode(res, Mode.RETAINED);
    }

    public static void beginFrame() {
        if (!ENABLED) return;
        RETAINED_THIS_FRAME.clear();
        for (ModelStats s : MODELS.values()) {
            s.retainedFrameLast = s.retainedFrameVehicles.getAndSet(0);
            s.dormantLastFrame = s.dormantThisFrame.getAndSet(0);
            s.activeLastFrame = s.activeThisFrame.getAndSet(0);
        }
    }

    private static void recordMode(ResourceLocation res, Mode mode) {
        ModelStats s = statsFor(res);
        if (s.lastMode != mode) {
            Mode prev = s.lastMode;
            s.lastMode = mode;
            Komodo.LOGGER.info("[KmodoDebug] {} mode: {} → {}",
                    res, prev == null ? "NONE" : prev, mode);
        }
    }

    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== KmodoDebug dump ===\n");
        if (MODELS.isEmpty()) {
            sb.append("  (no models tracked yet — drive a vehicle)\n");
        }
        for (ModelStats s : MODELS.values()) {
            sb.append("  ").append(s.res).append('\n');
            Mode mode = s.lastMode;
            sb.append("    mode: ").append(mode == null ? "UNKNOWN" : mode).append('\n');

            if (s.flywheelBodyVertices > 0 || s.flywheelDynamicBoneCount > 0) {
                sb.append("    [Flywheel] body=").append(s.flywheelBodyVertices).append(" verts");
                sb.append(" | dynamic ").append(s.flywheelDynamicBoneCount).append(" bone(s)=")
                        .append(s.flywheelDynamicVertices).append(" verts");
                long totalVerts = s.flywheelBodyVertices + s.flywheelDynamicVertices;
                sb.append(" | GPU ").append(s.flywheelGpuBytes).append("B (stride ")
                        .append(FullVertexView.STRIDE).append("×").append(totalVerts).append(")\n");
                sb.append("    [Flywheel] live vehicles=").append(s.flywheelLiveInstances.get()).append('\n');
                sb.append("    [Flywheel] dormant=").append(s.dormantLastFrame)
                        .append(" active=").append(s.activeLastFrame).append(" (per render frame)\n");
            } else {
                sb.append("    [Flywheel] not baked\n");
            }

            if (s.retainedVboCount > 0) {
                sb.append("    [Retained] ").append(s.retainedVboCount).append(" VBOs | ")
                        .append(s.retainedTotalVertices).append(" total verts\n");
                sb.append("    [Retained] vehicles this frame=")
                        .append(s.retainedFrameLast).append('\n');
            } else {
                sb.append("    [Retained] not baked\n");
            }
        }
        sb.append("=== end ===");
        String text = sb.toString();
        Komodo.LOGGER.info("[KmodoDebug] {}", text);
        return text;
    }

    public static void invalidateAll() {
        MODELS.clear();
        RETAINED_THIS_FRAME.clear();
    }
}
