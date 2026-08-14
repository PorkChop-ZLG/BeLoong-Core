package com.zonlong.beloong.weather;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.entity.MeteorEntity;
import com.zonlong.beloong.network.MeteorRainSyncPayload;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 流星火雨事件处理器（服务端）。
 * <p>
 * 通过 {@code ServerTickEvent.Post} 节流驱动 {@link MeteorRainManager} 状态机，
 * 在 ACTIVE 期间向在线玩家附近生成陨石，并在相位迁移时向客户端广播同步。
 * 玩家登录/换维度进入天灾维度时补发一次同步。
 */
public class MeteorRainHandler {

    /** 天灾维度键。 */
    public static final ResourceKey<Level> DISASTER =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "disaster"));

    /** 状态机判定节流计数器。 */
    private int tickCounter;

    /** 陨石生成波次节流计数器。 */
    private int spawnCounter;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!Config.MeteorRain.enabled.get()) {
            return;
        }

        ServerLevel disaster = event.getServer().getLevel(DISASTER);
        if (disaster == null) {
            return;
        }

        // 天灾维度无在线玩家时跳过全部处理（性能边界）
        boolean anyPlayer = false;
        for (ServerPlayer player : disaster.players()) {
            if (player.isAlive()) {
                anyPlayer = true;
                break;
            }
        }
        if (!anyPlayer) {
            return;
        }

        // 状态机判定节流
        boolean doStateTick = ++tickCounter >= Config.MeteorRain.checkIntervalTicks.get();
        if (doStateTick) {
            tickCounter = 0;
        }

        if (doStateTick) {
            boolean transitioned = MeteorRainManager.INSTANCE.tick(disaster);
            if (transitioned) {
                broadcast(disaster, MeteorRainManager.INSTANCE.isActive(DISASTER));
            }
        }

        // ACTIVE 期间按波次生成陨石
        if (MeteorRainManager.INSTANCE.isActive(DISASTER)) {
            boolean doSpawn = ++spawnCounter >= Config.MeteorRain.spawnIntervalTicks.get();
            if (doSpawn) {
                spawnCounter = 0;
                for (ServerPlayer player : disaster.players()) {
                    if (player.isAlive()) {
                        spawnMeteorsAround(player);
                    }
                }
            }
        } else {
            spawnCounter = 0;
        }
    }

    /** 在玩家水平半径内随机落点、从高处生成陨石。 */
    private void spawnMeteorsAround(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int count = Config.MeteorRain.meteorsPerPlayerPerSpawn.get();
        int radius = Config.MeteorRain.spawnRadius.get();
        int height = Config.MeteorRain.spawnHeight.get();

        for (int i = 0; i < count; i++) {
            double x = player.getX() + (level.random.nextDouble() * 2 - 1) * radius;
            double z = player.getZ() + (level.random.nextDouble() * 2 - 1) * radius;
            MeteorEntity meteor = new MeteorEntity(level, x, height, z);
            level.addFreshEntity(meteor);
        }
    }

    /** 向天灾维度内所有玩家广播当前天气状态。 */
    private void broadcast(ServerLevel level, boolean active) {
        for (ServerPlayer player : level.players()) {
            PacketDistributor.sendToPlayer(player, new MeteorRainSyncPayload(active));
        }
    }

    /** 换维度进入天灾维度时补发同步。 */
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(DISASTER)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new MeteorRainSyncPayload(MeteorRainManager.INSTANCE.isActive(DISASTER)));
    }

    /** 登录进入天灾维度时补发同步。 */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!player.level().dimension().equals(DISASTER)) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
                new MeteorRainSyncPayload(MeteorRainManager.INSTANCE.isActive(DISASTER)));
    }
}
