package com.zonlong.beloong.block;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 龙宫传送门「无法传送」时的玩家提示。
 * <p>
 * <b>三种失败</b>（用户指定）：
 * <ul>
 *   <li>{@link Failure#NON_DRAGON} —— 玩家不是龙（{@code DragonStateProvider.isDragon} 为假）；</li>
 *   <li>{@link Failure#WRONG_DIMENSION} —— 当前维度不在"仅主世界 ↔ 龙宫"的允许集合里；</li>
 *   <li>{@link Failure#TELEPORT_FAILED} —— API 返回 null（防御性，实际不会发生）。</li>
 * </ul>
 * <b>为什么需要节流</b>：门是"持续接触"的方块，{@link LoongPalacePortalBlock#entityInside} 每 tick 都可能触发；
 * 不节流会把玩家的聊天框刷满。这里按<b>每玩家 {@value #THROTTLE_TICKS} tick 一条</b>限流，
 * 并统一用 <b>actionbar</b>（{@code displayClientMessage(msg, true)}）——actionbar 会被新消息直接替换，
 * 不会像聊天框那样排成行。
 * <p>
 * 日志分级：前两种是"设计内的正常拒绝"，记 DEBUG；{@code TELEPORT_FAILED} 属于不该发生的分支，记 ERROR。
 */
final class LoongPalacePortalNotifier {

    /** 不是龙。 */
    static final String MSG_NOT_A_DRAGON = "message.beloong.loong_palace_portal.not_a_dragon";
    /** 当前维度不允许（仅主世界 ↔ 龙宫）。 */
    static final String MSG_WRONG_DIMENSION = "message.beloong.loong_palace_portal.wrong_dimension";
    /** 传送 API 返回 null（防御性）。 */
    static final String MSG_TELEPORT_FAILED = "message.beloong.loong_palace_portal.teleport_failed";

    /** 同玩家两条提示之间的最小间隔（ticks）。 */
    private static final long THROTTLE_TICKS = 20;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个玩家"下一次允许提示"的游戏刻。登出时清理。 */
    private static final Map<UUID, Long> NEXT_ALLOWED = new HashMap<>();

    private LoongPalacePortalNotifier() {}

    /** 失败原因（决定日志级别）。 */
    enum Failure {
        NON_DRAGON,
        WRONG_DIMENSION,
        TELEPORT_FAILED
    }

    /**
     * 通知玩家本次无法传送（受节流约束）。
     *
     * @param player     目标玩家
     * @param messageKey {@code message.beloong.loong_palace_portal.*} 之一
     * @param reason     失败原因，仅用于日志分级
     */
    static void notify(ServerPlayer player, String messageKey, Failure reason) {
        long now = player.level().getGameTime();
        long nextAllowed = NEXT_ALLOWED.getOrDefault(player.getUUID(), 0L);
        if (now < nextAllowed) {
            return;
        }
        NEXT_ALLOWED.put(player.getUUID(), now + THROTTLE_TICKS);

        player.displayClientMessage(Component.translatable(messageKey), true);

        if (reason == Failure.TELEPORT_FAILED) {
            LOGGER.error("[BeLoongCore] loong palace portal rejected teleport for {} (reason {})",
                    player.getName().getString(), reason);
        } else {
            LOGGER.debug("[BeLoongCore] loong palace portal rejected teleport for {} (reason {})",
                    player.getName().getString(), reason);
        }
    }

    /** 玩家登出时清理节流状态（挂到 {@code PlayerLoggedOutEvent}）。 */
    static void clear(UUID playerId) {
        NEXT_ALLOWED.remove(playerId);
    }
}
