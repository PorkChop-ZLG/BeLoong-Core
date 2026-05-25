package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientFlightHandler.class)
public abstract class ClientFlightHandlerMixin {

    @Shadow private static double ax;
    @Shadow private static double az;
    @Shadow private static double ay;

    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void fixStableHoverDrift(CallbackInfo ci) {
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;

        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            if (!handler.isDragon()) return;

            FlightData flightData = FlightData.getData(player);
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) return;

            Input movement = player.input;
            boolean shouldHover = ServerFlightHandler.stableHover
                    && !movement.jumping
                    && !movement.shiftKeyDown
                    && !ServerFlightHandler.isSpin(player)
                    && !ServerFlightHandler.isGliding(player);

            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

            if (shouldHover && noMoveInput) {
                ax = 0.0;
                az = 0.0;

                if (player.isCreative()) {
                    ay = 0.0;
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            }
        });
    }
}
