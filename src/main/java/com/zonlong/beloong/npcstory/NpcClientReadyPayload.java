package com.zonlong.beloong.npcstory;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「我已经真正进入游戏了」（客户端 → 服务端，**无数据**）。
 *
 * <h2>为什么需要它</h2>
 * 序章的等待原本用 {@code ServerPlayer#tickCount}，而它从**服务端收下玩家**那一刻就开始累加 ——
 * 与"客户端能不能看见世界"无关。房主客户端本地加载很快，5 秒够；**远程玩家那 5 秒还要额外涵盖
 * 地形从网络流过来** ⇒ 登场 CG 在他还看着"加载地形中"时就开播了（实机日志：房主
 * {@code logged in} → {@code granted root} = 5.302 秒）。原版**没有**"客户端加载完成"这个信号，
 * 所以只能由客户端把"我已经就绪"送过来。
 *
 * <h2>客户端何时发</h2>
 * {@code Minecraft.getInstance().level != null} 且 {@code getOverlay() == null}
 * （加载遮罩消失 —— **原版自己就用这个判据**判断"能不能接受操作"，见 {@code MouseHandler} 与
 * {@code KeyboardHandler}）且**连续 20 tick** 都满足 ⇒ 发一次，本次会话不再发。
 *
 * <h2>服务端怎么用</h2>
 * 记下"该玩家就绪于 &lt;gameTime&gt;"（只在内存，登出清理）⇒ 序章的延迟从这一刻起算。
 * ⚠️ <b>必须有兜底</b>：若始终收不到信号（老客户端 / 模组冲突 / 被改的客户端），
 * 序章会在 {@code tickCount > delay + 60 秒} 时照旧开场并打 WARN —— 否则玩家会**永远没有剧情**。
 *
 * <h2>⚠️ 安全</h2>
 * 载荷**不携带任何内容**，服务端只把它当作"时机信号"；起点进度（{@code root}）**仍然只由服务端授予**。
 * 受理口径照本项目既有包：**失败一律静默**（载荷是客户端可控输入，没有可提示的对象，
 * 且服务端线程里绝不能抛异常）。
 */
public record NpcClientReadyPayload() implements CustomPacketPayload {

    public static final NpcClientReadyPayload INSTANCE = new NpcClientReadyPayload();

    public static final Type<NpcClientReadyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "npc_client_ready"));

    /** 无字段 ⇒ {@code unit} 即可（读/写都不消费字节）。 */
    public static final StreamCodec<net.minecraft.network.FriendlyByteBuf, NpcClientReadyPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理器（默认在服务端主线程执行）。重复上报忽略（保留第一次）。 */
    public static void handleServer(NpcClientReadyPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        NpcPrologueHandler.markClientReady(player);
    }
}
