package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DragonStateHandler.class, remap = false)
public class DragonStateHandlerMixin {

    @Shadow
    public DragonStateHandler.LargeDragonDestruction largeDragonDestruction;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void disableDestructionByDefault(CallbackInfo ci) {
        this.largeDragonDestruction = DragonStateHandler.LargeDragonDestruction.DISABLED;
    }
}
