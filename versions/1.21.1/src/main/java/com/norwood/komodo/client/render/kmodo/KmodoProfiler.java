package com.norwood.komodo.client.render.kmodo;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import net.minecraft.client.Minecraft;

import com.norwood.komodo.Komodo;

public final class KmodoProfiler {

    private KmodoProfiler() {}

    private static volatile boolean ENABLED = false;

    public static boolean enabled() {
        return ENABLED;
    }

    public static void setEnabled(boolean value) {
        if (value && !ENABLED) {
            hasFrameStamp = false;
        }
        ENABLED = value;
    }

    public enum Phase {
        BAKE,
        ANIMATE,
        WALK,
        RELIGHT,
        DORMANCY,
        GARAGE_BAKE,
        GARAGE_DRAW,
        GARAGE_COMPACT,
        TOTAL
    }

    public static final int PHASE_COUNT = Phase.values().length;

    private static final boolean[] RENDER_THREAD = new boolean[PHASE_COUNT];
    static {
        RENDER_THREAD[Phase.ANIMATE.ordinal()] = true;
        RENDER_THREAD[Phase.WALK.ordinal()] = true;
        RENDER_THREAD[Phase.DORMANCY.ordinal()] = true;
        RENDER_THREAD[Phase.GARAGE_BAKE.ordinal()] = true;
        RENDER_THREAD[Phase.GARAGE_DRAW.ordinal()] = true;
        RENDER_THREAD[Phase.GARAGE_COMPACT.ordinal()] = true;
    }

    private static final LongAdder[] PHASE_NANOS = new LongAdder[PHASE_COUNT];
    static {
        for (int i = 0; i < PHASE_COUNT; i++) {
            PHASE_NANOS[i] = new LongAdder();
        }
    }

    private static final LongAdder VEHICLES_PROCESSED = new LongAdder();
    private static final LongAdder VEHICLES_UPDATED = new LongAdder();
    private static final LongAdder VEHICLES_SKIPPED_DORMANT = new LongAdder();
    private static final LongAdder INSTANCES_SET_CHANGED = new LongAdder();
    private static final LongAdder WAKE_EVENTS = new LongAdder();
    private static final LongAdder BAKES = new LongAdder();
    private static final LongAdder UPDATED_TOTAL_NANOS = new LongAdder();
    private static final LongAdder STATE_ACTIVE = new LongAdder();
    private static final LongAdder STATE_SETTLING = new LongAdder();
    private static final LongAdder STATE_DORMANT = new LongAdder();

    public static void addPhase(Phase phase, long nanos) {
        PHASE_NANOS[phase.ordinal()].add(nanos);
    }

    public static void countProcessed() {
        VEHICLES_PROCESSED.increment();
    }

    public static void countUpdated() {
        VEHICLES_UPDATED.increment();
    }

    public static void countSkipped() {
        VEHICLES_SKIPPED_DORMANT.increment();
    }

    public static void countInstances(int n) {
        INSTANCES_SET_CHANGED.add(n);
    }

    public static void countWake() {
        WAKE_EVENTS.increment();
    }

    public static void countBake() {
        BAKES.increment();
    }

    public static void addUpdatedTotal(long nanos) {
        UPDATED_TOTAL_NANOS.add(nanos);
    }

    public static void countState(KmodoDormancy.State state) {
        switch (state) {
            case ACTIVE -> STATE_ACTIVE.increment();
            case SETTLING -> STATE_SETTLING.increment();
            case DORMANT -> STATE_DORMANT.increment();
        }
    }

    private static final int RING = 240;

    private static final long[] framePhaseNanos = new long[PHASE_COUNT * RING];
    private static final long[] frameTotalNanos = new long[RING];
    private static final long[] frameWallNanos = new long[RING];
    private static final long[] frameProcessed = new long[RING];
    private static final long[] frameUpdated = new long[RING];
    private static final long[] frameSkipped = new long[RING];
    private static final long[] frameInstances = new long[RING];
    private static final long[] frameWake = new long[RING];
    private static final long[] frameBakes = new long[RING];
    private static final long[] frameUpdatedTotal = new long[RING];
    private static final long[] frameActive = new long[RING];
    private static final long[] frameSettling = new long[RING];
    private static final long[] frameDormant = new long[RING];

    private static int ringHead = 0;
    private static int ringValid = 0;

