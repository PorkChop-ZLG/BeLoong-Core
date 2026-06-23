package com.zonlong.beloong.mixin;

import com.finderfeed.fdbosses.BossClientEvents;
import com.zonlong.beloong.Config;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 提供配置开关以禁用 {@link BossClientEvents#renderHellscapeSkybox} 的地狱天空盒渲染。
 */
@Mixin(value = BossClientEvents.class, remap = false)
public abstract class BossClientEventsMixin {

    @Inject(method = "renderHellscapeSkybox", at = @At("HEAD"), cancellable = true)
    private static void beloong$cancelSkyboxRender(RenderLevelStageEvent event, CallbackInfo ci) {
        if (Config.DISABLE_MALKUTH_HELLSCAPE_SKYBOX.get()) {
            ci.cancel();
        }
    }
}
