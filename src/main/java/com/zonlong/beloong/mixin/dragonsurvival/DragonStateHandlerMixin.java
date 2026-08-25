package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 新玩家的大型龙破坏（largeDragonDestruction）默认设为 {@code DISABLED}。
 *
 * <p>这是刻意设计：整合包默认不希望新龙玩家在大型状态时无意识破坏地形。
 * 玩家仍可通过 Dragon Survival 自带的按键循环切换该状态。</p>
 */
@Mixin(value = DragonStateHandler.class, remap = false)
public class DragonStateHandlerMixin {

    @Shadow
    public DragonStateHandler.LargeDragonDestruction largeDragonDestruction;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void disableDestructionByDefault(CallbackInfo ci) {
        this.largeDragonDestruction = DragonStateHandler.LargeDragonDestruction.DISABLED;
    }
}
