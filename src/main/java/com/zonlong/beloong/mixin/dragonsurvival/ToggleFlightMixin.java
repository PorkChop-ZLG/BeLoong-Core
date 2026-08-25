package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.network.flight.ToggleFlight;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 DS 展翅逻辑前注入飞行等级门控。
 *
 * <h3>工作原理</h3>
 * 当玩家按下 G 键（或双击跳跃）试图展翅时，{@link ToggleFlight#handleServer} 被调用，
 * 随后通过 {@code context.enqueueWork} 将真正逻辑切换到服务端主线程。
 * 本 Mixin 注入到主线程执行的 {@code lambda$handleServer$1} HEAD：
 * <ul>
 *   <li>玩家是否试图展翅（{@code !flight.areWingsSpread}，即翅膀当前是收起的）</li>
 *   <li>有效飞行等级是否 &lt; 0</li>
 * </ul>
 * 若两者均满足，则直接返回 {@code Result.NONE}（无动作/无提示）。
 *
 * <h3>线程说明</h3>
 * 由于注入点位于 {@code context.enqueueWork} 的 lambda 内，属性读取在服务端主线程执行，
 * 避免了在网络线程读取 attribute 的线程安全问题。
 *
 * @see ModAttributes#getFlightLevel(Player)
 */
@Mixin(value = ToggleFlight.class, remap = false)
public class ToggleFlightMixin {

    @Inject(method = "lambda$handleServer$1", at = @At("HEAD"), cancellable = true, remap = false)
    private static void flightLevelGate(
            IPayloadContext context,
            ToggleFlight packet,
            CallbackInfoReturnable<ToggleFlight.Result> cir
    ) {
        Player player = context.player();
        if (player == null) {
            return;
        }

        FlightData flight = FlightData.getData(player);

        // 仅在展翅时拦截（收翅不需要飞行等级）
        if (!flight.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
            // 返回 NONE 而非 WINGS_BLOCKED——NONE 在客户端不显示消息
            // WINGS_BLOCKED 的提示"翅膀被阻挡或损坏"对禁空场景语义不准确
            cir.setReturnValue(ToggleFlight.Result.NONE);
        }
    }
}
