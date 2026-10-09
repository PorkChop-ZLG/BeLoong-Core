package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.damage.DamageUtil;
import com.bobmowzie.mowziesmobs.server.entity.effects.EntitySuperNova;
import com.zonlong.beloong.compat.mowziesmobs.SolarDamageTypes;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 把「超新星燃烧」的伤害类型从原版 {@code mob_projectile} 换成 {@code mowziesmobs:supernova}。
 *
 * <p><b>这是太阳祝福的第四个技能</b>（`SupernovaAbility` 只负责生成 {@link EntitySuperNova}，
 * 真正的伤害结算在 {@code EntitySuperNova.tick()} 里）。结构与太阳射线/太阳打击同构：
 * 一次 {@link DamageUtil#dealMixedDamage} 打两段 —— 第一段 {@code mobProjectile}(弹射物)、
 * 第二段 {@code onFire}(燃烧)；外加一条直接 {@code Entity.hurt} 的路径。
 * 本 Mixin <b>只换第一段</b>与那条直接路径（保留 {@code on_fire}）：燃烧与火焰保护语义不变，
 * 且只有第一段命中破防标签 ⇒ 一次命中只扣 1 层免疫层数（判别逻辑在
 * {@link SolarDamageTypes#convert}：遇到 {@code on_fire} 原样返回）。
 *
 * <p><b>注入点（1.8.2 字节码实测，都在 {@code tick()} 内）</b>：
 * <ul>
 *   <li>第 859 字节 {@code invokestatic DamageUtil.dealMixedDamage(LivingEntity;DamageSource;FDamageSource;F)Pair}
 *       ⇒ 改其 <b>index 1</b> 参数（第一段伤害源，由第 845 字节的 {@code mobProjectile} 构造）；
 *       index 3（第 854 字节的 {@code onFire}）不动。</li>
 *   <li>第 946 字节 {@code invokevirtual Entity.hurt(DamageSource;F)Z}
 *       ⇒ 改其 <b>index 0</b> 参数（另一条直接结算路径，伤害源来自第 940 字节的 {@code mobProjectile}）。</li>
 * </ul>
 *
 * <p><b>为什么不写 {@code ordinal}</b>：与另两个太阳 Mixin 同理 —— Mixin 的
 * {@code @ModifyArg} 默认匹配目标方法里<b>每一条</b>候选指令（其 {@code expect}/{@code allow} javadoc
 * 明确说明；{@code expect} 仅在 {@code mixin.debug.countInjections} 时生效），本项目
 * {@code injectors.defaultRequire = 1} 又保证"匹配不到即启动期硬失败"。少写序号 = 少一类运行时隐患。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}），故不用 {@code @Pseudo}。
 */
@Mixin(EntitySuperNova.class)
public abstract class EntitySuperNovaSolarDamageMixin {

    /**
     * 替换 {@code dealMixedDamage} 的第一段伤害源（弹射物那段）。
     *
     * @param original 原版 {@code mob_projectile} 伤害源（direct=超新星实体，causing=施法者）
     * @return 超新星燃烧伤害源（拿不到注册项时原样返回；{@code on_fire} 也会原样返回）
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/bobmowzie/mowziesmobs/server/damage/DamageUtil;dealMixedDamage(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FLnet/minecraft/world/damagesource/DamageSource;F)Lorg/apache/commons/lang3/tuple/Pair;"),
            index = 1,
            remap = false
    )
    private DamageSource beloong$supernovaMixedDamageType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SUPERNOVA);
    }

    /**
     * 替换 {@code Entity.hurt} 那条直接路径的伤害源。
     *
     * @param original 原版 {@code mob_projectile} 伤害源
     * @return 超新星燃烧伤害源
     */
    @ModifyArg(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            index = 0,
            remap = false
    )
    private DamageSource beloong$supernovaHurtType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SUPERNOVA);
    }
}
