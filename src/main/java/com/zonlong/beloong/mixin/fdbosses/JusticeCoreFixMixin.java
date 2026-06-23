package com.zonlong.beloong.mixin.fdbosses;

import com.finderfeed.fdbosses.BossEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = BossEvents.class, remap = false)
public abstract class JusticeCoreFixMixin {

    @Redirect(
        method = "justiceCore",
        at = @At(
            value = "INVOKE",
            target = "Ljava/lang/Math;clamp(FFF)F"
        ),
        remap = false
    )
    private static float beloong$safeClamp(float value, float min, float max) {
        return Math.clamp(value, min, Math.max(0, max));
    }
}
