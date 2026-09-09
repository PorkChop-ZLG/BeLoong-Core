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

    private static int eventCounter;

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        boolean isClient = event.getLevel() instanceof ClientLevel;
        boolean isLoong = isClient
                && LOONG_PALACE.equals(((ClientLevel) event.getLevel()).dimension());

        eventCounter++;
        if (eventCounter % 100 == 0 || isLoong) {
            String levelClass = event.getLevel().getClass().getSimpleName();
            String dim = event.getLevel().dimension().location().toString();
            BeLoongCore.LOGGER.info(
                    "[SkyDebug] tickHandler called count={} level={} dim={} client={} loong={}",
                    eventCounter, levelClass, dim, isClient, isLoong
            );
        }

        if (isLoong) {
            DramaticSkyRenderer.tick((ClientLevel) event.getLevel());
        }
    }
}
