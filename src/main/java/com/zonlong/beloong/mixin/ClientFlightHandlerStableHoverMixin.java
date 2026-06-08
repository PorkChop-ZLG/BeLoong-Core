package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 将 ClientFlightHandler.flightControl() 中对 ServerFlightHandler.stableHover
 * 的引用替换为 FLIGHT_LEVEL >= 1 的判断。
 * @Redirect 会自动拦截所有 FIELD GET 访问（两处）。
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerStableHoverMixin {

    @Redirect(
            method = "flightControl",
            at = @At(
                    value = "FIELD",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/server/handlers/ServerFlightHandler;stableHover:Z"
            )
    )
    private static boolean replaceStableHover() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            return ModAttributes.getFlightLevel(player) >= 1.0;
        }
        return false;
    }
}
