package com.zonlong.beloong.mixin.mowziesmobs;

import com.bobmowzie.mowziesmobs.server.ability.abilities.player.heliomancy.SolarFlareAbility;
import com.zonlong.beloong.compat.mowziesmobs.SolarDamageTypes;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 把「太阳耀斑」的伤害类型从原版 {@code player_attack} 换成 {@code mowziesmobs:solar_flare}。
 *
 * <p><b>为什么必须换类型</b>：耀斑原本用 {@code user.damageSources().playerAttack(user)} 造成伤害 ——
 * 那与原版近战完全同类，无法充当「破防入口」（把它写进 tag 就等于让所有玩家攻击都能破防）。
 * 换成自建类型后，冥界骑士侧只需认 tag（见 {@code registry/ModDamageTypeTags}）。
 *
 * <p><b>注入点（2.1.2 / 1.8.2 字节码实测）</b>：
 * {@code beginSection(AbilitySection)} 内第 126 字节构造 {@code playerAttack}，
 * 第 131 字节 {@code invokevirtual LivingEntity.hurt(DamageSource;F)Z} ⇒ 本 Mixin 改的是后者的 <b>index 0 参数</b>。
 * 用 {@code @ModifyArg} 而不是 {@code @Redirect} 是为了只动「传进去的伤害源」，
 * 不改变 {@code hurt} 的返回值语义（调用方用返回值决定是否击退）。
 *
 * <p><b>保留实体引用</b>：{@link SolarDamageTypes#convert} 只换类型、沿用原来的
 * direct / causing 实体 ⇒ 击杀归属、友好火力判定、成就与经验判定都不变。
 *
 * <p>Mowzie's Mobs 在本模组是<b>必选</b>依赖（{@code type="required"}，见 {@code neoforge.mods.toml}），
 * 因此本 Mixin 不用 {@code @Pseudo}，注入按 {@code injectors.defaultRequire = 1} 硬失败 ——
 * 上游改了方法签名会在启动期直接报错，而不是静默失效。
 */
@Mixin(SolarFlareAbility.class)
public abstract class SolarFlareAbilitySolarDamageMixin {

    /**
     * 把传给 {@code LivingEntity#hurt} 的伤害源换成 {@code mowziesmobs:solar_flare}。
     *
     * @param original Mowzie 构造的原版玩家攻击伤害源
     * @return 太阳耀斑伤害源（拿不到注册项时原样返回）
     */
    @ModifyArg(
            method = "beginSection(Lcom/bobmowzie/mowziesmobs/server/ability/AbilitySection;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/LivingEntity;hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
            index = 0,
            remap = false
    )
    private DamageSource beloong$solarFlareDamageType(DamageSource original) {
        return SolarDamageTypes.convert(original, SolarDamageTypes.SOLAR_FLARE);
    }
}
