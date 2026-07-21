package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.treasure.ClientTreasureCache;
import io.netty.buffer.ByteBuf;
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

    /**
     * 单个可序列化的财宝条目。
     * STREAM_CODEC 使用 {@link ByteBuf}（最不特定的缓冲区类型，符合最佳实践）。
     */
    public record SyncedEntry(String blockId, double value, int limit, boolean isDragon) {
        static final StreamCodec<ByteBuf, SyncedEntry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SyncedEntry::blockId,
                        ByteBufCodecs.DOUBLE,      SyncedEntry::value,
                        ByteBufCodecs.INT,          SyncedEntry::limit,
                        ByteBufCodecs.BOOL,         SyncedEntry::isDragon,
                        SyncedEntry::new
                );
    }

    /**
     * 使用 {@link RegistryFriendlyByteBuf} — Play 阶段网络包要求此类型，
     * 但内部组件的 {@code ByteBuf} codec 依然兼容（{@code RegistryFriendlyByteBuf extends ByteBuf}）。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, TreasureSyncPayload> STREAM_CODEC =
            SyncedEntry.STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(TreasureSyncPayload::new, TreasureSyncPayload::entries)
                    .mapStream(buf -> (ByteBuf) buf);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 客户端处理器。默认在主线程执行，直接写入缓存。
     */
    public static void handleClient(TreasureSyncPayload payload, IPayloadContext context) {
        ClientTreasureCache.INSTANCE.loadFromSync(payload.entries());
    }
}
