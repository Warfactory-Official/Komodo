package com.norwood.komodo.client.render.kmodo;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import com.norwood.komodo.Komodo;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

public final class KmodoGaragePool {

    static final int VERTEX_STRIDE = 36;
    private static final int INITIAL_CAPACITY = 16;
    private static final float FRAG_THRESHOLD = 0.4f;
    private static final int MAX_COMPACT_MOVES = 2;

    private final ResourceLocation res;
    private final ResourceLocation texture;
    private final int slotVerts;
    private final int slotBytes;
    private final byte[] degenerateSlot;

    private int vbo = -1;
    private int vao = -1;
    private int capacity;
    private int highWater;

    private final Int2IntOpenHashMap entityToSlot = new Int2IntOpenHashMap();
    private int[] slotToEntity;
    private int[] slotLight;
    private final IntArrayList freeStack = new IntArrayList();

    private KmodoGaragePool(ResourceLocation res, ResourceLocation texture, int slotVerts) {
        this.res = res;
        this.texture = texture;
        this.slotVerts = slotVerts;
        this.slotBytes = slotVerts * VERTEX_STRIDE;
        this.degenerateSlot = buildDegenerateSlot(slotVerts);
        this.capacity = INITIAL_CAPACITY;
        this.highWater = 0;
        this.slotToEntity = new int[capacity];
        this.slotLight = new int[capacity];
        java.util.Arrays.fill(slotToEntity, -1);
        java.util.Arrays.fill(slotLight, -1);
        this.entityToSlot.defaultReturnValue(-1);
        createGl();
    }

    public static KmodoGaragePool create(ResourceLocation res, ResourceLocation texture, int slotVerts) {
        if (slotVerts <= 0 || slotVerts % 4 != 0) {
            Komodo.LOGGER.warn("[komodo] Kmodo garage pool rejected {}: slotVerts={} not a positive multiple of 4",
                    res, slotVerts);
            return null;
        }
        return new KmodoGaragePool(res, texture, slotVerts);
    }

    private static byte[] buildDegenerateSlot(int slotVerts) {
        byte[] slot = new byte[slotVerts * VERTEX_STRIDE];
        for (int v = 0; v < slotVerts; v++) {
            int base = v * VERTEX_STRIDE;
            slot[base + 33] = (byte) 127;
        }
        return slot;
    }

