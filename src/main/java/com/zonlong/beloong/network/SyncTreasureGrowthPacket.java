package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.client.TreasureGrowthClientData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端→客户端：同步财宝值、效果等级、成长倍率和休息状态 */
public record SyncTreasureGrowthPacket(
        double treasureValue,
        int amplifier,
        double growthMultiplier,
        boolean isResting
) implements CustomPacketPayload {

    public static final Type<SyncTreasureGrowthPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "sync_treasure_growth")
    );

    public static final StreamCodec<ByteBuf, SyncTreasureGrowthPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, SyncTreasureGrowthPacket::treasureValue,
            ByteBufCodecs.VAR_INT, SyncTreasureGrowthPacket::amplifier,
            ByteBufCodecs.DOUBLE, SyncTreasureGrowthPacket::growthMultiplier,
            ByteBufCodecs.BOOL, SyncTreasureGrowthPacket::isResting,
            SyncTreasureGrowthPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端接收后，将数据写入共享缓存供 HUD 渲染读取 */
    public static void handleClient(final SyncTreasureGrowthPacket packet, final IPayloadContext context) {
        context.enqueueWork(() ->
                TreasureGrowthClientData.update(packet.treasureValue, packet.growthMultiplier, packet.isResting)
        );
    }
}
