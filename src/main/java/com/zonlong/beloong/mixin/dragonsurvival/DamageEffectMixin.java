package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.DamageEffect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 Dragon Survival 在渲染未学习技能说明时，
 * 将等级 0 传入 {@code LevelBasedValue.Lookup} 导致数组越界崩溃的问题。
 *
 * <p>仅在客户端 tooltip 渲染路径生效：把用于计算说明的等级下限钳制为 1。</p>
 */
@Mixin(DamageEffect.class)
public abstract class DamageEffectMixin {

    @Redirect(
            method = "getDescription",
            at = @At(
                    value = "INVOKE",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/ability/DragonAbilityInstance;level()I"
            ),
            remap = false
    )
    private int beloong$clampTooltipLevel(DragonAbilityInstance ability) {
        return Math.max(1, ability.level());
    }
}
