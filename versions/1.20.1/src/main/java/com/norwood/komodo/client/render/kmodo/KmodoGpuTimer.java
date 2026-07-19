package com.norwood.komodo.client.render.kmodo;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

public final class KmodoGpuTimer {

    private KmodoGpuTimer() {}

    private static final int RING = 4;

    private static int[] queries;
    private static boolean[] issued;
    private static int head;
    private static boolean active;
    private static volatile long avgNanos;

    public static void begin() {
        if (!RenderSystem.isOnRenderThread() || active) {
            return;
        }
        if (queries == null) {
            queries = new int[RING];
            issued = new boolean[RING];
            for (int i = 0; i < RING; i++) {
                queries[i] = GL15.glGenQueries();
            }
        }
        int q = queries[head];
        if (issued[head] && GL15.glGetQueryObjecti(q, GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
            long r = GL33.glGetQueryObjecti64(q, GL15.GL_QUERY_RESULT);
            avgNanos = avgNanos == 0 ? r : (long) (avgNanos * 0.9 + r * 0.1);
        }
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, q);
        active = true;
    }

    public static void end() {
        if (!active || !RenderSystem.isOnRenderThread()) {
            return;
        }
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        issued[head] = true;
        head = (head + 1) % RING;
        active = false;
    }

    public static long avgNanos() {
        return avgNanos;
    }

    public static void dispose() {
        if (queries != null && RenderSystem.isOnRenderThread()) {
            if (active) {
                GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
                active = false;
            }
            for (int q : queries) {
                GL15.glDeleteQueries(q);
            }
        }
        queries = null;
        issued = null;
        head = 0;
        avgNanos = 0;
    }
}