    private static long lastFrameStamp = 0L;
    private static boolean hasFrameStamp = false;

    private static volatile int garagePoolsCur;
    private static volatile int garageSlicesCur;
    private static volatile int garageHolesCur;
    private static volatile long garageBytesCur;

    private static Run activeRun = null;
    private static final List<Run> COMPLETED = Collections.synchronizedList(new ArrayList<>());

    public static void rollFrame() {
        long now = System.nanoTime();
        long wall = hasFrameStamp ? (now - lastFrameStamp) : 0L;
        lastFrameStamp = now;
        hasFrameStamp = true;

        int base = ringHead * PHASE_COUNT;
        for (int i = 0; i < PHASE_COUNT; i++) {
            long v = PHASE_NANOS[i].sumThenReset();
            framePhaseNanos[base + i] = v;
        }
        long recordedTotal = framePhaseNanos[base + Phase.TOTAL.ordinal()];
        frameTotalNanos[ringHead] = recordedTotal;

        long processed = VEHICLES_PROCESSED.sumThenReset();
        long updated = VEHICLES_UPDATED.sumThenReset();
        long skipped = VEHICLES_SKIPPED_DORMANT.sumThenReset();
        long instances = INSTANCES_SET_CHANGED.sumThenReset();
        long wake = WAKE_EVENTS.sumThenReset();
        long active = STATE_ACTIVE.sumThenReset();
        long settling = STATE_SETTLING.sumThenReset();
        long dormant = STATE_DORMANT.sumThenReset();
        long bakes = BAKES.sumThenReset();
        long updatedTotal = UPDATED_TOTAL_NANOS.sumThenReset();

        frameWallNanos[ringHead] = wall;
        frameProcessed[ringHead] = processed;
        frameUpdated[ringHead] = updated;
        frameSkipped[ringHead] = skipped;
        frameInstances[ringHead] = instances;
        frameWake[ringHead] = wake;
        frameBakes[ringHead] = bakes;
        frameUpdatedTotal[ringHead] = updatedTotal;
        frameActive[ringHead] = active;
        frameSettling[ringHead] = settling;
        frameDormant[ringHead] = dormant;

        garagePoolsCur = KmodoGarage.poolCount();
        garageSlicesCur = KmodoGarage.liveSlices();
        garageHolesCur = KmodoGarage.holes();
        garageBytesCur = KmodoGarage.gpuBytes();

        ringHead = (ringHead + 1) % RING;
        if (ringValid < RING) {
            ringValid++;
        }

        Run run = activeRun;
        if (run != null) {
            run.frameSampled(base, recordedTotal, wall, processed, updated, skipped,
                    instances, wake, bakes, updatedTotal, active, settling, dormant);
        }
    }

    public static Snapshot snapshot() {
        return snapshotLast(ringValid);
    }

    public static Snapshot snapshotLast(int n) {
        int count = Math.min(n, ringValid);
        Window w = new Window(count);
        for (int f = 0; f < count; f++) {
            int idx = ((ringHead - count + f) % RING + RING) % RING;
            w.addFrame(idx);
        }
        return w.build();
    }

    static Window newWindow() {
        return new Window();
    }

    static final class Window {
        int frames;
        long wallNanos;
        long processed;
        long updated;
        long skipped;
        long instances;
        long wake;
        long bakes;
        long updatedTotal;
        long active;
        long settling;
        long dormant;
        final long[] phaseSum = new long[PHASE_COUNT];
        long totalSum;
        final List<long[]> phaseSamples = new ArrayList<>();
        final List<Long> totalSamples = new ArrayList<>();
        final boolean collectSamples;

        Window() {
            this.collectSamples = true;
        }

        Window(int expected) {
            this.collectSamples = true;
        }

        void addFrame(int idx) {
            int base = idx * PHASE_COUNT;
            accumulate(base, frameTotalNanos[idx], frameWallNanos[idx], frameProcessed[idx],
                    frameUpdated[idx], frameSkipped[idx], frameInstances[idx], frameWake[idx],
                    frameBakes[idx], frameUpdatedTotal[idx],
                    frameActive[idx], frameSettling[idx], frameDormant[idx]);
        }

