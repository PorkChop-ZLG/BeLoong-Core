package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.registry.attachments.DSDataAttachments;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.SummonData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.TargetingMode;
import by.dragonsurvivalteam.dragonsurvival.util.AdditionalEffectData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.ability.ExecuteAbility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;

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
        // (c) 忽略**我们自己的**斩杀伤害（用户 2026-10-11 裁定）。
        // ExecuteDamageSource 带着斩杀者（为了让击杀统计 / 掉落 / 进度归因正确），于是我们自己的斩杀
        // 也会触发本事件，看起来就像「玩家又打了一下」⇒ 自动重新挂线 ⇒ 冷却一结束就自动再斩一次。
        // 斩杀伤害不是「玩家攻击」，不应据此挂线。
        if (isOurExecuteDamage(event.getSource())) {
            return;
        }

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

        // (b) 冷却期间不再挂线（用户 2026-10-11 裁定）。这**反转**了 ExecuteCooldown 类注释里记载的
        // 旧意图「冷却期间仍然可以继续给敌人挂斩杀线」，理由见该注释与设计文档 §十七：
        // 被目标挡下的斩杀若还能被下一次攻击立刻重新挂上，就会变成每 tick 重试的无限斩杀。
        if (!ExecuteCooldown.isReady(player)) {
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
        //
        // 先走**正常路径**（会经过 canBeAffected / MobEffectEvent.Applicable 等全部原版闸门）；
        // 被拒时才回退到 forceAddEffect —— 有些 Boss 直接覆写 addEffect 无条件返回 false，
        // 那种闸门连 canBeAffected 都不会被问到（见 applyByForce）。
        if (!victim.addEffect(mark, player)) {
            applyByForce(victim, mark, player);
        }
    }

    /**
     * 让斩杀标记对「拒绝状态效果」的生物也能挂上 —— <b>第 1 道闸门（{@code canBeAffected}）</b>。
     *
     * <h3>为什么这一条就够了，不需要给每个 Boss 模组写 mixin</h3>
     * {@code LivingEntity#addEffect} 的第一行就是 {@code CommonHooks.canMobEffectBeApplied}，
     * 而它的实现（{@code CommonHooks:1548-1551}）是「post 一个 {@link MobEffectEvent.Applicable}
     * 再读结果」，并且原版把这个结果解释成（{@code MobEffectEvent.Applicable:138-143}）：
     * <pre>
     *   if (result == APPLY) return true;                    // ← 直接 true，canBeAffected 根本不参与
     *   return result == DEFAULT &amp;&amp; entity.canBeAffected(...);
     * </pre>
     * 即 {@code Result.APPLY} 的官方语义就是「<b>强制施加</b>」。该事件不可取消、所有监听器都会执行、
     * 结果取<b>最后一个</b> {@code setResult} ⇒ 用 {@link EventPriority#LOWEST} 跑到最后即可稳赢。
     * 于是包内**任何** {@code canBeAffected} 实现（灾变的效果白名单
     * {@code IABoss_monster:302}、以及将来任何模组）都会被这一处放行，无需逐个 mixin。
     *
     * <p><b>作用面</b>：只对 {@code beloong:execute_threshold} 生效（先做 Holder 比较再改结果），
     * 其它任何效果的行为完全不变。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void forceExecuteMarkApplicable(final MobEffectEvent.Applicable event) {
        if (event.getEffectInstance().is(ModMobEffects.EXECUTE_THRESHOLD)) {
            event.setResult(MobEffectEvent.Applicable.Result.APPLY);
        }
    }

    /**
     * 第 2 道闸门（{@code addEffect} 覆写）的兜底：有些生物<b>直接覆写 {@code addEffect} 并
     * 无条件返回 false</b>，那种写法连 {@code canBeAffected} 都不会被问到 ——
     * 原版凋灵的 {@code WitherBoss:529-532}、传奇怪物的 Boss 基类 {@code IAnimatedBoss:225-227}
     * 都是这一类。
     *
     * <p>{@code LivingEntity#forceAddEffect}（{@code :1014-1024}）<b>不调用被覆写的 {@code addEffect}</b>，
     * 而是直接写 {@code activeEffects} 并触发 {@code onEffectAdded}；它仍会走
     * {@code canMobEffectBeApplied} ⇒ 已被 {@link #forceExecuteMarkApplicable} 放行。</p>
     *
     * <p><b>⚠️ 必须自己补记「斩杀者」</b>：{@code forceAddEffect} <b>不触发 {@code MobEffectEvent.Added}</b>，
     * 而 DS 正是在那个事件里把 {@code effectSource} 写进 effect 实例的（DS {@code EffectHandler:21-22}）。
     * 不补的话标记会挂上、却永远不触发 —— 又是一个静默失败。DS 的接口是公开的，直接调即可。</p>
     */
    private static void applyByForce(final LivingEntity victim, final MobEffectInstance mark,
                                     final ServerPlayer player) {
        ((AdditionalEffectData) mark).dragonSurvival$setApplier(player);
        victim.forceAddEffect(mark, player);

        if (victim.getEffect(ModMobEffects.EXECUTE_THRESHOLD) == null) {
            // 两道闸门都过了还挂不上：极其罕见，且症状（斩杀对该生物永久无效）完全静默，
            // 所以这里必须留痕。用 WARN 是因为它只在真正异常时出现，不会刷屏。
            BeLoongCore.LOGGER.warn("[BeLoong] execute: could NOT apply the mark to {} ({}),"
                            + " it can never be executed",
                    victim.getDisplayName().getString(), victim.getType());
        } else {
            BeLoongCore.LOGGER.debug("[BeLoong] execute: mark force-applied to {} ({})",
                    victim.getDisplayName().getString(), victim.getType());
        }
    }

    /**
     * 这一下是不是 {@code beloong:execute} 自己造成的。
     *
     * <p>按<b>伤害类型</b>判断而不是 {@code instanceof ExecuteDamageSource}：类型是数据的身份，
     * 将来若换了伤害源的构造方式（或由别的代码路径复用这个类型）也不会漏判。</p>
     */
    private static boolean isOurExecuteDamage(final DamageSource source) {
        return source.typeHolder().unwrapKey()
                .map(key -> key.equals(ExecuteThresholdEffect.DAMAGE_TYPE))
                .orElse(false);
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
