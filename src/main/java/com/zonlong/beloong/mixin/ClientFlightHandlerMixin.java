package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.client.handlers.ClientFlightHandler;
import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复龙之生存稳定悬停时的漂移问题。
 *
 * <p>在 {@code flightControl()} 末尾注入，检测悬停状态：
 * 若玩家无输入且处于稳定悬停，则清零水平加速度。
 * 创造模式下额外清零垂直加速度和垂直速度。</p>
 *
 * <p>详见 {@code doc/ds-bug-fix-mixins.md}</p>
 */
@Mixin(ClientFlightHandler.class)
public abstract class ClientFlightHandlerMixin {

    /** 龙的 X 轴加速度（前后），通过 Shadow 映射目标类的私有静态字段 */
    @Shadow
    private static double ax;

    /** 龙的 Z 轴加速度（左右） */
    @Shadow
    private static double az;

    /** 龙的 Y 轴加速度（垂直） */
    @Shadow
    private static double ay;

    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void fixStableHoverDrift(CallbackInfo ci) {
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            if (!handler.isDragon()) return;

            FlightData flightData = FlightData.getData(player);
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) return;

            Input movement = player.input;
            double flightLevel = ModAttributes.getFlightLevel(player);
            boolean shouldHover = flightLevel >= 1.0
                    && !movement.jumping
                    && !movement.shiftKeyDown
                    && !ServerFlightHandler.isSpin(player)
                    && !ServerFlightHandler.isGliding(player);

            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

            if (shouldHover && noMoveInput) {
                ax = 0.0;
                az = 0.0;

                // 创造模式没有重力，需额外清零垂直速度和加速度
                if (player.isCreative()) {
                    ay = 0.0;
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            }

            // 飞行等级不足 1 的龙追加额外重力，模拟非稳定悬停的 elytra 式下落
            if (!shouldHover && flightLevel < 1.0 && noMoveInput) {
                double gravity = player.getAttributeValue(Attributes.GRAVITY);
                Vec3 delta = player.getDeltaMovement();
                player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
            }
        });
    }
}
