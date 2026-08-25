package com.zonlong.beloong.mixin.dragonsurvival;

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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 DS 稳定悬停漂移 + 实现飞行等级控制的悬停/非悬停切换。
 *
 * <h3>原有功能：修复漂移</h3>
 * 当 DS 的 {@code stableHover = true} 时，悬停中不做任何操控龙会向上缓慢漂移；
 * 创造模式漂移更明显。本 Mixin 在 {@code flightControl()} 末尾注入，检测到
 * 无输入 + 应悬停时清零水平和垂直加速度。
 *
 * <h3>新增功能：飞行等级控制的悬停切换</h3>
 * 飞行等级系统要求：{@code FLIGHT_LEVEL >= 1} 时稳定悬停，{@code FLIGHT_LEVEL < 1} 时
 * 模拟鞘翅式下坠。本 Mixin 尊重 DS 的 {@code stableHover} 配置：
 * <ul>
 *   <li>{@code stableHover = false} 时完全不干预，由 DS 原版物理处理；</li>
 *   <li>{@code stableHover = true} 时，仅在玩家处于真实空中飞行、无操作输入时调整：</li>
 *   <ul>
 *     <li>{@code FLIGHT_LEVEL >= 1} + 无输入 → 清零加速度 = 稳定悬停</li>
 *     <li>{@code FLIGHT_LEVEL < 1} + 无输入 → 追加额外重力 = 模拟非稳定下坠</li>
 *   </ul>
 * </ul>
 *
 * <p>水中、熔岩、地面、骑乘、滑翔、旋转状态均不干预，保持 DS 原版行为。</p>
 *
 * <h3>性能</h3>
 * 每客户端 tick 在 {@code flightControl()} 末尾执行一次。对非龙玩家、无翅玩家、
 * 非空中飞行或存在操作输入时，通过早期返回跳过主体逻辑。
 *
 * @see com.zonlong.beloong.registry.ModAttributes#getFlightLevel
 */
@Mixin(value = ClientFlightHandler.class, remap = false)
public abstract class ClientFlightHandlerMixin {

    /**
     * 在 {@code flightControl} 完成所有飞行动力学计算后注入。
     * 用户可通过配置文件中的 {@code fixStableHoverDrift} 开关禁用整个修复。
     */
    @Inject(method = "flightControl", at = @At("TAIL"), remap = false)
    private static void fixStableHoverDrift(CallbackInfo ci) {
        // 配置开关：允许用户禁用此修复
        if (!Config.FIX_STABLE_HOVER.get()) {
            return;
        }

        // 尊重 DS 配置：stableHover=false 时完全不干预
        if (!ServerFlightHandler.stableHover) {
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            // 仅处理龙玩家
            if (!handler.isDragon()) {
                return;
            }

            // 翅膀未展开或无飞行能力时不干预
            FlightData flightData = FlightData.getData(player);
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) {
                return;
            }

            // 只处理真实空中飞行，排除水中/熔岩/地面/骑乘
            if (!ServerFlightHandler.isFlying(player)) {
                return;
            }

            // 保持 DS 原版滑翔/旋转行为，不干预
            if (ServerFlightHandler.isGliding(player) || ServerFlightHandler.isSpin(player)) {
                return;
            }

            Input movement = player.input;
            double flightLevel = ModAttributes.getFlightLevel(player);

            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;
            boolean noVerticalInput = !movement.jumping && !movement.shiftKeyDown;

            // 有任何操作输入时不干预，交给 DS 处理
            if (!noMoveInput || !noVerticalInput) {
                return;
            }

            if (flightLevel >= 1.0) {
                // 稳定悬停：清零水平加速度
                ClientFlightHandlerAccessor.beloong$setAx(0.0);
                ClientFlightHandlerAccessor.beloong$setAz(0.0);

                // 创造模式无重力，需额外清零垂直速度和加速度防止上漂
                if (player.isCreative()) {
                    ClientFlightHandlerAccessor.beloong$setAy(0.0);
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            } else if (flightLevel < 1.0) {
                // 非稳定悬停：追加额外重力模拟 elytra 式下落
                // DS 的 stableHover=true 路径仅应用 -gravity；此处追加 -gravity
                // 使总重力达到 -(gravity×2)，与 DS 原版 stableHover=false 一致
                double gravity = player.getAttributeValue(Attributes.GRAVITY);
                Vec3 delta = player.getDeltaMovement();
                player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
            }
        });
    }
}
