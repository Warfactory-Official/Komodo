package com.norwood.komodo.client.render.kmodo;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;

import com.atsuishio.superbwarfare.entity.vehicle.base.AutoAimableEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;

import com.norwood.komodo.config.KomodoConfig;

public final class KmodoDormancy {

    private static final int STABLE_FRAMES = 3;
    private static final int MIN_PROBE_INTERVAL = 20;
    private static final int MAX_PROBE_INTERVAL = 160;
    private static final String NO_TARGET = "undefined";

    private static final double MOVE_EPS_SQ = 1.0e-4;
    private static final double POS_EPS = 1.0e-3;
    private static final float ROT_EPS = 0.05f;

    private boolean dormant;
    private boolean dormancyBlocked;
    private int stableCount;
    private long lastHash;
    private boolean hasHash;
    private long lastProbeTick;
    private int probeInterval = MIN_PROBE_INTERVAL;
    private boolean probePending;

    private boolean probeAllowed;
    private int probePolicyGen = -1;

    public boolean needsUpdate(GeoVehicleEntity e, boolean visualMoved) {
        if (!KmodoConfig.dormancyEnabled()) {
            dormant = false;
            dormancyBlocked = false;
            return true;
        }

        dormancyBlocked = occupied(e);
        if (!dormant) {
            return true;
        }
        if (visualMoved || dormancyBlocked || wakeSignal(e)) {
            wake();
            return true;
        }
        if (!probeAllowed(e)) {

            return false;
        }
        long now = e.tickCount;
        int phase = Math.floorMod(e.getId(), probeInterval);
        if (now != lastProbeTick && Math.floorMod(now, probeInterval) == phase) {
            lastProbeTick = now;
            probePending = true;
            return true;
        }
        return false;
    }

    public void recordPose(long hash, long tick) {
        if (!KmodoConfig.dormancyEnabled()) {
            dormant = false;
            lastHash = hash;
            hasHash = true;
            lastProbeTick = tick;
            probeInterval = MIN_PROBE_INTERVAL;
            probePending = false;
            return;
        }
        if (dormancyBlocked) {

            stableCount = 0;
            dormant = false;
            probeInterval = MIN_PROBE_INTERVAL;
        } else if (hasHash && hash == lastHash) {
            if (!dormant && ++stableCount >= STABLE_FRAMES) {
                dormant = true;
            }

            if (probePending && probeInterval < MAX_PROBE_INTERVAL) {
                probeInterval = Math.min(probeInterval * 2, MAX_PROBE_INTERVAL);
            }
        } else {
            stableCount = 0;
            dormant = false;
            probeInterval = MIN_PROBE_INTERVAL;
        }
        lastHash = hash;
        hasHash = true;
        lastProbeTick = tick;
        probePending = false;
    }

    public boolean isDormant() {
        return dormant && KmodoConfig.dormancyEnabled();
    }

    public enum State {
        ACTIVE,
        SETTLING,
        DORMANT
    }

    public State state() {
        if (!KmodoConfig.dormancyEnabled()) {
            return State.ACTIVE;
        }
        if (dormant) {
            return State.DORMANT;
        }
        if (stableCount > 0) {
            return State.SETTLING;
        }
        return State.ACTIVE;
    }

    private void wake() {
        dormant = false;
        stableCount = 0;
        probeInterval = MIN_PROBE_INTERVAL;
        probePending = false;
    }

    private boolean probeAllowed(GeoVehicleEntity e) {
        int gen = KomodoConfig.probeVehiclesGeneration();
        if (gen != probePolicyGen) {
            probePolicyGen = gen;
            EntityType<?> type = e.getType();
            probeAllowed = KomodoConfig.shouldProbeVehicle(EntityType.getKey(type).toString());
        }
        return probeAllowed;
    }

    private static boolean occupied(GeoVehicleEntity e) {
        return e.isVehicle() || e.getFirstPassenger() != null;
    }

    private boolean wakeSignal(GeoVehicleEntity e) {
        if (e.getDeltaMovement().lengthSqr() > MOVE_EPS_SQ) {
            return true;
        }
        if (Math.abs(e.getX() - e.xOld) > POS_EPS
                || Math.abs(e.getY() - e.yOld) > POS_EPS
                || Math.abs(e.getZ() - e.zOld) > POS_EPS) {
            return true;
        }
        if (Math.abs(Mth.degreesDifference(e.yRotO, e.getYRot())) > ROT_EPS
                || Math.abs(Mth.degreesDifference(e.xRotO, e.getXRot())) > ROT_EPS) {
            return true;
        }
        if (Math.abs(Mth.degreesDifference(e.getPrevRoll(), e.getRoll())) > ROT_EPS) {
            return true;
        }
        if (Math.abs(Mth.degreesDifference(e.getTurretYRotO(), e.getTurretYRot())) > ROT_EPS
                || Math.abs(Mth.degreesDifference(e.getTurretXRotO(), e.getTurretXRot())) > ROT_EPS) {
            return true;
        }
        if (Math.abs(Mth.degreesDifference(e.getGunYRotO(), e.getGunYRot())) > ROT_EPS
                || Math.abs(Mth.degreesDifference(e.getGunXRotO(), e.getGunXRot())) > ROT_EPS) {
            return true;
        }
        if (e.getCannonRecoilTime() > 0) {
            return true;
        }
        if (!NO_TARGET.equals(e.getAiTurretTargetUUID()) || !NO_TARGET.equals(e.getAiPassengerWeaponTargetUUID())) {
            return true;
        }
        if (e instanceof AutoAimableEntity a && a.getActive()) {
            return true;
        }
        return e.isOnFire();
    }
}