        void accumulate(int base, long total, long wall, long proc, long upd, long skp,
                        long inst, long wk, long bk, long updTot, long act, long set, long dor) {
            frames++;
            wallNanos += wall;
            processed += proc;
            updated += upd;
            skipped += skp;
            instances += inst;
            wake += wk;
            bakes += bk;
            updatedTotal += updTot;
            active += act;
            settling += set;
            dormant += dor;
            long[] sample = new long[PHASE_COUNT];
            for (int p = 0; p < PHASE_COUNT; p++) {
                long v = framePhaseNanos[base + p];
                phaseSum[p] += v;
                sample[p] = v;
            }
            phaseSamples.add(sample);
            totalSum += total;
            totalSamples.add(total);
        }

        Snapshot build() {
            return new Snapshot(this);
        }
    }

    public static final class Snapshot {
        public final int frames;
        public final double avgFrameMs;
        public final double windowFps;
        public final double mcFps;
        public final int workerThreads;

        public final long[] phaseAvgNanos = new long[PHASE_COUNT];
        public final long[] phaseP50Nanos = new long[PHASE_COUNT];
        public final long[] phaseP95Nanos = new long[PHASE_COUNT];
        public final long[] phaseMaxNanos = new long[PHASE_COUNT];

        public final double aggCpuMsPerFrameAvg;
        public final double aggCpuMsPerFrameP95;
        public final double aggCpuMsPerFrameMax;
        public final double wallEstMsPerFrame;
        public final double perVehicleCpuUs;
        public final double pctOfFrame;

        public double renderThreadMsPerFrame;
        public double unaccountedMsPerFrame;
        public double gpuMsPerFrame;
        public String topPhase = "-";
        public double topPhaseMs;

        public final double processedPerFrame;
        public final double updatedPerFrame;
        public final double skippedPerFrame;
        public final double instancesPerFrame;
        public final double wakePerSec;
        public final double bakesPerFrame;

        public final double stateActiveAvg;
        public final double stateSettlingAvg;
        public final double stateDormantAvg;

        public final long meshCount;
        public final long liveInstances;
        public final long totalVertices;
        public final long gpuBytes;

        public final int garagePools;
        public final int garageSlices;
        public final int garageHoles;
        public final long garageGpuBytes;

