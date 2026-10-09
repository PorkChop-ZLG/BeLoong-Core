package com.zonlong.beloong.registry;

import net.minecraft.world.entity.player.Player;

/**
 * 斩杀被动技能的冷却记账。
 *
 * <h3>为什么不用 DS 的能力冷却</h3>
 * DS 的 {@code DragonAbilityInstance#tickActions} 末尾有一条硬编码：
 * <pre>
 *   if (activation.type() == SIMPLE || isPassive() &amp;&amp; activation.getCooldown(level) &gt; 0) {
 *       stopCasting(dragon, false);   // → release() → cooldown = getCooldown(level)
 *   }
 * </pre>
 * 也就是说，被动能力只要声明了 {@code cooldown > 0}，<b>每次被触发都会立刻进入满冷却</b>。
 * 而斩杀需要的语义是「<b>触发斩杀之后</b>才进冷却」，且冷却期间<b>仍然可以</b>继续给敌人挂斩杀线。
 * 两者不可调和，因此能力 JSON <b>不声明</b> {@code activation.cooldown}，
 * 冷却值改由能力 JSON 里 {@code beloong:execute} 效果的 {@code cooldown} 字段提供，
 * 状态存在本模组自己的附件里。
 *
 * <p>代价：DS 技能界面不会显示冷却读数（那是 {@code activation.cooldown > 0} 才渲染的一行），
 * 该数值改在技能的 {@code dynamic_desc} 里写出来。</p>
 *
 * @see ModAttachments#EXECUTE_READY_AT
 */
public final class ExecuteCooldown {

    private ExecuteCooldown() {}

    /** 冷却是否已经走完（可以再次触发斩杀）。 */
    public static boolean isReady(Player player) {
        return gameTime(player) >= readyAt(player);
    }

    /** 记录一次冷却，{@code ticks} 为冷却刻数（负数按 0 处理）。 */
    public static void mark(Player player, int ticks) {
        player.setData(ModAttachments.EXECUTE_READY_AT.get(), gameTime(player) + Math.max(0, ticks));
    }

    /** 剩余冷却刻数（0 表示随时可用）。 */
    public static int remaining(Player player) {
        long remaining = readyAt(player) - gameTime(player);
        return remaining > 0 ? (int) Math.min(Integer.MAX_VALUE, remaining) : 0;
    }

    private static long readyAt(Player player) {
        Long value = player.getData(ModAttachments.EXECUTE_READY_AT.get());
        return value == null ? 0L : value;
    }

    private static long gameTime(Player player) {
        return player.level().getGameTime();
    }
}
