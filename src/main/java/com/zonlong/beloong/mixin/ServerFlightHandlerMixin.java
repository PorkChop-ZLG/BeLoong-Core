package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 始终启用稳定悬停物理，用 FLIGHT_LEVEL >= 1 控制是否真正悬停。
 * 非稳定悬停的龙由 ClientFlightHandlerMixin.fixStableHoverDrift 追加额外重力。
 */
@Mixin(value = ServerFlightHandler.class, remap = false)
public abstract class ServerFlightHandlerMixin {

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void forceStableHover(CallbackInfo ci) {
        ServerFlightHandler.stableHover = true;
    }
}
