package com.norwood.komodo;

import net.minecraft.resources.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class Komodo {

    public static final String MOD_ID = "komodo";
    public static final boolean DEBUG = false;
    public static final Logger LOGGER = LogManager.getLogger("Komodo");

    private Komodo() {}

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
