package com.zonlong.beloong.teleport;

import com.zonlong.beloong.Config;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * 传送冷却：<b>本模组所有跨维度传送共用的那一份</b>。
 * <p>
 * 键名与语义都沿用天灾传送门既有实现（<b>键名不得更改</b>：它写在玩家持久化 NBT 里，跨维度、跨重登有效）。
 * 三条调用方——天灾门、龙宫技能、龙宫 Y&lt;0 兜底——读/写<b>同一个键</b>，
 * 因此它们互不绕过：刚用技能去过龙宫，紧接着进门也会被拦住（反之亦然）。
 * <p>
 * 时长取 {@link Config.DisasterPortal#teleportCooldownTicks}（默认 100 tick）。
 * <p>
 * <b>前提（时间基准）</b>：本类用 {@code player.level().getGameTime()} 作为时间基准，
 * 而跨维度后 {@code player.level()} 已切到目标维度。当前三个相关维度（主世界、龙宫、天灾）
 * 都是 {@code natural: true} 且由同一服务端推进，{@code getGameTime()} 同步递增，故功能成立；
 * 若将来出现 {@code natural: false} 的维度、或用指令单独改动某维度时间，冷却时长会出现偏差。
 * <p>
 * <b>与传送门方块的配合</b>：门方块还需要额外"把原版冷却顶住"（见
 * {@link #refreshVanillaToOutlastNbt}），因为原版<b>玩家</b>冷却只有 10 tick
 * （{@code Player#getDimensionChangingDelay} 覆写 {@code Entity} 的 300），撑不过 NBT 冷却。
 * 技能与 Y&lt;0 兜底路径不经过原版门流程，<b>不需要</b>该方法。
 */
public final class TeleportCooldown {

    /** NBT 键名。<b>不得更改</b>：改了等于让所有已存在的冷却失效。 */
    public static final String KEY = "beloong_portal_cooldown";

    private TeleportCooldown() {}

    /** 玩家是否仍在冷却中。 */
    public static boolean isOnCooldown(ServerPlayer player) {
        return player.getPersistentData().getLong(KEY) > player.level().getGameTime();
    }

    /** 记一次冷却：从现在起 {@code teleportCooldownTicks} 内不再允许传送。 */
    public static void mark(ServerPlayer player) {
        player.getPersistentData().putLong(KEY,
                player.level().getGameTime() + Config.DisasterPortal.teleportCooldownTicks.get());
    }

    /**
     * 把<b>原版</b>传送冷却顶到"比 NBT 冷却晚 1 tick 到期"。
     * <p>
     * <b>为什么需要</b>：原版 {@code Entity.handlePortal} 在询问落点<b>之前</b>就设置了传送冷却，
     * 而 {@code setAsInsidePortal} 在冷却期内只刷新冷却、不登记 {@code insidePortalThisTick}。
     * 若不顶住，NBT 冷却一到期就会立刻重新登记并重试——玩家站在门里时会无界重试，
     * 每轮都刷一次失败日志与玩家提示。
     * <p>
     * <b>仅门方块调用</b>：本方法的目的是与"玩家持续站在门里"这一情形配合。
     */
    public static void refreshVanillaToOutlastNbt(ServerPlayer player, Level level) {
        long cooldownEnd = player.getPersistentData().getLong(KEY);
        if (cooldownEnd <= level.getGameTime()) return;
        player.setPortalCooldown((int) (cooldownEnd - level.getGameTime()) + 1);
    }
}
