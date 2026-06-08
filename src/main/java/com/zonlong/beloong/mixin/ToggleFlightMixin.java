package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.network.flight.ToggleFlight;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 ToggleFlight.handleServer 开头注入飞行等级检查。
 * 若飞行等级 < 0 且玩家试图展翅，则取消并返回 NONE。
 */
@Mixin(value = ToggleFlight.class, remap = false)
public class ToggleFlightMixin {

    @Inject(method = "handleServer", at = @At("HEAD"), cancellable = true)
    private static void flightLevelGate(ToggleFlight packet, IPayloadContext context, CallbackInfo ci) {
        Player player = context.player();
        if (player == null) return;

        FlightData flight = FlightData.getData(player);
        if (!flight.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
            // 飞行等级不足，静默拒绝展翅
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) player,
                    new ToggleFlight(packet.activation(), ToggleFlight.Result.NONE)
            );
            ci.cancel();
        }
    }
}
