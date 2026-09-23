package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 飞行状态同步网络包。
 *
 * <p>Dragon Survival 的 {@code stable_hover} 是<b>服务端</b>配置
 * （{@code ServerFlightHandler.java:77-78}，{@code ConfigSide.SERVER}），
 * 而本模组的稳定悬停修复必须知道它才能决定是否介入。此包由服务端在玩家登录时
 * 把权威值发给该玩家，客户端存入 {@link ClientFlightStatusCache}。</p>
 *
 * <p>选择自建 payload 而非直接读静态字段，是为了消除"远端专用服务器的客户端
 * 能否读到服务端配置"这一未实测的不确定性。</p>
 *
 * @param stableHover 服务端读到的 DS {@code stable_hover} 值
 */
public record FlightStatusSyncPayload(boolean stableHover) implements CustomPacketPayload {

    public static final Type<FlightStatusSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "flight_status_sync"));

    /**
     * 使用 {@link RegistryFriendlyByteBuf} —— Play 阶段网络包要求此类型，
     * 内部 {@code ByteBuf} codec 依然兼容（{@code RegistryFriendlyByteBuf extends ByteBuf}）。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, FlightStatusSyncPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL
                    .map(FlightStatusSyncPayload::new, FlightStatusSyncPayload::stableHover)
                    .mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器。默认在主线程执行，直接写入缓存。
     */
    public static void handleClient(FlightStatusSyncPayload payload, IPayloadContext context) {
        ClientFlightStatusCache.INSTANCE.loadFromSync(payload.stableHover());
    }
}
