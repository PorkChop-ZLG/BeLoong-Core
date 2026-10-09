package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.ability.abilities.player.heliomancy.SolarFlareAbility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
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
 * <p><b>两个注入都用 {@code @ModifyConstant}，把上游的字面量本身当锚点</b>：
 * <ul>
 *   <li>半径 = `ldc 3.2f`（1.8.2 字节码里 {@code beginSection} 内<b>恰好 1 次</b>）→ {@link #BELOONG_FLARE_RADIUS}；</li>
 *   <li>基准伤害 = `fconst_2`（同样<b>恰好 1 次</b>）→ {@link #BELOONG_FLARE_BASE_DAMAGE}。
 *       能匹配 {@code fconst_2} 的依据：Mixin 的 {@code BeforeConstant} javadoc 明确写
 *       "searches for {@code LDC} <b>and other constant opcodes</b>"（`BeforeConstant.java:65`），
 *       {@code fconst_0/1/2} 属于其中。</li>
 * </ul>
 * 这样做的收益是**上游改了基准值/半径就变成启动期硬失败**（本项目 {@code injectors.defaultRequire = 1}），
 * 而不是"注入照常成功、数值静默漂移"。
 * <br>（历史：本文件最初用"把已乘配置倍率的伤害 ×2"实现基准 4.0 —— 那条路在上游把 `2.0F` 改成 `2.5F` 时会
 * 静默变成 5.0F 且不报错，属代码审查点出的隐患，现已改为锚定写法。）
 *
 * <p><b>范围的真实语义（别只看数字）</b>：`radius` 这同一个局部量被传了 4 次，落地为
 * {@code getEntityLivingBaseNearby(user, 9, 9, 9, 9)} ⇒
 * ① 候选域 = {@code player.getBoundingBox().inflate(9,9,9)}（约 18×18×18 的立方体）；
 * ② 再按 {@code player.distanceTo(e) <= 9} 过滤，而 {@code distanceTo} 是<b>以玩家脚底为球心的 3D 欧氏距离</b>。
 * ⇒ 对高大目标（Boss）而言"有效水平距离 &lt; 9 格"（Y 方向占掉了额度）；地下/天上的目标会被 ② 滤掉。
 *
 * <p><b>已知且被接受的行为</b>：Mowzie 的循环只排除施法者自己（{@code aHit != user}），
 * <b>没有队伍 / 友军过滤</b> ⇒ 9 格内的队友、宠物、村民、被动生物都会被命中并被击退
 * （原版 3.2 格时几乎察觉不到）。这是"用户定值 9 格"的直接后果，已登记在设计文档
 * {@code docs/plans/2026-10-09-solar-flare-tuning.md}；要收窄改 {@link #BELOONG_FLARE_RADIUS} 即可
 * （按用户要求当前为硬编码、无配置开关）。
 *
 * <p><b>本次不动</b>：击退（3.0 ⇒ 实际 1.8 水平 + 0.1 垂直）、段时序（12t 前摇 + 瞬时结算 + 18t 后摇）、
 * 施法减速、音效与动画。
 *
 * <p><b>为什么标 {@link Unique}</b>：这两个字段会被合并进目标类；加 {@code @Unique} 可避免与上游将来
 * 出现的同名字段冲突（合并后它们是无初值的静态字段，实际取值已被 javac 内联进 handler，功能等价）。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}），故不用 {@code @Pseudo}。
 */
@Mixin(SolarFlareAbility.class)
public abstract class SolarFlareAbilityTuningMixin {

    /** 新的作用半径（格）。原版 3.2F。 */
    @Unique
    private static final float BELOONG_FLARE_RADIUS = 9.0F;

    /** 新的基准伤害。原版 2.0F（其后仍会乘 Mowzie 的 {@code suns_blessing_attack_multiplier}）。 */
    @Unique
    private static final float BELOONG_FLARE_BASE_DAMAGE = 4.0F;

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
     * 把基准伤害字面量 2.0F 换成 {@link #BELOONG_FLARE_BASE_DAMAGE}。
     *
     * @param original 原版常量值（2.0F）
     * @return 新的基准伤害
     */
    @ModifyConstant(
            method = "beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V",
            constant = @Constant(floatValue = 2.0F),
            remap = false
    )
    private float beloong$flareBaseDamage(float original) {
        return BELOONG_FLARE_BASE_DAMAGE;
    }
}
