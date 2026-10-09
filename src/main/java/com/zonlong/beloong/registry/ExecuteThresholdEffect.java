package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.util.AdditionalEffectData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.ability.ExecuteAbility;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 「斩杀线」状态效果——斩杀被动的标记与结算核心。
 *
 * <h3>它标记什么</h3>
 * <ul>
 *   <li><b>斩杀线</b>：由 {@code amplifier} 编码，{@code 斩杀线 = (amplifier + 1) × 0.5%}。
 *       之所以把百分比塞进 amplifier，是因为 amplifier 是 {@link MobEffectInstance} 里
 *       <b>唯一会被原版自动同步到客户端</b>的数值 —— 任何要做客户端表现（HUD / 血条 / 粒子）的
 *       消费者都能直接读到它，不必再造一条网络通道。技能等级 L 对应 {@code amplifier = L + 4}
 *       （L1 → 5 → 3.0%；L15 → 19 → 10.0%）。</li>
 *   <li><b>斩杀者</b>：由 DS 自己的 {@code AdditionalEffectData} 机制记录。DS 的
 *       {@code EffectHandler#handleEffectApplication} 会把 {@code MobEffectEvent.Added}
 *       的 {@code effectSource} 写进 effect 实例，而 {@code LivingEntity#addEffect(instance, source)}
 *       的第 2 个参数正是它 ⇒ 施加时传 {@code player} 即可，本模组不需要再造一份附件。
 *       该字段只存在服务端（DS 只做了 NBT 存档，没有网络同步），结算全部在服务端完成，因此够用。</li>
 * </ul>
 *
 * <h3>为什么结算放在 effect tick</h3>
 * 需求是「敌人<b>血量低于</b>斩杀线时触发」——这是一个<b>状态</b>而不是某个瞬时事件：
 * 掉血可能来自别的玩家、火焰、摔落或 DoT。放在受害者身上的 tick 里判定，
 * 上述所有来源都能覆盖；斩杀者离线/掉技能时自然失效，不需要清理逻辑。
 *
 * <p>刻意保留的副作用：斩杀者正在冷却时，目标会「卡在斩杀线以下」，
 * 冷却结束的瞬间立刻被斩杀——这正是斩杀线标记应有的威慑力。</p>
 *
 * @see ExecuteAbility 能力等级与数值来源
 * @see ExecuteCooldown 冷却记账
 * @see com.zonlong.beloong.registry.ExecuteMarkHandler 标记的施加方
 */
public class ExecuteThresholdEffect extends MobEffect {

    /** 斩杀线伤害类型：{@code data/beloong/damage_type/execute.json}。 */
    public static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "execute"));

    /** 每级（amplifier + 1）提升的斩杀线，0.5%。 */
    public static final float THRESHOLD_PER_LEVEL = 0.005F;

    /** 每级提升的斩杀线，以「百分比数值」计：0.5 表示 0.5%。 */
    public static final float THRESHOLD_STEP_PERCENT = 0.5F;

    /** 斩杀触发时打给玩家的提示语言键。 */
    public static final String MESSAGE_TRIGGERED = "message.beloong.execute.triggered";

    /** 标记方块（mob effect）的颜色：暗金，与「斩杀线」的视觉基调一致。 */
    private static final int COLOR = 0x8B0000;

    public ExecuteThresholdEffect() {
        super(MobEffectCategory.HARMFUL, COLOR);
    }

    /** 斩杀线比例（0.005 = 0.5%）。 */
    public static float thresholdFraction(int amplifier) {
        return (amplifier + 1) * THRESHOLD_PER_LEVEL;
    }

    /** 由斩杀线比例反解 amplifier；技能等级 L 的斩杀线落在 {@code L + 4}。 */
    public static int amplifierForThreshold(float percent) {
        return Math.max(0, Math.round(percent / THRESHOLD_STEP_PERCENT) - 1);
    }

    /** 每 tick 都要判定一次血量，因此恒定返回 true。 */
    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return true;
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // 原版在双端都会调 tick；结算只在服务端做。
        if (entity.level().isClientSide() || !(entity.level() instanceof ServerLevel serverLevel)) {
            return true;
        }

        MobEffectInstance instance = entity.getEffect(ModMobEffects.EXECUTE_THRESHOLD);
        if (instance == null) {
            return true;
        }

        // ① 血量是否已跌破斩杀线（严格小于）
        float line = entity.getMaxHealth() * thresholdFraction(instance.getAmplifier());
        if (entity.getHealth() >= line) {
            return true;
        }

        // ② 斩杀者是否还在（DS 存在 effect 实例上的 applier）
        Entity applier = ((AdditionalEffectData) instance).dragonSurvival$getApplier(serverLevel);
        if (!(applier instanceof ServerPlayer player) || player.serverLevel() != serverLevel) {
            return true;
        }

        // ③ 斩杀者是否还持有技能（等级 ≥ 1、未被禁用）
        ExecuteAbility.Config config = ExecuteAbility.config(player);
        if (config == null) {
            return true;
        }

        // ④ 冷却是否走完
        if (!ExecuteCooldown.isReady(player)) {
            return true;
        }

        execute(player, entity, config);
        return true;
    }

    /**
     * 结算一次斩杀：真实伤害 → 清理标记 → 记账冷却 → 粒子 / 音效 / 提示。
     *
     * <p><b>伤害来源刻意不带实体</b>（{@link ExecuteDamageSource} 走的是单参构造器
     * {@code new DamageSource(holder)}，两个实体都是 {@code null}）：归因交给
     * {@code getKillCredit()}，而死亡消息由 {@link ExecuteDamageSource} 覆写、
     * 按击杀者的<b>真实成长阶段</b>渲染（见该类的类注释）。
     * 因此在这之前必须显式 {@code setLastHurtByPlayer} 把击杀者钉死——
     * {@code getKillCredit()} 先看 {@code lastHurtByPlayer}，为 {@code null} 时才会退到
     * {@code lastHurtByMob}。</p>
     */
    private static void execute(ServerPlayer player, LivingEntity victim, ExecuteAbility.Config config) {
        ServerLevel level = player.serverLevel();
        Holder<DamageType> damageType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(DAMAGE_TYPE);

        float damage = config.damage().calculate(config.level());

        // 先钉死击杀归因，再结算伤害：死亡消息在 hurt() 内部就会被算出来。
        victim.setLastHurtByPlayer(player);

        if (!victim.hurt(new ExecuteDamageSource(damageType), damage)) {
            // 无敌实体 / 已死亡等：不消耗标记，也不进冷却。
            return;
        }

        // 标记用完即弃，避免死亡动画期间重复结算。
        //
        // 注意：这里是在 LivingEntity#tickEffects 的迭代过程中移除效果，理论上会让该迭代器在下一次
        // next() 时抛 ConcurrentModificationException——但原版自己就是这么容忍的
        // （tickEffects 用空 catch 吞掉了它，见 LivingEntity.java:816-817，而
        // MobEffectInstance.tick 在 applyEffectTick 返回 false 时也会走同一路径）。
        // 后果仅是「该生物本 tick 剩下的状态效果少 tick 一次」，可以接受。
        victim.removeEffect(ModMobEffects.EXECUTE_THRESHOLD);

        ExecuteCooldown.mark(player, Math.max(0, (int) config.cooldown().calculate(config.level())));

        playTriggerEffects(level, victim);

        player.displayClientMessage(
                Component.translatable(MESSAGE_TRIGGERED, victim.getDisplayName()),
                /* actionbar = */ true);
    }

    /** 龙息粒子缠身 + 末影龙低吼。 */
    private static void playTriggerEffects(ServerLevel level, LivingEntity victim) {
        double spread = Math.max(0.3D, victim.getBbWidth() * 0.8D);

        level.sendParticles(
                ParticleTypes.DRAGON_BREATH,
                victim.getX(),
                victim.getY() + victim.getBbHeight() * 0.5D,
                victim.getZ(),
                60,
                spread,
                Math.max(0.3D, victim.getBbHeight() * 0.6D),
                spread,
                0.02D);

        level.playSound(
                null,
                victim.getX(),
                victim.getY(),
                victim.getZ(),
                SoundEvents.ENDER_DRAGON_GROWL,
                SoundSource.HOSTILE,
                1.0F,
                1.0F);
    }
}
