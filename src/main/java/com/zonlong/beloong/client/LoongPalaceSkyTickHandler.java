package com.zonlong.beloong.client;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.sky.DramaticSkyRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 龙宫天空 alpha 平滑更新处理器。
 */
public class LoongPalaceSkyTickHandler {

    private static final ResourceKey<Level> LOONG_PALACE = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace")
    );

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ClientLevel clientLevel)) {
            return;
        }
        if (!LOONG_PALACE.equals(clientLevel.dimension())) {
            return;
        }
        DramaticSkyRenderer.tick(clientLevel);
    }
}
