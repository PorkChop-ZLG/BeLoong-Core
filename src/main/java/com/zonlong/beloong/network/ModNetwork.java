package com.zonlong.beloong.network;

import com.zonlong.beloong.BeLoongCore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** 在 MOD 总线上注册自定义网络包，MOD bus 自动发现无需手动注册 */
@EventBusSubscriber(modid = BeLoongCore.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetwork {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(BeLoongCore.MODID).versioned("1.0.0");
        // 服务端→客户端：同步财宝成长数据
        registrar.playToClient(
                SyncTreasureGrowthPacket.TYPE,
                SyncTreasureGrowthPacket.STREAM_CODEC,
                SyncTreasureGrowthPacket::handleClient
        );
    }
}
