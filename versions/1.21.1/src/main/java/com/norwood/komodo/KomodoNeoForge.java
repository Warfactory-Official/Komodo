package com.norwood.komodo;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;

import com.norwood.komodo.config.KomodoConfig;

@Mod(value = Komodo.MOD_ID, dist = Dist.CLIENT)
public final class KomodoNeoForge {

    public KomodoNeoForge(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, KomodoConfig.SPEC, "komodo.toml");
        modBus.addListener(this::onModConfig);
    }

    private void onModConfig(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == KomodoConfig.SPEC) {
            KomodoConfig.bake();
        }
    }
}
