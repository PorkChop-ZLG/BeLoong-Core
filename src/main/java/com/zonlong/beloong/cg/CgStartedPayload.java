package com.zonlong.beloong.cg;

import com.zonlong.beloong.BeLoongCore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 「这段过场开始了，它长 N tick」（服务端 → 客户端）。
 *
 * <h2>为什么需要它</h2>
 * 过场由**客户端本地播放**，而"播完了"原本由服务端自己的钟判定 ⇒ 两套钟互相独立
 * （实机日志：9 次 {@code cg-start → granted '1_0'} 里 8 次精确 6.00 秒，与客户端无关；
 * 表情上报里出现过"只剩 44 ticks" ⇒ 动画被服务端中途清掉）。
 * <p>
 * <b>本包就是"客户端那一侧的起点"</b>：客户端**收到它的那一刻**才是它真正开始播的时刻
 * （加载地形期间的包会排队，客户端能处理时才处理）⇒ 客户端从这里开始本地计时，
 * 到点后回 {@link CgFinishedPayload} ⇒ 服务端据此结算。
 * <p>
 * ⚠️ 这样就不必依赖第三方过场库（FDLib）的客户端回调 —— 我们只依赖"自己的包什么时候到"，
 * 那正是客户端开始播的同一时刻。
 *
 * @param cgName        这段过场的名字（回传时原样带回）
 * @param durationTicks 服务端烘出来的过场时长（tick），客户端照它计时
 */
public record CgStartedPayload(String cgName, int durationTicks) implements CustomPacketPayload {

    public static final Type<CgStartedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "cg_started"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CgStartedPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, CgStartedPayload::cgName,
                    ByteBufCodecs.VAR_INT, CgStartedPayload::durationTicks,
                    CgStartedPayload::new
            ).mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理器（只会在物理客户端被调用 ⇒ 可以安全引用客户端专属类）。 */
    public static void handleClient(CgStartedPayload payload, IPayloadContext context) {
        com.zonlong.beloong.client.NpcClientSyncHandler.onCgStarted(payload.cgName(), payload.durationTicks());
    }
}
