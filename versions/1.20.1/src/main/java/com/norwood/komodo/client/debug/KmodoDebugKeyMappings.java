package com.norwood.komodo.client.debug;

import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.norwood.komodo.Komodo;

@Mod.EventBusSubscriber(modid = Komodo.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class KmodoDebugKeyMappings {

    private KmodoDebugKeyMappings() {}

    private static final String CATEGORY = "key.categories.komodo.debug";

    public static final KeyMapping TOGGLE = new KeyMapping(
            "key.komodo.kmodo_debug.toggle",
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    public static final KeyMapping DUMP = new KeyMapping(
            "key.komodo.kmodo_debug.dump",
            InputConstants.UNKNOWN.getValue(),
            CATEGORY);

    @SubscribeEvent
    public static void onRegister(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(DUMP);
    }
}
