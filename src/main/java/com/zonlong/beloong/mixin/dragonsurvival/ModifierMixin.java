package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.Modifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 修复 Dragon Survival 在渲染未学习技能的 Modifier 描述时，
 * 将等级 0 传入 {@code LevelBasedValue.Lookup} 导致数组越界崩溃的问题。
 *
 * <p>在 {@link Modifier#getFormattedDescription(int, boolean)} 入口将等级下限
 * 钳制为 1，覆盖 ModifierEffect / ModifierWithDuration 等所有 Modifier 描述路径。</p>
 */
@Mixin(value = Modifier.class, remap = false)
public abstract class ModifierMixin {

    @ModifyVariable(
            method = "getFormattedDescription",
            at = @At("HEAD"),
            argsOnly = true,
            index = 1
    )
    private int beloong$clampTooltipLevel(int level) {
        return Math.max(1, level);
    }
}
