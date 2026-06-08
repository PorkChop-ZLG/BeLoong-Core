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
 * 修复 DS 稳定悬停漂移 + 实现飞行等级控制的悬停/非悬停切换。
 *
 * <h3>原有功能：修复漂移</h3>
 * 当 DS 的 {@code stableHover = true} 时，悬停中不做任何操控龙会向上缓慢漂移；
 * 创造模式漂移更明显。本 Mixin 在 {@code flightControl()} 末尾注入，检测到
 * 无输入 + 应悬停时清零水平和垂直加速度。
 *
 * <h3>新增功能：飞行等级控制的悬停切换</h3>
 * 飞行等级系统要求：{@code FLIGHT_LEVEL >= 1} 时稳定悬停，{@code FLIGHT_LEVEL < 1} 时
 * 模拟鞘翅式下坠。DS 的 {@code stableHover} 配置由用户控制，本 Mixin 不覆盖。
 *
 * <p>当 {@code stableHover = true} 时，DS 物理使用柔和重力（{@code -gravity}）。
 * 本 Mixin 在 TAIL 阶段根据飞行等级调整：</p>
 * <ul>
 *   <li>{@code FLIGHT_LEVEL >= 1} + 无输入 → 清零加速度 = 稳定悬停</li>
 *   <li>{@code FLIGHT_LEVEL < 1} + 无输入 → 追加额外重力 = 模拟非稳定下坠</li>
 * </ul>
 *
 * <p>当 {@code stableHover = false} 时，DS 自身使用双倍重力（{@code -(gravity×2)}），
 * 本 Mixin 的悬停修正不会覆盖飞行控制已设定的速度，自然表现为无稳定悬停。</p>
 *
 * <h3>性能</h3>
 * 每客户端 tick 在 {@code flightControl()} 末尾执行一次。对非龙玩家或无翅玩家，
 * 通过早期返回跳过主体逻辑（{@code handler.isDragon()}、{@code isWingsSpread()} 检查）。
 *
 * @see com.zonlong.beloong.registry.ModAttributes#getFlightLevel
 */
@Mixin(ClientFlightHandler.class)
public abstract class ClientFlightHandlerMixin {

    /** 龙的 X 轴加速度（前后），Shadow 映射目标类 {@code ClientFlightHandler.ax} */
    @Shadow
    private static double ax;

    /** 龙的 Z 轴加速度（左右），Shadow 映射目标类 {@code ClientFlightHandler.az} */
    @Shadow
    private static double az;

    /** 龙的 Y 轴加速度（垂直），Shadow 映射目标类 {@code ClientFlightHandler.ay} */
    @Shadow
    private static double ay;

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

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        DragonStateProvider.getOptional(player).ifPresent(handler -> {
            // 仅处理龙玩家
            if (!handler.isDragon()) return;

            // 翅膀未展开或无飞行能力时不干预
            FlightData flightData = FlightData.getData(player);
            if (!flightData.isWingsSpread() || !flightData.hasFlight()) return;

            Input movement = player.input;
            double flightLevel = ModAttributes.getFlightLevel(player);

            // 判断是否应进入稳定悬停：
            //   FLIGHT_LEVEL >= 1 && 未按跳跃/潜行 && 未旋转/滑翔
            boolean shouldHover = flightLevel >= 1.0
                    && !movement.jumping
                    && !movement.shiftKeyDown
                    && !ServerFlightHandler.isSpin(player)
                    && !ServerFlightHandler.isGliding(player);

            // 无水平移动输入（键盘 WASD 均未按下）
            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;

            // ── 稳定悬停：清零加速度 ──
            if (shouldHover && noMoveInput) {
                ax = 0.0;
                az = 0.0;

                // 创造模式无重力，需额外清零垂直速度和加速度防止上漂
                if (player.isCreative()) {
                    ay = 0.0;
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            }

            // ── 非稳定悬停：追加额外重力模拟 elytra 式下落 ──
            // DS 的 stableHover=true 路径仅应用 -gravity；此处追加 -gravity
            // 使总重力达到 -(gravity×2)，与 DS 原版 stableHover=false 一致
            if (!shouldHover && flightLevel < 1.0 && noMoveInput) {
                double gravity = player.getAttributeValue(Attributes.GRAVITY);
                Vec3 delta = player.getDeltaMovement();
                player.setDeltaMovement(delta.x, delta.y - gravity, delta.z);
            }
        });
    }
}