    private void createGl() {
        vbo = GL15.glGenBuffers();
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) capacity * slotBytes, GL15.GL_DYNAMIC_DRAW);
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        DefaultVertexFormat.NEW_ENTITY.clearBufferState();
    }

    public ResourceLocation res() {
        return res;
    }

    public ResourceLocation texture() {
        return texture;
    }

    public int slotVerts() {
        return slotVerts;
    }

    public int liveCount() {
        return entityToSlot.size();
    }

    public int highWater() {
        return highWater;
    }

    public int holes() {
        return freeStack.size();
    }

    public long gpuBytes() {
        return (long) capacity * slotBytes;
    }

    public boolean contains(int entityId) {
        return entityToSlot.containsKey(entityId);
    }

    public float fragMetric() {
        return highWater == 0 ? 0f : (float) freeStack.size() / (float) highWater;
    }

    public boolean alloc(int entityId, ByteBuffer sliceVerts, int packedLight) {
        if (entityToSlot.containsKey(entityId)) {
            return false;
        }
        int sliceBytes = sliceVerts.remaining();
        if (sliceBytes != slotBytes) {
            Komodo.LOGGER.warn("[komodo] Kmodo garage pool {} rejected entity {}: slice {} bytes != slot {} bytes",
                    res, entityId, sliceBytes, slotBytes);
            return false;
        }
        int slot;
        if (freeStack.isEmpty()) {
            if (highWater >= capacity) {
                grow();
            }
            slot = highWater++;
        } else {
            slot = freeStack.popInt();
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) slot * slotBytes, sliceVerts);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        slotToEntity[slot] = entityId;
        slotLight[slot] = packedLight;
        entityToSlot.put(entityId, slot);
        return true;
    }

    public void free(int entityId) {
        if (!entityToSlot.containsKey(entityId)) {
            return;
        }
        int slot = entityToSlot.remove(entityId);
        slotToEntity[slot] = -1;
        slotLight[slot] = -1;
        writeDegenerate(slot);
        if (slot == highWater - 1) {
            highWater--;
            while (highWater > 0 && slotToEntity[highWater - 1] < 0) {
                freeStack.rem(highWater - 1);
                highWater--;
            }
        } else {
            freeStack.push(slot);
        }
    }

    public void freeMissing(it.unimi.dsi.fastutil.ints.IntSet liveIds) {
        if (entityToSlot.isEmpty()) {
            return;
        }
        int[] ids = entityToSlot.keySet().toIntArray();
        for (int id : ids) {
            if (!liveIds.contains(id)) {
                free(id);
            }
        }
    }

    public void relight(int entityId, int packedLight, ByteBuffer sliceVerts) {
        if (!entityToSlot.containsKey(entityId)) {
            return;
        }
        int slot = entityToSlot.get(entityId);
        if (slotLight[slot] == packedLight) {
            return;
        }
        if (sliceVerts.remaining() != slotBytes) {
            return;
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) slot * slotBytes, sliceVerts);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        slotLight[slot] = packedLight;
    }

    private void writeDegenerate(int slot) {
        ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(slotBytes);
        try {
            buf.put(degenerateSlot);
            buf.flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) slot * slotBytes, buf);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    private void grow() {
        int newCapacity = capacity * 2;
        int newVbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, newVbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) newCapacity * slotBytes, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        GL31.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo);
        GL31.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, newVbo);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L,
                (long) highWater * slotBytes);
        GL31.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
        GL31.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

        GL15.glDeleteBuffers(vbo);
        vbo = newVbo;

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        DefaultVertexFormat.NEW_ENTITY.setupBufferState();
        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        DefaultVertexFormat.NEW_ENTITY.clearBufferState();

        int[] newSlotToEntity = new int[newCapacity];
        int[] newSlotLight = new int[newCapacity];
        java.util.Arrays.fill(newSlotToEntity, -1);
        java.util.Arrays.fill(newSlotLight, -1);
        System.arraycopy(slotToEntity, 0, newSlotToEntity, 0, capacity);
        System.arraycopy(slotLight, 0, newSlotLight, 0, capacity);
        slotToEntity = newSlotToEntity;
        slotLight = newSlotLight;
        capacity = newCapacity;
    }

    public int compactStep() {
        int moves = 0;
        while (highWater > 0 && slotToEntity[highWater - 1] < 0) {
            freeStack.rem(highWater - 1);
            highWater--;
        }
        if (fragMetric() <= FRAG_THRESHOLD) {
            return 0;
        }
        while (moves < MAX_COMPACT_MOVES && !freeStack.isEmpty()) {
            while (highWater > 0 && slotToEntity[highWater - 1] < 0) {
                freeStack.rem(highWater - 1);
                highWater--;
            }
            if (freeStack.isEmpty() || highWater == 0) {
                break;
            }
            int src = highWater - 1;
            int dst = lowestHole();
            if (dst < 0 || dst >= src) {
                break;
            }
            int entity = slotToEntity[src];
            GL31.glBindBuffer(GL31.GL_COPY_READ_BUFFER, vbo);
            GL31.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, vbo);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER,
                    (long) src * slotBytes, (long) dst * slotBytes, slotBytes);
            GL31.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            GL31.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);

            freeStack.rem(dst);
            slotToEntity[dst] = entity;
            slotLight[dst] = slotLight[src];
            entityToSlot.put(entity, dst);
            slotToEntity[src] = -1;
            slotLight[src] = -1;
            highWater--;
            moves++;
        }
        return moves;
    }

    private int lowestHole() {
        int lowest = -1;
        for (int i = 0; i < freeStack.size(); i++) {
            int s = freeStack.getInt(i);
            if (lowest < 0 || s < lowest) {
                lowest = s;
            }
        }
        return lowest;
    }

    public void draw(ShaderInstance shader, Matrix4f modelView, Matrix4f projection) {
        if (highWater == 0) {
            return;
        }
        int indexCount = highWater * slotVerts / 4 * 6;
        RenderSystem.AutoStorageIndexBuffer seq = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        GL30.glBindVertexArray(vao);
        seq.bind(indexCount);
        applyShader(shader, modelView, projection);
        RenderSystem.drawElements(GL11.GL_TRIANGLES, indexCount, seq.type().asGLType);
        shader.clear();
    }

    private void applyShader(ShaderInstance shader, Matrix4f modelView, Matrix4f projection) {
        for (int i = 0; i < 12; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.GLINT_ALPHA != null) {
            shader.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        RenderSystem.setupShaderLights(shader);
        shader.apply();
    }

    public void delete() {
        if (vbo >= 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = -1;
        }
        if (vao >= 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = -1;
        }
        entityToSlot.clear();
        freeStack.clear();
        highWater = 0;
    }
}
