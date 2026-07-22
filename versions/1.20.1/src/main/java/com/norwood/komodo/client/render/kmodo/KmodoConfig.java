package com.norwood.komodo.client.render.kmodo;

import net.minecraftforge.fml.ModList;

public final class KmodoConfig {

    private KmodoConfig() {}

    private static volatile boolean RETAIN = true;
    private static volatile boolean FLYWHEEL = true;
    private static volatile boolean DORMANCY = true;
    private static volatile boolean GARAGE = false;

    public static boolean retainEnabled() {
        return RETAIN;
    }

    public static void setRetain(boolean enabled) {
        RETAIN = enabled;
    }

    public static boolean dormancyEnabled() {
        return DORMANCY;
    }

    public static void setDormancy(boolean enabled) {
        DORMANCY = enabled;
    }

    public static boolean flywheelEnabled() {
        return FLYWHEEL;
    }

    public static void setFlywheel(boolean enabled) {
        FLYWHEEL = enabled;
    }

    public static boolean garageEnabled() {
        return GARAGE;
    }

    public static void setGarage(boolean enabled) {
        GARAGE = enabled;
    }

    public static boolean rawDrawAllowed() {
        return !shaderPackActive();
    }

    private static Boolean foreignFrustum;

    public static boolean frustumCullTrusted() {
        if (foreignFrustum == null) {
            foreignFrustum = ModList.get().isLoaded("voxy") || ModList.get().isLoaded("boxy");
        }
        return !foreignFrustum;
    }

    private static Boolean irisLoaded;

    private static boolean shaderPackActive() {
        try {
            if (irisLoaded == null) {
                irisLoaded = ModList.get().isLoaded("oculus")
                        || ModList.get().isLoaded("iris")
                        || ModList.get().isLoaded("irisflw");
            }
            if (!irisLoaded) {
                return false;
            }

            for (String className : new String[]{
                    "net.irisshaders.iris.api.v0.IrisApi",
                    "net.coderbot.iris.api.v0.IrisApi"}) {
                try {
                    Class<?> api = Class.forName(className);
                    Object instance = api.getMethod("getInstance").invoke(null);
                    return (Boolean) api.getMethod("isShaderPackInUse").invoke(instance);
                } catch (ClassNotFoundException ignored) {

                }
            }
        } catch (Throwable ignored) {

        }
        return false;
    }
}
