package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.ServerFlightHandler;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 在 {@link LivingEntity#travel} 中为“稳定悬停”取消重力，解决 ClientTickEvent.Pre 阶段
 * 修改垂直速度后仍被流体/移动逻辑覆盖导致缓慢下沉的问题。
 *
 * <p>只对本地龙玩家生效：当 DS 的 {@code stableHover} 开启、飞行等级 &ge; 1、翅膀展开、
 * 且没有按下跳跃/下潜键时，把水中/飞行状态的重力视为 0，让飞行方向能跟随视角，
 * 不再叠加 DS 原版滑翔自带的向下偏移。</p>
 */
@Mixin(value = LivingEntity.class, remap = false)
public abstract class LivingEntityStableHoverMixin {

    @ModifyVariable(method = "travel", at = @At(value = "STORE", ordinal = 0), remap = false)
    private double beloong$disableGravityForStableHover(double gravity) {
        if (!((Object) this instanceof LocalPlayer player)) {
            return gravity;
        }

        if (!Config.FIX_STABLE_HOVER.get() || !ServerFlightHandler.stableHover) {
            return gravity;
        }

        if (player.onGround() || player.isPassenger() || player.isSpectator() || player.hasEffect(MobEffects.LEVITATION)) {
            return gravity;
        }

        if (!DragonStateProvider.isDragon(player)) {
            return gravity;
        }

        FlightData flightData = FlightData.getData(player);
        if (!flightData.isWingsSpread() || !flightData.hasFlight()) {
            return gravity;
        }

        if (ModAttributes.getFlightLevel(player) < 1.0) {
            return gravity;
        }

        // 旋转攻击不应被锁定高度
        if (ServerFlightHandler.isSpin(player)) {
            return gravity;
        }

        // 处理水中或真实飞行中的稳定悬停；滑翔状态仍保留，这里只取消持续下拉的重力
        if (!player.isInWater() && !ServerFlightHandler.isFlying(player)) {
            return gravity;
        }

        // 跳跃/下潜是主动升降操作，不取消重力
        if (player.input.jumping || player.input.shiftKeyDown) {
            return gravity;
        }

        return 0.0;
    }
}
