package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.damage.DamageUtil;
import com.bobmowzie.mowziesmobs.server.entity.effects.EntitySolarBeam;
import com.zonlong.beloong.compat.mowziesmobs.SolarDamageTypes;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 把「太阳射线」的伤害类型从原版 {@code mob_projectile} 换成 {@code mowziesmobs:solar_beam}。
 *
 * <p><b>本体是两段混合伤害</b>：{@code EntitySolarBeam.tick()} 用
 * {@link DamageUtil#dealMixedDamage} 一次打两段 —— 第一段 {@code mobProjectile}(弹射物) +
 * 第二段 {@code onFire}(燃烧)。本 Mixin <b>只换第一段</b>：第二段保留原版 {@code on_fire}，
 * 这样燃烧与火焰保护语义不变，且只有第一段命中破防标签 ⇒ 一次命中只扣 1 层免疫层数
 * （判别逻辑在 {@link SolarDamageTypes#convert}：遇到 {@code on_fire} 原样返回）。
 *
 * <p><b>注入点（1.8.2 字节码实测）</b>：都在 {@code tick()} 内 ——
 * <ul>
 *   <li>第 1187 字节 {@code invokestatic DamageUtil.dealMixedDamage(LivingEntity;DamageSource;FDamageSource;F)Pair}
 *       ⇒ 改其 <b>index 1</b> 参数（第一段伤害源）；index 3（{@code onFire}）不动。</li>
 *   <li>第 1209 字节 {@code invokevirtual Entity.hurt(DamageSource;F)Z}
 *       ⇒ 改其 <b>index 0</b> 参数（另一条直接结算路径，其伤害源同样来自 {@code mobProjectile}）。</li>
 * </ul>
 *
 * <p><b>为什么不写 {@code ordinal}</b>：Mixin 官方 javadoc（{@code @ModifyArg} 的
 * {@code expect}/{@code allow} 说明）明确"注入点默认匹配目标方法里<b>每一条</b>候选指令"，
 * {@code expect} 只在 {@code mixin.debug.countInjections} 打开时才生效、{@code allow} 仅作上限校验；
 * 本项目 {@code injectors.defaultRequire = 1} 已提供"匹配不到就启动期硬失败"的保护。
 * 少写一个会随重编译漂移的序号，就少一类只在运行时才炸的隐患。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}），故不用 {@code @Pseudo}。
 */
@Mixin(EntitySolarBeam.class)
public abstract class EntitySolarBeamSolarDamageMixin {

    /**
     * 替换 {@code dealMixedDamage} 的第一段伤害源（弹射物那段）。
     *
     * @param original 原版 {@code mob_projectile} 伤害源（direct=光束实体，causing=施法者）
     * @return 太阳射线伤害源（拿不到注册项时原样返回；{@code on_fire} 也会原样返回）
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/bobmowzie/mowziesmobs/server/damage/DamageUtil;dealMixedDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FLnet/minecraft/world/damagesource/DamageSource;F)Lorg/apache/commons/lang3/tuple/Pair;"),
            index = 1,
            remap = false
    )
    private DamageSource beloong$solarBeamMixedDamageType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SOLAR_BEAM);
    }

    /**
     * 替换 {@code Entity.hurt} 那条路径的伤害源（同样是弹射物那段）。
     *
     * @param original 原版 {@code mob_projectile} 伤害源
     * @return 太阳射线伤害源
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            index = 0,
            remap = false
    )
    private DamageSource beloong$solarBeamHurtType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SOLAR_BEAM);
    }
}
