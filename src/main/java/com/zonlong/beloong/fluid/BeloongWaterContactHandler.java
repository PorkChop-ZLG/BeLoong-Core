package com.zonlong.beloong.fluid;

import by.dragonsurvivalteam.dragonsurvival.config.ServerConfig;
import by.dragonsurvivalteam.dragonsurvival.network.container.OpenDragonAltar;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.AltarData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.DragonSpecies;
import by.dragonsurvivalteam.dragonsurvival.util.Functions;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModFluids;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BeloongWaterContactHandler {

    private static final int TICKS_PER_SECOND = 20;
    private static final String ALTAR_COOLDOWN = "dragonsurvival.gui.message.altar_cooldown";

    private final BeloongWaterContactTracker contactTracker = new BeloongWaterContactTracker();
    private final BeloongWaterTriggerCooldown triggerCooldown = new BeloongWaterTriggerCooldown();

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        boolean touching = player.isAlive()
                && !player.isRemoved()
                && player.isInFluidType(ModFluids.BELOONG_WATER_TYPE.get());
        if (!contactTracker.update(player.getUUID(), touching)) {
            return;
        }

        long currentTick = player.getServer().getTickCount();
        int cooldownTicks = Config.BeloongWater.triggerCooldownSeconds.get() * TICKS_PER_SECOND;
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
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        contactTracker.update(event.getEntity().getUUID(), false);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        contactTracker.update(event.getEntity().getUUID(), false);
        triggerCooldown.forget(event.getEntity().getUUID());
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
