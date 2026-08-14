package com.zonlong.beloong.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 客户端流星火雨状态缓存。
 * <p>
 * 由 {@code MeteorRainSyncPayload} 处理器更新，供渲染层（雾效/蒙层）查询，
 * 并在状态翻转时向玩家显示开始/结束警告。
 */
public class ClientMeteorRainState {

    private static boolean active;

    private ClientMeteorRainState() {}

    public static boolean isActive() {
        return active;
    }

    /** 更新状态；仅在翻转时触发一次警告提示。 */
    public static void update(boolean newActive) {
        if (active == newActive) {
            return;
        }
        active = newActive;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.translatable(newActive
                            ? "message.beloong.meteorrain.start"
                            : "message.beloong.meteorrain.end"),
                    false);
        }
    }
}
