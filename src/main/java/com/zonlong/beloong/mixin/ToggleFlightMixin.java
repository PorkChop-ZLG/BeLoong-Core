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
 * 在 DS 展翅逻辑前注入飞行等级门控。
 *
 * <h3>工作原理</h3>
 * 当玩家按下 G 键（或双击跳跃）试图展翅时，{@link ToggleFlight#handleServer} 被调用。
 * 本 Mixin 在 {@code HEAD} 注入，在执行 {@code enqueueWork} 主逻辑之前检查：
 * <ul>
 *   <li>玩家是否试图展翅（{@code !flight.areWingsSpread}，即翅膀当前是收起的）</li>
 *   <li>有效飞行等级是否 &lt; 0</li>
 * </ul>
 * 若两者均满足，则取消原始逻辑，向客户端返回 {@code Result.NONE}（无动作/无提示）。
 *
 * <h3>线程说明</h3>
 * {@code handleServer} 在网络线程上调用（{@code context.enqueueWork} 将主逻辑切换到
 * 服务端主线程）。本注入在 {@code HEAD} 读取 {@code FlightData.areWingsSpread}
 * （一个 boolean 字段）。跨线程读取 boolean 的竞态风险极低：
 * <ul>
 *   <li>最坏情况：读到过期值，导致本应被拒绝的展翅通过或本应通过的展翅被拒绝</li>
 *   <li>下一 tick 的 {@code ServerFlightHandler.isFlying()} 会再次校验飞行等级，
 *       即使此处误放过，飞行逻辑本身也不会生效</li>
 * </ul>
 *
 * <h3>为什么不用 enqueueWork 内注入</h3>
 * enqueueWork 内的 lambda 编译为独立合成方法，标准 Mixin 注解无法可靠定位。
 * 在 HEAD 进行早期门控是经过验证的折中方案。
 *
 * @see ModAttributes#getFlightLevel(Player)
 */
@Mixin(value = ToggleFlight.class, remap = false)
public class ToggleFlightMixin {

    @Inject(method = "handleServer", at = @At("HEAD"), cancellable = true)
    private static void flightLevelGate(ToggleFlight packet, IPayloadContext context, CallbackInfo ci) {
        Player player = context.player();
        if (player == null) return;

        FlightData flight = FlightData.getData(player);

        // 仅在展翅时拦截（收翅不需要飞行等级）
        if (!flight.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
            // 返回 NONE 而非 WINGS_BLOCKED——NONE 在客户端不显示消息
            // WINGS_BLOCKED 的提示"翅膀被阻挡或损坏"对禁空场景语义不准确
            PacketDistributor.sendToPlayer(
                    (ServerPlayer) player,
                    new ToggleFlight(packet.activation(), ToggleFlight.Result.NONE)
            );
            ci.cancel();
        }
    }
}
