package com.zonlong.beloong.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Locale;

/** 客户端 HUD：在屏幕下方 1/3 处显示宝藏价值和成长倍率，带渐入渐出动画 */
@EventBusSubscriber(modid = BeLoongCore.MODID, value = Dist.CLIENT)
public class TreasureGrowthOverlay {
    /** 渐变动画 alpha，0=完全透明，1=完全不透明 */
    private static float fadeAlpha;
    private static boolean wasResting;

    /** 每 tick 按方向 0.05 步长调整 fadeAlpha，实现平滑切换 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        boolean resting = TreasureGrowthClientData.isResting() && TreasureGrowthClientData.hasRecentData();

        if (resting && fadeAlpha < 1.0f) {
            fadeAlpha = Math.min(1.0f, fadeAlpha + 0.05f);
        } else if (!resting && fadeAlpha > 0.0f) {
            fadeAlpha = Math.max(0.0f, fadeAlpha - 0.05f);
        }

        wasResting = resting;
    }

    /** 在 AIR_LEVEL 层之上渲染半透明背景 + 金色财宝值文字 + 绿色倍率文字 */
    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiLayerEvent.Post event) {
        if (event.getName() != VanillaGuiLayers.AIR_LEVEL) return;
        if (fadeAlpha <= 0.01f) return;
        if (!Config.TreasureGrowth.enabled.get()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.isSpectator()) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int textY = mc.getWindow().getGuiScaledHeight() / 3;

        double value = TreasureGrowthClientData.getTreasureValue();
        double multiplier = TreasureGrowthClientData.getGrowthMultiplier();

        String valueText = String.format(Locale.ROOT, "%.1f", value);
        String multText = String.format(Locale.ROOT, "%.1fx", multiplier);

        Component valueLine = Component.translatable("overlay.beloong.treasure_value", valueText);
        Component multLine = Component.translatable("overlay.beloong.growth_multiplier", multText);

        int fontW1 = mc.font.width(valueLine);
        int fontW2 = mc.font.width(multLine);
        int bgW = Math.max(fontW1, fontW2) + 16;
        int bgH = mc.font.lineHeight * 2 + 12;

        int bgX = (screenW - bgW) / 2;
        int bgY = textY - mc.font.lineHeight - 8;

        int alpha = (int) (fadeAlpha * 102);
        int bgColor = (alpha << 24);

        graphics.fill(bgX, bgY, bgX + bgW, bgY + bgH, bgColor);

        RenderSystem.enableBlend();
        int valueColor = (int) (fadeAlpha * 255) << 24 | 0xFFD700;
        int multColor = (int) (fadeAlpha * 255) << 24 | 0x55FF55;

        graphics.drawCenteredString(mc.font, valueLine, screenW / 2, bgY + 4, valueColor);
        graphics.drawCenteredString(mc.font, multLine, screenW / 2, bgY + 4 + mc.font.lineHeight + 2, multColor);
        RenderSystem.disableBlend();
    }
}