        Snapshot(Window w) {
            this.frames = w.frames;
            this.workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
            this.mcFps = Minecraft.getInstance().getFps();
            this.garagePools = garagePoolsCur;
            this.garageSlices = garageSlicesCur;
            this.garageHoles = garageHolesCur;
            this.garageGpuBytes = garageBytesCur;

            long meshes = 0L;
            long live = 0L;
            long verts = 0L;
            long bytes = 0L;
            for (KmodoDebug.ModelStats s : KmodoDebug.allStats()) {
                long liveInst = s.flywheelLiveInstances.get();
                if (liveInst > 0 && (s.flywheelBodyVertices > 0 || s.flywheelDynamicBoneCount > 0)) {
                    meshes += 1L + s.flywheelDynamicBoneCount;
                    live += liveInst;
                    verts += ((long) s.flywheelBodyVertices + s.flywheelDynamicVertices) * liveInst;
                    bytes += (long) s.flywheelGpuBytes * liveInst;
                }
            }
            this.meshCount = meshes;
            this.liveInstances = live;
            this.totalVertices = verts;
            this.gpuBytes = bytes;

            int n = w.frames;
            if (n <= 0) {
                this.avgFrameMs = 0;
                this.windowFps = 0;
                this.aggCpuMsPerFrameAvg = 0;
                this.aggCpuMsPerFrameP95 = 0;
                this.aggCpuMsPerFrameMax = 0;
                this.wallEstMsPerFrame = 0;
                this.perVehicleCpuUs = 0;
                this.pctOfFrame = 0;
                this.processedPerFrame = 0;
                this.updatedPerFrame = 0;
                this.skippedPerFrame = 0;
                this.instancesPerFrame = 0;
                this.wakePerSec = 0;
                this.bakesPerFrame = 0;
                this.stateActiveAvg = 0;
                this.stateSettlingAvg = 0;
                this.stateDormantAvg = 0;
                return;
            }

            for (int p = 0; p < PHASE_COUNT; p++) {
                long[] vals = new long[n];
                long max = 0L;
                for (int f = 0; f < n; f++) {
                    long v = w.phaseSamples.get(f)[p];
                    vals[f] = v;
                    if (v > max) max = v;
                }
                Arrays.sort(vals);
                phaseAvgNanos[p] = Math.round((double) w.phaseSum[p] / n);
                phaseP50Nanos[p] = percentile(vals, 0.50);
                phaseP95Nanos[p] = percentile(vals, 0.95);
                phaseMaxNanos[p] = max;
            }

            long[] totals = new long[n];
            long totalMax = 0L;
            for (int f = 0; f < n; f++) {
                long v = w.totalSamples.get(f);
                totals[f] = v;
                if (v > totalMax) totalMax = v;
            }
            Arrays.sort(totals);

            double avgFrameNs = (double) w.wallNanos / n;
            double aggAvgNs = (double) w.totalSum / n;
            double aggP95Ns = percentile(totals, 0.95);

            this.avgFrameMs = avgFrameNs / 1.0e6;
            this.windowFps = avgFrameNs > 0 ? 1.0e9 / avgFrameNs : 0;
            this.aggCpuMsPerFrameAvg = aggAvgNs / 1.0e6;
            this.aggCpuMsPerFrameP95 = aggP95Ns / 1.0e6;
            this.aggCpuMsPerFrameMax = totalMax / 1.0e6;

            double parallelism = Math.min(workerThreads, Math.max(1.0, (double) w.processed / n));
            this.wallEstMsPerFrame = (aggAvgNs / parallelism) / 1.0e6;
            this.perVehicleCpuUs = w.updated > 0 ? ((double) w.updatedTotal / w.updated) / 1.0e3 : 0;
            this.pctOfFrame = avgFrameNs > 0 ? (wallEstMsPerFrame * 1.0e6) / avgFrameNs * 100.0 : 0;

            this.processedPerFrame = (double) w.processed / n;
            this.updatedPerFrame = (double) w.updated / n;
            this.skippedPerFrame = (double) w.skipped / n;
            this.instancesPerFrame = (double) w.instances / n;
            this.bakesPerFrame = (double) w.bakes / n;
            double windowSeconds = w.wallNanos / 1.0e9;
            this.wakePerSec = windowSeconds > 0 ? w.wake / windowSeconds : 0;

            this.stateActiveAvg = (double) w.active / n;
            this.stateSettlingAvg = (double) w.settling / n;
            this.stateDormantAvg = (double) w.dormant / n;

            double rtNs = 0;
            long bestNs = 0;
            int bestIdx = -1;
            for (int p = 0; p < PHASE_COUNT; p++) {
                if (RENDER_THREAD[p]) {
                    rtNs += phaseAvgNanos[p];
                }
                if (p != Phase.TOTAL.ordinal() && phaseAvgNanos[p] > bestNs) {
                    bestNs = phaseAvgNanos[p];
                    bestIdx = p;
                }
            }
            this.renderThreadMsPerFrame = rtNs / 1.0e6;
            this.gpuMsPerFrame = KmodoGpuTimer.avgNanos() / 1.0e6;
            this.unaccountedMsPerFrame = Math.max(0.0, avgFrameMs - renderThreadMsPerFrame);
            if (bestIdx >= 0) {
                this.topPhase = Phase.values()[bestIdx].name();
                this.topPhaseMs = bestNs / 1.0e6;
            }
        }
    }

    private static long percentile(long[] sorted, double q) {
        if (sorted.length == 0) return 0L;
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }

    public static boolean isRunActive() {
        return activeRun != null;
    }

    public static void startRun(String label) {
        Run run = new Run(label);
        run.dormancyAtStart = KmodoConfig.dormancyEnabled();
        activeRun = run;
        hasFrameStamp = false;
    }

    public static Run stopRun() {
        Run run = activeRun;
        activeRun = null;
        if (run == null) {
            return null;
        }
        run.finalizeRun();
        COMPLETED.add(run);
        exportCsv(run);
        Komodo.LOGGER.info("[KmodoProfiler] run '{}' finished: {}", run.label, run.summaryLine());
        return run;
    }

    public static List<Run> completedRuns() {
        return new ArrayList<>(COMPLETED);
    }

    public static Run lastRun() {
        synchronized (COMPLETED) {
            return COMPLETED.isEmpty() ? null : COMPLETED.get(COMPLETED.size() - 1);
        }
    }

    public static final class Run {
        public final String label;
        boolean dormancyAtStart;
        final Window window = new Window();
        public Snapshot summary;
        long frameCount;

