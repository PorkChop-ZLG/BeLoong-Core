package com.zonlong.beloong.cg;

import com.zonlong.beloong.BeLoongCore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「这段过场我这边**真的播完了**」（客户端 → 服务端，带 CG 名）。
 *
 * <h2>为什么需要它</h2>
 * 过场由**客户端本地播放**（{@code FDLibCalls.startCutsceneForPlayer} 把烘好的轨迹交过去），
 * 而完成判定原本由**服务端自己的钟**做（{@code cutsceneTime} 之后就算"播完"）——
 * 两个钟互相独立。实机日志：9 次 {@code cg-start → granted '1_0'} 里 8 次**精确 6.00 秒**，
 * 与客户端在做什么无关；表情上报里还出现过一次 {@code will be cleared in 44 ticks}
 * —— 服务端登记那只表情时客户端只剩 44 tick ⇒ 服务端 44 tick 后清掉它 ⇒ **动画被硬生生截断**
 * （运镜属客户端过场、仍在跑）。用户看到的正是"动画播一半就停、CG 运镜还在"。
 *
 * <h2>服务端怎么用</h2>
 * 只在"该玩家此刻**确有待结算的 CG**"时才生效 ⇒ 立刻结算并授予完成进度
 * （{@link CgCompletionHandler#settleFromClient}）；否则忽略（**幂等**）。
 * <p>
 * ⚠️ <b>服务端自己的计时**保留为兜底**</b>（{@code cutsceneTime + 200} tick）——
 * 客户端崩了 / 没上报 / 被中断时仍会授予，所以用户定的
 * "**只要 CG 开始过就一定拿到进度**"这条语义**不会破**。
 *
 * <h2>⚠️ 安全</h2>
 * 载荷只带 **CG 名**，服务端**不采信任何进度内容**，且只对"本来就在待结算表里"的条目生效。
 * 受理口径与 {@link NpcEmoteDurationPayload} 一致：**失败一律静默**。
 *
 * @param cgName 客户端播完的那段 CG 的名字（{@code CgAnimation#name()}）
 */
public record CgFinishedPayload(String cgName) implements CustomPacketPayload {

    public static final Type<CgFinishedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "cg_finished"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CgFinishedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CgFinishedPayload::cgName,
                    CgFinishedPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 服务端处理器（默认在服务端主线程执行）。没有对应待结算项 ⇒ 静默忽略。 */
    public static void handleServer(CgFinishedPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        CgCompletionHandler.settleFromClient(player, payload.cgName());
    }
}
