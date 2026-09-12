package com.zonlong.beloong.transport;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.util.LandingY;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class DimensionTransportHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 每个玩家的冷却剩余 ticks */
    private static final Map<UUID, Integer> COOLDOWNS = new HashMap<>();

    /** 每个玩家的检查间隔计数器 */
    private static final Map<UUID, Integer> TICK_COUNTERS = new HashMap<>();

    public DimensionTransportHandler() {}

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        // 仅服务端处理
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 死亡或已移除的玩家跳过
        if (!player.isAlive() || player.isRemoved()) {
            return;
        }

        UUID uuid = player.getUUID();

        // 冷却递减
        int cooldown = COOLDOWNS.getOrDefault(uuid, 0);
        if (cooldown > 0) {
            COOLDOWNS.put(uuid, cooldown - 1);
            return;
        }

        // 间隔检查
        int interval = Config.DimensionTransport.checkIntervalTicks.get();
        int counter = TICK_COUNTERS.getOrDefault(uuid, 0) + 1;
        if (counter < interval) {
            TICK_COUNTERS.put(uuid, counter);
            return;
        }
        TICK_COUNTERS.put(uuid, 0);

        // 检查两个方向的触发条件
        tryTransportToConfiguredDestination(player,
                player.level().dimension().location().toString(),
                Level.OVERWORLD.location().toString(),
                Config.DimensionTransport.owToLP_enabled.get(),
                Config.DimensionTransport.owToLP_triggerY.get(),
                Config.DimensionTransport.owToLP_targetDimension.get(),
                Config.DimensionTransport.owToLP_targetX.get(),
                Config.DimensionTransport.owToLP_targetZ.get(),
                Config.DimensionTransport.owToLP_fallbackY.get());

        tryTransportToOverworldSpawn(player,
                player.level().dimension().location().toString(),
                ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace").toString(),
                Config.DimensionTransport.lpToOw_enabled.get(),
                Config.DimensionTransport.lpToOw_triggerY.get());
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void tryTransportToConfiguredDestination(ServerPlayer player,
            String currentDim, String sourceDim,
            boolean enabled, int triggerY,
            String targetDimStr, double targetX, double targetZ, double fallbackY) {

        if (!enabled) return;
        if (!currentDim.equals(sourceDim)) return;
        if (player.getY() <= triggerY) return;

        // 解析目标维度
        ResourceLocation targetDimId = ResourceLocation.tryParse(targetDimStr);
        if (targetDimId == null) {
            LOGGER.warn("[BeLoongCore] Invalid target dimension ID: {}", targetDimStr);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.dimension_transport.invalid_dimension", targetDimStr));
            return;
        }

        // 防止传送到同一维度
        if (currentDim.equals(targetDimId.toString())) return;

        // 获取目标 ServerLevel
        ServerLevel targetLevel = player.server.getLevel(
                net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, targetDimId));
        if (targetLevel == null) {
            LOGGER.warn("[BeLoongCore] Target dimension not found: {}", targetDimId);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.dimension_transport.dimension_not_found", targetDimId.toString()));
            return;
        }

        // 解除骑乘
        if (player.isPassenger()) {
            player.stopRiding();
        }

        // 非阻塞解析落脚点：命中内存中的区块才读高度图，未加载则直接用 fallbackY
        // （旧实现在此处同步 getChunk + getHeight，会在主线程等待区块生成——不受支持的用法）
        double safeY = LandingY.resolveOrFallback(targetLevel, targetX, targetZ, fallbackY);

        // 执行传送
        player.teleportTo(targetLevel,
                targetX, safeY, targetZ,
                Set.of(), player.getYRot(), player.getXRot());

        finishTransport(player);

        LOGGER.debug("[BeLoongCore] {} 从 {} 传送到 {} ({}, {}, {})",
                player.getName().getString(), sourceDim, targetDimId,
                targetX, safeY, targetZ);
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void tryTransportToOverworldSpawn(ServerPlayer player,
            String currentDim, String sourceDim,
            boolean enabled, int triggerY) {
        if (!enabled) return;
        if (!currentDim.equals(sourceDim)) return;
        if (player.getY() >= triggerY) return;

        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.warn("[BeLoongCore] Overworld not found for void transport");
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.dimension_transport.dimension_not_found",
                    Level.OVERWORLD.location().toString()));
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        // 本方法的落点 Y 恒为世界出生点自身的 Y（从不使用高度图），因此旧实现里那行
        // "确保目标区块已加载"的 getChunk 既不必要、又会在主线程阻塞——已删除。
        // 落点区块的加载由玩家的 TicketType.PLAYER 票据长期保证；teleportTo 另带一张
        // TicketType.POST_TELEPORT，但它的寿命只有 5 tick（TicketType.java:18），只覆盖传送瞬间。
        BlockPos spawnPos = overworld.getSharedSpawnPos();

        double targetX = spawnPos.getX() + 0.5;
        double targetY = spawnPos.getY();
        double targetZ = spawnPos.getZ() + 0.5;
        player.teleportTo(overworld,
                targetX, targetY, targetZ,
                Set.of(), player.getYRot(), player.getXRot());

        finishTransport(player);

        LOGGER.debug("[BeLoongCore] {} 从 {} 传送到主世界出生点 ({}, {}, {})",
                player.getName().getString(), sourceDim,
                targetX, targetY, targetZ);
    }

    private static void finishTransport(ServerPlayer player) {
        player.fallDistance = 0;

        UUID uuid = player.getUUID();
        COOLDOWNS.put(uuid, Config.DimensionTransport.cooldownTicks.get());
        TICK_COUNTERS.remove(uuid);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().getUUID();
        COOLDOWNS.remove(uuid);
        TICK_COUNTERS.remove(uuid);
    }
}
