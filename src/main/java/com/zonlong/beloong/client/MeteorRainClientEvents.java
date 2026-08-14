package com.zonlong.beloong.client;

import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 流星火雨客户端氛围渲染事件处理器。
 * <p>
 * 订阅 game 总线（{@code NeoForge.EVENT_BUS}）事件，在流星火雨激活时：
 * <ul>
 *   <li>{@link ViewportEvent.ComputeFogColor} — 将雾效染成暗红色</li>
 *   <li>{@link RenderGuiEvent.Post} — 绘制全屏半透明暗红蒙层</li>
 * </ul>
 * 通过 {@code NeoForge.EVENT_BUS.register(new MeteorRainClientEvents())} 在
 * {@link com.zonlong.beloong.BeLoongCoreClient} 构造函数中注册。
 */
public class MeteorRainClientEvents {

    @SubscribeEvent
    public void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!ClientMeteorRainState.isActive()) {
            return;
        }
        event.setRed(0.55F);
        event.setGreen(0.12F);
        event.setBlue(0.10F);
    }

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        if (!ClientMeteorRainState.isActive()) {
            return;
        }
        GuiGraphics gui = event.getGuiGraphics();
        // 0x2E = 约 18% 透明度，暗红蒙层
        gui.fill(0, 0, gui.guiWidth(), gui.guiHeight(), 0x2EFF0A00);
    }
}
