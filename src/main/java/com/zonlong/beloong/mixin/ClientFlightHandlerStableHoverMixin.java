package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyExpressionValue;

/**
 * 将 ClientFlightHandler.flightControl() 中对 ServerFlightHandler.stableHover
 * 的两处引用替换为 FLIGHT_LEVEL >= 1 的判断。
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerStableHoverMixin {

    @ModifyExpressionValue(
            method = "flightControl",
            at = @At(
                    value = "FIELD",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/server/handlers/ServerFlightHandler;stableHover:Z"
            ),
            require = 2
    )
    private static boolean replaceStableHover(boolean original) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            return ModAttributes.getFlightLevel(player) >= 1.0;
        }
        return false;
    }
}
