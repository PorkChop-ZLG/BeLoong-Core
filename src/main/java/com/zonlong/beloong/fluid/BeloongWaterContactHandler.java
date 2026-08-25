package com.zonlong.beloong.fluid;

import by.dragonsurvivalteam.dragonsurvival.config.ServerConfig;
import by.dragonsurvivalteam.dragonsurvival.network.container.OpenDragonAltar;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.AltarData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.DragonSpecies;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BeloongWaterContactHandler {

    private static final String ALTAR_COOLDOWN = "dragonsurvival.gui.message.altar_cooldown";

    private final BeloongWaterContactTracker contactTracker = new BeloongWaterContactTracker();
    private final BeloongWaterTriggerCooldown triggerCooldown = new BeloongWaterTriggerCooldown();
    private final Map<UUID, BlockPos> lastScannedPositions = new HashMap<>();

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        // 维度早退：当前维度没有化龙池水区域时完全不检测
        if (BeloongWaterRegionLoader.INSTANCE
                .getRegions(player.level().dimension()).isEmpty()) {
            return;
        }

        // 方块坐标未变化时跳过扫描，避免静止状态下每 tick 全量扫描
        BlockPos currentPos = player.blockPosition();
        BlockPos lastPos = lastScannedPositions.get(player.getUUID());
        if (currentPos.equals(lastPos)) {
            return;
        }
        lastScannedPositions.put(player.getUUID(), currentPos);

        boolean touching = player.isAlive()
                && !player.isRemoved()
                && BeloongWaterContactDetector.isTouching(player);
        if (!contactTracker.update(player.getUUID(), touching)) {
            return;
        }

        long currentTick = player.getServer().getTickCount();
        int cooldownTicks = Config.BeloongWater.triggerCooldownTicks.get();
        if (!triggerCooldown.isReady(player.getUUID(), currentTick, cooldownTicks)) {
            return;
        }

        if (openDragonAltar(player)) {
            triggerCooldown.recordTrigger(player.getUUID(), currentTick);
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        contactTracker.update(event.getEntity().getUUID(), false);
        lastScannedPositions.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        contactTracker.update(event.getEntity().getUUID(), false);
        lastScannedPositions.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        contactTracker.update(event.getEntity().getUUID(), false);
        triggerCooldown.forget(event.getEntity().getUUID());
        lastScannedPositions.remove(event.getEntity().getUUID());
    }

    private static boolean openDragonAltar(ServerPlayer player) {
        AltarData data = AltarData.getData(player);
        if (ServerConfig.altarUsageCooldown > 0 && data.altarCooldown > 0) {
            Functions.Time time = Functions.Time.fromTicks(data.altarCooldown);
            player.sendSystemMessage(Component.translatable(ALTAR_COOLDOWN, time.format()));
            return false;
        }

        data.altarCooldown = Functions.secondsToTicks(ServerConfig.altarUsageCooldown);
        data.hasUsedAltar = true;
        data.isInAltar = true;
        PacketDistributor.sendToPlayer(
                player,
                new OpenDragonAltar(DragonSpecies.getSpecies(player, true)));
        return true;
    }
}
