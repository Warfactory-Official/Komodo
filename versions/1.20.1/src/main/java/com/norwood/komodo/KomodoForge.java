package com.norwood.komodo;

import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import com.norwood.komodo.config.KomodoConfig;

@Mod(Komodo.MOD_ID)
public final class KomodoForge {

    public KomodoForge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, KomodoConfig.SPEC, "komodo.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onModConfig);
    }

    private void onModConfig(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == KomodoConfig.SPEC) {
            KomodoConfig.bake();
        }
    }
}
