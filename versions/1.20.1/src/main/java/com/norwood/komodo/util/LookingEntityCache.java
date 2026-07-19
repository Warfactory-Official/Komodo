package com.norwood.komodo.util;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.Entity;

/**
 * Per-thread, per-tick memoization for Superb Warfare's
 * {@code TraceTool.findLookingEntity(Entity, double)}.
 *
 * <p>{@code findLookingEntity} samples positions at {@code partialTick = 1.0f}, so its result only
 * changes once per game tick. The client HUD, however, calls it many times per <em>frame</em> from
 * several independent overlays (crosshair, mortar, rocket, type63, spyglass...) plus
 * {@code OBB.getLookingObb}, each performing a full {@code Level.getEntities} + per-entity clip test.
 * Caching by {@code (entity, reach)} for the duration of a tick collapses those identical raycasts
 * into one and reuses the result across every frame rendered within that tick, with no behavior
 * change (same tick-granular inputs -> same output).
 *
 * <p>State is held in a {@link ThreadLocal} so client and server callers never contend, and the map
 * is cleared whenever the game time advances, so it never grows or leaks.
 */
public final class LookingEntityCache {

    private LookingEntityCache() {
    }

    /** Sentinel distinguishing "no cached value" from a cached {@code null} (a valid "looking at nothing"). */
    public static final Object MISS = new Object();

    private static final class Cache {
        long gen = Long.MIN_VALUE;
        final Map<Long, Entry> map = new HashMap<>();
    }

    private static final class Entry {
        Entity entity;
        double reach;
        Entity result;
    }

    private static final ThreadLocal<Cache> LOCAL = ThreadLocal.withInitial(Cache::new);

    private static long key(Entity entity, double reach) {
        return ((long) entity.getId() << 32) ^ Double.doubleToLongBits(reach);
    }

    private static Cache current(Entity entity) {
        Cache c = LOCAL.get();
        long gen = entity.level().getGameTime();
        if (c.gen != gen) {
            c.map.clear();
            c.gen = gen;
        }
        return c;
    }

    /** @return the cached result for this tick (possibly {@code null}), or {@link #MISS} if absent. */
    public static Object get(Entity entity, double reach) {
        Cache c = current(entity);
        Entry e = c.map.get(key(entity, reach));
        if (e != null && e.entity == entity && e.reach == reach) {
            return e.result;
        }
        return MISS;
    }

    public static void put(Entity entity, double reach, Entity result) {
        Cache c = current(entity);
        long k = key(entity, reach);
        Entry e = c.map.get(k);
        if (e == null) {
            e = new Entry();
            c.map.put(k, e);
        }
        e.entity = entity;
        e.reach = reach;
        e.result = result;
    }
}