        Run(String label) {
            this.label = label;
        }

        void frameSampled(int base, long total, long wall, long proc, long upd, long skp,
                          long inst, long wk, long bk, long updTot, long act, long set, long dor) {
            window.accumulate(base, total, wall, proc, upd, skp, inst, wk, bk, updTot, act, set, dor);
            frameCount++;
        }

        void finalizeRun() {
            this.summary = window.build();
        }

        public String summaryLine() {
            if (summary == null) return "(no data)";
            return String.format(
                    "frames=%d fps=%.1f frameMs=%.2f rtCPU=%.2fms other=%.2fms top=%s(%.2fms) gpuGarage=%.2fms "
                            + "workerAgg=%.2fms perVeh=%.1fus upd/frame=%.1f inst/frame=%.0f bake/frame=%.2f wake/s=%.1f "
                            + "active=%.1f settling=%.1f dormant=%.1f live=%d verts=%d gpu=%dkB",
                    summary.frames, summary.windowFps, summary.avgFrameMs,
                    summary.renderThreadMsPerFrame, summary.unaccountedMsPerFrame, summary.topPhase,
                    summary.topPhaseMs, summary.gpuMsPerFrame, summary.aggCpuMsPerFrameAvg,
                    summary.perVehicleCpuUs, summary.updatedPerFrame, summary.instancesPerFrame,
                    summary.bakesPerFrame, summary.wakePerSec, summary.stateActiveAvg,
                    summary.stateSettlingAvg, summary.stateDormantAvg, summary.liveInstances,
                    summary.totalVertices, summary.gpuBytes / 1024);
        }
    }

    public static String compareRuns(Run a, Run b) {
        if (a == null || b == null || a.summary == null || b.summary == null) {
            return "compareRuns: missing run data";
        }
        Snapshot sa = a.summary;
        Snapshot sb = b.summary;
        StringBuilder sb2 = new StringBuilder();
        sb2.append("=== Kmodo A/B: ").append(a.label).append(" vs ").append(b.label).append(" ===\n");
        sb2.append(row("fps", sa.windowFps, sb.windowFps, "%.1f"));
        sb2.append(row("frame ms", sa.avgFrameMs, sb.avgFrameMs, "%.2f"));
        sb2.append(row("veh CPU agg ms/frame", sa.aggCpuMsPerFrameAvg, sb.aggCpuMsPerFrameAvg, "%.2f"));
        sb2.append(row("veh CPU p95 ms/frame", sa.aggCpuMsPerFrameP95, sb.aggCpuMsPerFrameP95, "%.2f"));
        sb2.append(row("per-veh us", sa.perVehicleCpuUs, sb.perVehicleCpuUs, "%.1f"));
        sb2.append(row("% frame (wall est)", sa.pctOfFrame, sb.pctOfFrame, "%.1f"));
        sb2.append(row("updated/frame", sa.updatedPerFrame, sb.updatedPerFrame, "%.1f"));
        sb2.append(row("instances/frame", sa.instancesPerFrame, sb.instancesPerFrame, "%.0f"));
        sb2.append(row("bakes/frame", sa.bakesPerFrame, sb.bakesPerFrame, "%.2f"));
        sb2.append(row("wake/s", sa.wakePerSec, sb.wakePerSec, "%.1f"));
        sb2.append(row("active", sa.stateActiveAvg, sb.stateActiveAvg, "%.1f"));
        sb2.append(row("settling", sa.stateSettlingAvg, sb.stateSettlingAvg, "%.1f"));
        sb2.append(row("dormant", sa.stateDormantAvg, sb.stateDormantAvg, "%.1f"));
        sb2.append("draw/GPU A: meshes=").append(sa.meshCount).append(" live=").append(sa.liveInstances)
                .append(" verts=").append(sa.totalVertices).append(" gpu=").append(sa.gpuBytes / 1024)
                .append("kB\n");
        sb2.append("draw/GPU B: meshes=").append(sb.meshCount).append(" live=").append(sb.liveInstances)
                .append(" verts=").append(sb.totalVertices).append(" gpu=").append(sb.gpuBytes / 1024)
                .append("kB (megabuffer targets instances/frame + gpu bytes)\n");
        sb2.append("=== end ===");
        return sb2.toString();
    }

