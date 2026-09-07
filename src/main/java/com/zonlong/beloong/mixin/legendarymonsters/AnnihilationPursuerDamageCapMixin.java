package com.zonlong.beloong.mixin.legendarymonsters;

import net.miauczel.legendary_monsters.config.ModConfig;
import net.miauczel.legendary_monsters.entity.AnimatedMonster.Mobs.SpaceStation.Flameborn.AnnihilationPursuer.AnnihilationPursuerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 修复湮灭猎影不读取通用 MiniBoss 伤害上限的问题。
 *
 * <p>原版 LM 中 {@link AnnihilationPursuerEntity#damageCap()} 硬编码返回 21，
 * 导致修改 {@code MiniBoss DamageCap} 对它无效。本 Mixin 在 HEAD 直接返回
 * {@link ModConfig.MOB_CONFIG#MiniBossDamageCap} 的当前配置值，使其与其它
 * IAnimatedMiniBoss 行为一致。</p>
 *
 * <p>传奇怪物为可选依赖：使用 {@code @Pseudo} + {@code require = 0}，
 * 未安装传奇怪物时本 Mixin 自动跳过，不影响化龙核心加载。</p>
 */
@Pseudo
@Mixin(value = AnnihilationPursuerEntity.class, remap = false)
public abstract class AnnihilationPursuerDamageCapMixin {

    @Inject(method = "damageCap", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void beloong$useSharedMiniBossDamageCap(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue((double) ModConfig.MOB_CONFIG.MiniBossDamageCap.get());
    }
}
