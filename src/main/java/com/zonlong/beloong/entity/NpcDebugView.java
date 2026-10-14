package com.zonlong.beloong.entity;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * <b>调试视角开关</b>（2026-10-14，设计 D8/D9）：开启后该玩家**看得见所有 NPC**
 * （包括永不显示的公共锚点、以及别人的私有分身），用于整合包作者校对锚点位置与分身系统。
 *
 * <h2>它有意打破的不变量</h2>
 * 正常情况下"任一玩家眼里恰好一个 NPC"（见 {@link NpcEntity#visibleTo} 的注释）；
 * 调试视角**故意**让该玩家看到全部 —— 它只改可见性，**不改交互**
 * （私有分身仍然只认主人，见 {@code NpcDialogueHandler} 的交互闸门）⇒ 看得到、点不动，
 * 这既够校对用，又不会让调试玩家误动别人的剧情。
 *
 * <h2>持久化与缓存</h2>
 * 开关存在玩家的 {@code getPersistentData()} 里（与 {@code teleport/TeleportCooldown} 同款，
 * 这是本仓库既有的先例）；同时缓存一份内存集合，因为 {@link NpcEntity#visibleTo}
 * **每个追踪周期、对范围内每个玩家**都会被调用，不能每次去读 NBT。
 * 缓存由登录/登出事件维护：登录时按存档值装载、登出时清掉（避免 UUID 残留）。
 *
 * <p>⚠️ 用户 2026-10-14 裁定：**不加权限检查**（任何玩家都能开）—— 它只影响"看得见什么"，
 * 不影响任何实体状态或交互。
 */
public final class NpcDebugView {

    /** 玩家持久数据里的键。 */
    private static final String KEY = "BeloongNpcDebugView";

    /** 已开启调试视角的玩家（内存缓存，见类注释）。 */
    private static final Set<UUID> ON = new HashSet<>();

    /** 该玩家是否开着调试视角。**纯读**，可在 {@code visibleTo} 里放心调用。 */
    public static boolean isOn(ServerPlayer player) {
        return ON.contains(player.getUUID());
    }

    /** 切换并写回持久数据；返回切换**之后**的状态。 */
    public static boolean toggle(ServerPlayer player) {
        boolean now = !ON.contains(player.getUUID());
        if (now) {
            ON.add(player.getUUID());
        } else {
            ON.remove(player.getUUID());
        }
        player.getPersistentData().putBoolean(KEY, now);
        return now;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && player.getPersistentData().getBoolean(KEY)) {
            ON.add(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ON.remove(event.getEntity().getUUID());
    }
}
