package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.treasure.ClientTreasureCache;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 财宝数据同步网络包。
 * <p>
 * 服务端在玩家登录时将全量财宝条目序列化发送至客户端，
 * 客户端接收后存入 {@link ClientTreasureCache}。
 */
public record TreasureSyncPayload(List<SyncedEntry> entries) implements CustomPacketPayload {

    public static final Type<TreasureSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "treasure_sync"));

    /** 单个可序列化的财宝条目 */
    public record SyncedEntry(String blockId, double value, int limit, boolean isDragon) {
        static final StreamCodec<RegistryFriendlyByteBuf, SyncedEntry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SyncedEntry::blockId,
                        ByteBufCodecs.DOUBLE,      SyncedEntry::value,
                        ByteBufCodecs.INT,          SyncedEntry::limit,
                        ByteBufCodecs.BOOL,         SyncedEntry::isDragon,
                        SyncedEntry::new
                );
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, TreasureSyncPayload> STREAM_CODEC =
            SyncedEntry.STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(TreasureSyncPayload::new, TreasureSyncPayload::entries);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端处理器：入队到主线程写入缓存 */
    public static void handleClient(TreasureSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientTreasureCache.INSTANCE.loadFromSync(payload.entries()));
    }
}
