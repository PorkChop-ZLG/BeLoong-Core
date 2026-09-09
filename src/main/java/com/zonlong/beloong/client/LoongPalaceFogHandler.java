package com.zonlong.beloong.client;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 龙宫维度禁用客户端雾效的处理器。
 *
 * <p>使用 NeoForge 官方 {@link ViewportEvent.RenderFog}，
 * 在龙宫将雾距离设为极大并取消原版雾渲染。</p>
 */
public class LoongPalaceFogHandler {

    private static final ResourceKey<Level> LOONG_PALACE = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace")
    );

    @SubscribeEvent
    public void onRenderFog(ViewportEvent.RenderFog event) {
        var level = Minecraft.getInstance().level;
        if (level == null || !LOONG_PALACE.equals(level.dimension())) {
            return;
        }
        event.setNearPlaneDistance(10000.0F);
        event.setFarPlaneDistance(10000.0F);
        event.setCanceled(true);
    }
}
