package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.entity.effects.EntitySunstrike;
import com.zonlong.beloong.compat.mowziesmobs.SolarDamageTypes;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 把「太阳打击」的伤害类型从原版 {@code mob_projectile} 换成 {@code mowziesmobs:sun_strike}。
 *
 * <p><b>本体同样是两段伤害</b>：{@code EntitySunstrike.damageEntityLivingBaseNearby(double)}
 * 连续调用两次 {@code Entity.hurt} —— 第一次 {@code mobProjectile}(弹射物)、第二次 {@code onFire}(燃烧)。
 * 本 Mixin <b>只换第一段</b>（第二段保留原版 {@code on_fire}，燃烧与火焰保护语义不变，
 * 同时保证一次命中只扣 1 层免疫层数）。
 *
 * <p><b>注入点（1.8.2 字节码实测）</b>：{@code damageEntityLivingBaseNearby(D)V} 内
 * 第 324 字节（伤害源来自 {@code DamageSources.mobProjectile}）与
 * 第 347 字节（伤害源来自 {@code DamageSources.onFire}）两处
 * {@code invokevirtual Entity.hurt(DamageSource;F)Z}。
 *
 * <p><b>为什么两处都注入却仍然正确、且不需要 {@code ordinal}</b>：
 * Mixin 默认把注入点匹配到目标方法里<b>每一条</b>候选指令（见 {@code @ModifyArg} 的
 * {@code expect}/{@code allow} javadoc：{@code expect} 仅在 {@code mixin.debug.countInjections}
 * 打开时生效，{@code allow} 只是上限校验）。两处都进同一个 handler 后，由
 * {@link SolarDamageTypes#convert} 按"原伤害源是不是 {@code on_fire}"判别：
 * 是则原样返回（第二段不动），否则换成 {@code mowziesmobs:sun_strike}。
 * 这样既避免了会随重编译漂移的 {@code ordinal}，也不需要在 handler 里判断"我是第几次调用"。
 *
 * <p><b>多次命中是预期行为</b>：该方法在打击持续期内会反复结算，每一次命中都会消耗 1 层免疫层数；
 * 骑士护盾只有 1~2 层，因此最多被消耗到 0，之后太阳打击每次命中都造成正常伤害
 * （这正是"太阳伤害能打伤骑士"的设计目标；不额外做节流）。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}），故不用 {@code @Pseudo}。
 */
@Mixin(EntitySunstrike.class)
public abstract class EntitySunstrikeSolarDamageMixin {

    /**
     * 把传给 {@code Entity#hurt} 的伤害源换成 {@code mowziesmobs:sun_strike}。
     *
     * @param original 原版伤害源（弹射物那段会被换掉；{@code on_fire} 那段原样返回）
     * @return 太阳打击伤害源
     */
    @ModifyArg(
            method = "damageEntityLivingBaseNearby(D)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            index = 0,
            remap = false
    )
    private DamageSource beloong$sunStrikeDamageType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SUN_STRIKE);
    }
}
