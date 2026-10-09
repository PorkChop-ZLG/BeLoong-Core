package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.ability.abilities.player.heliomancy.SolarFlareAbility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 太阳耀斑数值调整（用户定值：**范围 9 格、基准伤害 4 点，其余不变**）。
 *
 * <p>原版（Mowzie 1.8.2，{@code SolarFlareAbility.beginSection}）：
 * <pre>
 * float radius = 3.2F;                                  // 硬编码，无任何配置项
 * for (LivingEntity aHit : getEntityLivingBaseNearby(user, radius, radius, radius, radius)) {
 *     float damage = 2.0F;                              // 基准值
 *     float knockback = 3.0F;                           // 击退（本次不动）
 *     damage *= ConfigHandler...SUNS_BLESSING.sunsBlessingAttackMultiplier.get();
 *     if (aHit.hurt(user.damageSources().playerAttack(user), damage) &amp;&amp; knockback &gt; 0) { ...push... }
 * }
 * </pre>
 *
 * <p><b>范围 3.2 → 9.0</b>：用 {@code @ModifyConstant} 精确替换方法里那个唯一的
 * {@code ldc 3.2f}（1.8.2 字节码实测：{@code beginSection} 内 {@code ldc 3.2f} 恰好 1 次）。
 * 该局部变量同时用于 AABB {@code inflate(3.2,3.2,3.2)} 与 {@code distanceTo(e) <= 3.2}，
 * 所以改一处即可让"探测体积"和"距离判定"一起变成 9 格。
 * <br>⚠️ 注意 9 格相当大：范围内的**所有**生物（含队友、宠物、村民）都会被命中并击退。
 *
 * <p><b>基准伤害 2.0 → 4.0</b>：不改常量，而是在 {@code hurt} 调用处把<b>已经乘过配置倍率</b>的
 * 伤害量（{@code index 1}）再 ×2 ⇒ 等效于"基准 4.0 × 配置倍率"，与 Mowzie 的
 * {@code suns_blessing_attack_multiplier} 语义完全兼容（该倍率由四招共享，本次<b>不动配置</b>）。
 * <br>为什么不用 {@code @ModifyConstant(floatValue = 2.0F)}：{@code 2.0F} 在字节码里是
 * <b>{@code fconst_2}</b> 专用指令（不进常量池），对它的常量匹配不可靠；改参数则是确定的。
 *
 * <p><b>本次不动</b>：击退（3.0 ⇒ 实际 1.8 水平 + 0.1 垂直）、段时序（12t 前摇 + 瞬时结算 + 18t 后摇）、
 * 施法减速、音效与动画。数值都集中在下面两个常量里，后续要调只改这两行。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}），故不用 {@code @Pseudo}；
 * 注入按 {@code injectors.defaultRequire = 1} 硬失败 —— 上游改了这段代码会在启动期直接报错。
 */
@Mixin(SolarFlareAbility.class)
public abstract class SolarFlareAbilityTuningMixin {

    /** 新的作用半径（格）。原版 3.2F。 */
    private static final float BELOONG_FLARE_RADIUS = 9.0F;

    /** 基准伤害倍数：原版基准 2.0F ⇒ ×2 = 4.0F（Mowzie 的配置倍率仍在其上生效）。 */
    private static final float BELOONG_FLARE_DAMAGE_FACTOR = 2.0F;

    /**
     * 把作用半径字面量 3.2F 换成 {@link #BELOONG_FLARE_RADIUS}。
     *
     * @param original 原版常量值（3.2F）
     * @return 新的半径
     */
    @ModifyConstant(
            method = "beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V",
            constant = @Constant(floatValue = 3.2F),
            remap = false
    )
    private float beloong$flareRadius(float original) {
        return BELOONG_FLARE_RADIUS;
    }

    /**
     * 把传给 {@code LivingEntity#hurt} 的伤害量按 {@link #BELOONG_FLARE_DAMAGE_FACTOR} 放大。
     *
     * <p>此处拿到的已经是"原版基准 2.0F × Mowzie 配置倍率"的结果 ⇒ ×2 后等于
     * "基准 4.0F × 配置倍率"。
     *
     * @param original 原版（含配置倍率）的伤害量
     * @return 放大后的伤害量
     */
    @ModifyArg(
            method = "beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            index = 1,
            remap = false
    )
    private float beloong$flareDamage(float original) {
        return original * BELOONG_FLARE_DAMAGE_FACTOR;
    }
}
