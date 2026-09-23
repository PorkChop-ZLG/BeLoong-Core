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
import net.minecraft.world.effect.MobEffects;
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
 * 应悬停时清零水平和垂直加速度，并锁定垂直速度。
 *
 * <h3>新增功能：飞行等级控制的悬停切换</h3>
 * 飞行等级系统要求：{@code FLIGHT_LEVEL >= 1} 时稳定悬停，{@code FLIGHT_LEVEL < 1} 时
 * 模拟鞘翅式下坠。本 Mixin 尊重 DS 的 {@code stableHover} 配置：
 * <ul>
 *   <li>{@code stableHover = false} 时完全不干预，由 DS 原版物理处理；</li>
 *   <li>{@code stableHover = true} 时，仅在玩家没有按下跳跃/下潜键时调整：</li>
 *   <ul>
 *     <li>水中且 {@code FLIGHT_LEVEL >= 1} → 锁定当前高度，不缓慢下沉</li>
 *     <li>空中（含滑翔）且 {@code FLIGHT_LEVEL >= 1} → 保留滑翔状态，飞行方向跟随视角，去掉原版向下偏移</li>
 *     <li>空中且 {@code FLIGHT_LEVEL < 1} → 追加额外重力 = 模拟非稳定下坠</li>
 *     <li>水中且 {@code FLIGHT_LEVEL < 1} → 不干预</li>
 *   </ul>
 * </ul>
 *
 * <p>保留滑翔/鞘翅状态，但开启稳定悬停后飞行方向跟随视角：平视不再有原版的向下偏移，
 * 抬头向上、低头向下；旋转、地面、骑乘、熔岩不干预。</p>
 *
 * <h3>性能</h3>
 * 每客户端 tick 在 {@code flightControl()} 末尾执行一次。对非龙玩家、无翅玩家、
 * 或存在垂直操作输入时，通过早期返回跳过主体逻辑。
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
        if (player == null || player.isPassenger() || Minecraft.getInstance().isPaused() || player.hasEffect(MobEffects.LEVITATION)) {
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

            Input movement = player.input;
            double flightLevel = ModAttributes.getFlightLevel(player);

            boolean noMoveInput = movement.forwardImpulse == 0 && movement.leftImpulse == 0;
            boolean noVerticalInput = !movement.jumping && !movement.shiftKeyDown;

            // 有垂直操作输入（跳跃/下潜）时不干预，交给 DS 处理
            if (!noVerticalInput) {
                return;
            }

            // 旋转攻击保持 DS 原版行为，不锁定高度
            if (ServerFlightHandler.isSpin(player)) {
                return;
            }

            // 水中稳定悬停：锁定当前高度（水平方向仅在无移动输入时清零，避免影响游泳转向）
            if (player.isInWater()) {
                if (flightLevel >= 1.0) {
                    if (noMoveInput) {
                        ClientFlightHandlerAccessor.beloong$setAx(0.0);
                        ClientFlightHandlerAccessor.beloong$setAz(0.0);
                    }

                    ClientFlightHandlerAccessor.beloong$setAy(0.0);
                    Vec3 delta = player.getDeltaMovement();
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
                // flightLevel < 1 时保持原版水中行为，不干预
                return;
            }

            // 只处理真实空中飞行，排除地面/骑乘/熔岩
            if (!ServerFlightHandler.isFlying(player)) {
                return;
            }

            // 保留滑翔状态，但让飞行方向跟随视角；去掉 DS 原版滑翔自带的向下偏移
            if (flightLevel >= 1.0) {
                // 稳定悬停：清零水平加速度（仅在无水平移动输入时）
                if (noMoveInput) {
                    ClientFlightHandlerAccessor.beloong$setAx(0.0);
                    ClientFlightHandlerAccessor.beloong$setAz(0.0);
                }

                ClientFlightHandlerAccessor.beloong$setAy(0.0);
                Vec3 delta = player.getDeltaMovement();

                if (ServerFlightHandler.isGliding(player)) {
                    // 滑翔时按视角方向飞行：保持当前速度大小，速度方向对齐视线
                    Vec3 look = player.getLookAngle();
                    double speed = delta.length();
                    if (speed > 1.0E-5) {
                        player.setDeltaMovement(look.scale(speed));
                    } else {
                        player.setDeltaMovement(delta.x, 0, delta.z);
                    }
                } else {
                    // 非滑翔的稳定悬停直接锁定高度
                    player.setDeltaMovement(delta.x, 0, delta.z);
                }
            } else if (flightLevel < 1.0 && noMoveInput && !ServerFlightHandler.isGliding(player)) {
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