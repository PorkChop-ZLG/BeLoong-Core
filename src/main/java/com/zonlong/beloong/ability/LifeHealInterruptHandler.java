package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.network.magic.SyncStopCast;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncCooldown;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * 「灵愈」的打断机制：<b>施法期间被攻击就中断施法</b>。
 *
 * <h3>为什么必须自己写</h3>
 * 原版龙之生存<b>没有任何「挨打打断施法」的机制</b>。{@code DragonAbilityInstance#tickActions}
 * 里只有三条停止路径：魔力耗尽、到达 {@code max_duration}、松开按键
 * （分别见其 {@code stopCasting(dragon, true)} / {@code maxDuration} 分支，以及
 * {@code ClientCastingHandler#stopCast}）。所以这一条只能由本模组补。
 *
 * <h3>为什么发两个包、而且只发给施法者自己</h3>
 * 原版 {@link SyncStopCast#handleServer} 的广播用的是
 * {@code PacketDistributor.sendToPlayersTrackingEntity(player, ...)}——<b>只发给「追踪该玩家的其他人」，
 * 不发给玩家本人</b>（它只发 {@code StopTickingSound} 与 {@code StopAbilityAnimation} 两条表现包）。
 * 施法者自己的客户端是靠 {@code tickActions} 自己发现「魔力不够 / 到时长」才停的。
 * 挨打打断是<b>服务端单方面</b>终止，客户端无从自行得知，因此这里必须补发：
 * <ul>
 *   <li>{@link SyncStopCast#handleServer} —— 走原版正规路径：停循环音效、清技能动画、停止施法状态。
 *       漏了它，施法者客户端会一直卡在引导态（循环音效不停、{@code isCasting} 卡住导致按不出任何技能）。</li>
 *   <li>{@link SyncCooldown} —— 客户端实例的冷却值是独立的一份，
 *       {@code stopCasting} 只改了服务端那一份，不补发同步的话界面上看不到那 20 刻冷却。</li>
 * </ul>
 *
 * <h3>关于 {@code cooldown} 数值的来源</h3>
 * 冷却时长写在能力 JSON 的 {@code activation.cooldown} 里（本技能为 20）。
 * 不能在这里硬塞一个别的值：{@code DragonAbilityInstance#setCooldown} 会用
 * {@code Math.clamp(cooldown, NO_COOLDOWN, activation.getCooldown(level))} 夹紧，
 * 超出 JSON 配置的冷却值会被静默压回去。也就是说
 * <b>「打断冷却」同时也是这个技能的常规冷却</b>——原版只有这一个冷却字段。
 *
 * <h3>打断判据</h3>
 * 只认「真正吃到伤害」的情况：事件被取消（如无敌帧、格挡、被别的模组免除）或最终伤害为 0 时不算。
 * 取 {@code getAmount()} 而不是 {@code getOriginalAmount()}，因为它反映的是各监听器修正后的最终数值。
 */
public final class LifeHealInterruptHandler {

    @SubscribeEvent
    public void onIncomingDamage(final LivingIncomingDamageEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MagicData magic = MagicData.getData(player);
        DragonAbilityInstance casting = magic.getCurrentlyCasting();

        if (!LifeHealAbility.isLifeHeal(casting)) {
            return;
        }

        // 走原版停止路径：内部会 release() 施加冷却，并清理音效 / 动画 / 施法状态。
        SyncStopCast.handleServer(player, LifeHealAbility.KEY);

        // 补发同步给施法者本人（理由见类注释）。发送时刻晚于上面两行，
        // 因此读到的冷却就是打断后实际生效的值，不需要自己再算一遍。
        PacketDistributor.sendToPlayer(player, new SyncStopCast(player.getId(), Optional.of(LifeHealAbility.KEY)));
        PacketDistributor.sendToPlayer(player, new SyncCooldown(LifeHealAbility.KEY, casting.cooldown()));
    }
}
