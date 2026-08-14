package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.ClientMeteorRainState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 流星火雨状态同步网络包（服务端 → 客户端）。
 * <p>
 * 仅携带一个布尔值：当前客户端所在维度（天灾维度）是否处于流星火雨中。
 * 客户端收到后更新 {@link ClientMeteorRainState}，驱动天空染红/蒙层/警告。
 */
public record MeteorRainSyncPayload(boolean active) implements CustomPacketPayload {

    public static final Type<MeteorRainSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "meteor_rain_sync"));

    /** 使用 {@link RegistryFriendlyByteBuf} —— Play 阶段网络包要求此类型。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, MeteorRainSyncPayload> STREAM_CODEC =
            ByteBufCodecs.BOOL
                    .map(MeteorRainSyncPayload::new, MeteorRainSyncPayload::active)
                    .mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理器。默认在主线程执行，直接更新客户端状态。 */
    public static void handleClient(MeteorRainSyncPayload payload, IPayloadContext context) {
        ClientMeteorRainState.update(payload.active());
    }
}