    private static String row(String name, double a, double b, String fmt) {
        double delta = b - a;
        double pct = a != 0 ? (delta / a) * 100.0 : 0;
        return String.format("  %-22s A=" + fmt + "  B=" + fmt + "  d=" + fmt + " (%+.1f%%)%n",
                name, a, b, delta, pct);
    }

    private static void exportCsv(Run run) {
        if (run.summary == null) return;
        final Snapshot s = run.summary;
        final String label = run.label;
        final File dir = Minecraft.getInstance().gameDirectory;
        Thread t = new Thread(() -> {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            File out = new File(dir, "komodo-profile-" + label + "-" + stamp + ".csv");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write("metric,value\n");
                fw.write("label," + label + "\n");
                fw.write("frames," + s.frames + "\n");
                fw.write("fps," + s.windowFps + "\n");
                fw.write("mc_fps," + s.mcFps + "\n");
                fw.write("frame_ms," + s.avgFrameMs + "\n");
                fw.write("veh_cpu_agg_ms_per_frame_avg," + s.aggCpuMsPerFrameAvg + "\n");
                fw.write("veh_cpu_agg_ms_per_frame_p95," + s.aggCpuMsPerFrameP95 + "\n");
                fw.write("veh_cpu_agg_ms_per_frame_max," + s.aggCpuMsPerFrameMax + "\n");
                fw.write("wall_est_ms_per_frame," + s.wallEstMsPerFrame + "\n");
                fw.write("render_thread_ms_per_frame," + s.renderThreadMsPerFrame + "\n");
                fw.write("unaccounted_ms_per_frame," + s.unaccountedMsPerFrame + "\n");
                fw.write("gpu_garage_ms_per_frame," + s.gpuMsPerFrame + "\n");
                fw.write("top_phase," + s.topPhase + "\n");
                fw.write("top_phase_ms," + s.topPhaseMs + "\n");
                fw.write("per_vehicle_us," + s.perVehicleCpuUs + "\n");
                fw.write("pct_of_frame," + s.pctOfFrame + "\n");
                fw.write("worker_threads," + s.workerThreads + "\n");
                fw.write("processed_per_frame," + s.processedPerFrame + "\n");
                fw.write("updated_per_frame," + s.updatedPerFrame + "\n");
                fw.write("skipped_per_frame," + s.skippedPerFrame + "\n");
                fw.write("instances_per_frame," + s.instancesPerFrame + "\n");
                fw.write("bakes_per_frame," + s.bakesPerFrame + "\n");
                fw.write("wake_per_sec," + s.wakePerSec + "\n");
                fw.write("state_active_avg," + s.stateActiveAvg + "\n");
                fw.write("state_settling_avg," + s.stateSettlingAvg + "\n");
                fw.write("state_dormant_avg," + s.stateDormantAvg + "\n");
                fw.write("mesh_count," + s.meshCount + "\n");
                fw.write("live_instances," + s.liveInstances + "\n");
                fw.write("total_vertices," + s.totalVertices + "\n");
                fw.write("gpu_bytes," + s.gpuBytes + "\n");
                fw.write("garage_pools," + s.garagePools + "\n");
                fw.write("garage_slices," + s.garageSlices + "\n");
                fw.write("garage_holes," + s.garageHoles + "\n");
                fw.write("garage_gpu_bytes," + s.garageGpuBytes + "\n");
                for (Phase p : Phase.values()) {
                    int i = p.ordinal();
                    fw.write("phase_" + p.name().toLowerCase() + "_avg_ns," + s.phaseAvgNanos[i] + "\n");
                    fw.write("phase_" + p.name().toLowerCase() + "_p50_ns," + s.phaseP50Nanos[i] + "\n");
                    fw.write("phase_" + p.name().toLowerCase() + "_p95_ns," + s.phaseP95Nanos[i] + "\n");
                    fw.write("phase_" + p.name().toLowerCase() + "_max_ns," + s.phaseMaxNanos[i] + "\n");
                }
                Komodo.LOGGER.info("[KmodoProfiler] wrote CSV {}", out.getAbsolutePath());
            } catch (IOException e) {
                Komodo.LOGGER.warn("[KmodoProfiler] CSV write failed", e);
            }
        }, "komodo-profiler-csv");
        t.setDaemon(true);
        t.start();
    }
}
