package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.SummonData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.TargetingMode;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.ability.ExecuteAbility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

/**
 * 「斩杀」被动的标记施加方：<b>龙玩家攻击非友方生物 ⇒ 给目标挂上「斩杀线」</b>。
 *
 * <h3>为什么不用 DS 的 {@code on_target_hit} 触发器</h3>
 * DS 的 {@code OnTargetHit#trigger} 在 {@code LivingDamageEvent.Post} 里只做一件事：
 * 找出带该触发器的被动能力并 {@code ability.tick(player)}。被击中的实体<b>不会传给行动</b>，
 * 行动只能按自己的 {@code target_selection} 重新找目标：
 * {@code looking_at} 对箭矢/法术必然打偏，{@code area}/{@code disc} 又会波及无关生物。
 * 因此这里直接监听同一事件、直接拿 {@code event.getEntity()}，与伤害来源（近战 / 远程 / 法术）无关。
 *
 * <h3>「非友方」的口径</h3>
 * 分两条路：
 * <ul>
 *   <li><b>玩家目标</b>：用原版 {@link Player#canHarmPlayer(Player)} —— 也就是
 *       {@code ServerPlayer#hurt} 判定「这一下打不打得到」用的同一个谓词。</li>
 *   <li><b>非玩家目标</b>：复用 DS 的 {@link TargetingMode#NON_ALLIES}，再补两条它覆盖不到的排除项
 *       （与本模组龙卷风的 {@code canAffect} 口径一致）：</li>
 * </ul>
 * <ul>
 *   <li>攻击者自己的<b>原版宠物</b>（{@link OwnableEntity#getOwnerUUID()}）；</li>
 *   <li>攻击者的 <b>Dragon Survival 召唤物</b>（{@link SummonData#isOwner}）。</li>
 * </ul>
 * 注意召唤物判定必须用 {@code getExistingDataOrNull}——{@code getData} 在附件缺失时会创建并挂上
 * 默认 {@code SummonData}，而它是 serializable 的，会给每个被打到的生物写 NBT。
 *
 * @see ExecuteThresholdEffect 标记的结算方
 */
@EventBusSubscriber(modid = BeLoongCore.MODID)
public final class ExecuteMarkHandler {

    private ExecuteMarkHandler() {}

    @SubscribeEvent
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }

        ExecuteAbility.Config config = ExecuteAbility.config(player);
        if (config == null) {
            return;
        }

        if (!isValidTarget(player, victim)) {
            return;
        }

        int level = config.level();
        int amplifier = ExecuteThresholdEffect.amplifierForThreshold(config.threshold().calculate(level));
        int duration = Math.max(1, (int) config.markDuration().calculate(level));

        // showIcon / visible 都关掉：amplifier 最大到 19，原版 HUD 会去查不存在的
        // enchantment.level.20 语言键；而这个标记本来就是纯内部状态，不需要在 buff 栏占一个图标。
        MobEffectInstance mark = new MobEffectInstance(
                ModMobEffects.EXECUTE_THRESHOLD,
                duration,
                amplifier,
                /* ambient = */ false,
                /* visible = */ false,
                /* showIcon = */ false);

        // 第二个参数是 effectSource —— DS 的 EffectHandler 会把它存进 effect 实例，
        // 结算时用来找回「斩杀者」。见 ExecuteThresholdEffect 的类注释。
        victim.addEffect(mark, player);
    }

    /** 「非友方生物」判定：DS 的 NON_ALLIES 口径 + 自己的宠物 / DS 召唤物豁免。 */
    private static boolean isValidTarget(ServerPlayer player, LivingEntity victim) {
        if (victim == player || !victim.isAlive() || victim.isSpectator()) {
            return false;
        }

        // 玩家之间**不走 DS 的 NON_ALLIES**，直接问原版「我能不能伤害他」。
        //
        // 原因：TargetingMode#isEntityRelevant 把 isFriendly 判在 isEnemy / isHarmful 之前，
        // 而且一旦命中友方分支就直接否决 NON_ALLIES（TargetingMode.java:83-85）——
        // 于是**同队的两个玩家永远挂不上斩杀线**，哪怕队伍开着友伤、伤害确实打出去了。
        // 下游那个 `isHarmful && Player` 的 canAttackPlayer 兜底（:91-93）根本轮不到。
        //
        // 而 ServerPlayer#hurt:796-800 判定「这一下打不打得到」用的正是同一个
        // Player#canHarmPlayer，所以用它既与「伤害能落地」等价，又天然尊重队伍友伤开关与 PvP 设置。
        if (victim instanceof Player other) {
            return player.canHarmPlayer(other);
        }

        if (!TargetingMode.NON_ALLIES.isEntityRelevant(player, victim, true)) {
            return false;
        }

        if (victim instanceof OwnableEntity ownable && player.getUUID().equals(ownable.getOwnerUUID())) {
            return false;
        }

        SummonData summon = victim.getExistingDataOrNull(DSDataAttachments.SUMMON.get());
        return summon == null || !summon.isOwner(player);
    }

    /** 便于其它系统复用同一口径（例如血条只在「会被斩杀的生物」上画线）。 */
    public static boolean isMarkableBy(Player player, LivingEntity victim) {
        return player instanceof ServerPlayer serverPlayer && isValidTarget(serverPlayer, victim);
    }
}
