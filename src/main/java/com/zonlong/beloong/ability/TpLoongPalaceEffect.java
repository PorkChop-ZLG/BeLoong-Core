package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.Set;

public record TpLoongPalaceEffect() implements AbilityEntityEffect {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<TpLoongPalaceEffect> CODEC = MapCodec.unit(new TpLoongPalaceEffect());

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof ServerPlayer player)) {
            return;
        }

        ResourceLocation currentDim = target.level().dimension().location();
        String owKey = Level.OVERWORLD.location().toString();
        String lpKey = ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace").toString();

        if (currentDim.toString().equals(owKey)) {
            teleportToLoongPalace(player);
        } else if (currentDim.toString().equals(lpKey)) {
            teleportToOverworldSpawn(player);
        }
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void teleportToLoongPalace(final ServerPlayer player) {
        String targetDimStr = Config.DimensionTransport.owToLP_targetDimension.get();
        ResourceLocation targetDimId = ResourceLocation.tryParse(targetDimStr);
        if (targetDimId == null) {
            LOGGER.warn("[BeLoongCore] Invalid target dimension ID: {}", targetDimStr);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.invalid_dimension", targetDimStr));
            return;
        }

        if (player.level().dimension().location().equals(targetDimId)) {
            return;
        }

        ServerLevel targetLevel = player.server.getLevel(
                ResourceKey.create(Registries.DIMENSION, targetDimId));
        if (targetLevel == null) {
            LOGGER.warn("[BeLoongCore] Target dimension not found: {}", targetDimId);
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.dimension_not_found", targetDimId.toString()));
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        double targetX = Config.DimensionTransport.owToLP_targetX.get();
        double targetZ = Config.DimensionTransport.owToLP_targetZ.get();
        double fallbackY = Config.DimensionTransport.owToLP_fallbackY.get();

        int blockX = (int) Math.floor(targetX);
        int blockZ = (int) Math.floor(targetZ);
        targetLevel.getChunk(blockX >> 4, blockZ >> 4);

        int topBlockY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        double safeY = topBlockY > targetLevel.getMinBuildHeight() ? topBlockY + 1.0 : fallbackY;

        player.teleportTo(targetLevel, targetX, safeY, targetZ,
                Set.of(), player.getYRot(), player.getXRot());
        player.fallDistance = 0;

        LOGGER.debug("[BeLoongCore] {} teleported from overworld to {} ({}, {}, {})",
                player.getName().getString(), targetDimId, targetX, safeY, targetZ);
    }

    // ServerLevel 实现 AutoCloseable；此处仅作世界引用，不可关闭（close() 会关闭区块源），抑制 resource 检查
    @SuppressWarnings("resource")
    private void teleportToOverworldSpawn(final ServerPlayer player) {
        ServerLevel overworld = player.server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            LOGGER.warn("[BeLoongCore] Overworld not found for teleport");
            player.sendSystemMessage(Component.translatable(
                    "message.beloong.tp_loong_palace.dimension_not_found",
                    Level.OVERWORLD.location().toString()));
            return;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        // Simulate end exit portal: check player respawn point first, fall back to world spawn
        BlockPos respawnPos = player.getRespawnPosition();
        BlockPos targetPos;
        if (respawnPos != null && Level.OVERWORLD.equals(player.getRespawnDimension())) {
            targetPos = respawnPos;
        } else {
            targetPos = overworld.getSharedSpawnPos();
        }

        overworld.getChunk(targetPos.getX() >> 4, targetPos.getZ() >> 4);
        int topBlockY = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, targetPos.getX(), targetPos.getZ());
        double safeY = topBlockY > overworld.getMinBuildHeight() ? topBlockY + 1.0 : targetPos.getY() + 0.5;

        player.teleportTo(overworld,
                targetPos.getX() + 0.5, safeY, targetPos.getZ() + 0.5,
                Set.of(), player.getYRot(), player.getXRot());
        player.fallDistance = 0;

        LOGGER.debug("[BeLoongCore] {} teleported from loong_palace to overworld spawn ({}, {}, {})",
                player.getName().getString(), targetPos.getX() + 0.5, safeY, targetPos.getZ() + 0.5);
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }
}
