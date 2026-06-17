package com.zonlong.beloong.structure;

import com.zonlong.beloong.Config;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class StructureEffectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructureEffectHandler.class);

    private Set<ResourceKey<MobEffect>> watchedEffects = Set.of();
    private int lastWatchedHash;
    private final Map<UUID, ChunkPos> playerLastChunk = new HashMap<>();
    private boolean refreshing;

    private void refreshWatchedEffects() {
        int currentHash = Config.StructureEffects.watchedEffects.get().hashCode();
        if (currentHash == lastWatchedHash) return;

        Set<ResourceKey<MobEffect>> newWatched = new HashSet<>();
        for (String effectId : Config.StructureEffects.watchedEffects.get()) {
            try {
                ResourceLocation loc = ResourceLocation.parse(effectId.trim());
                newWatched.add(ResourceKey.create(Registries.MOB_EFFECT, loc));
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid watched effect ID: {}", effectId.trim());
            }
        }
        this.watchedEffects = Collections.unmodifiableSet(newWatched);
        this.lastWatchedHash = currentHash;
    }

    private boolean checkAndApply(ServerPlayer player) {
        if (refreshing) return false;
        refreshing = true;
        try {
            return doCheckAndApply(player);
        } finally {
            refreshing = false;
        }
    }

    private boolean doCheckAndApply(ServerPlayer player) {
        var configMap = StructureEffectLoader.INSTANCE.getConfigMap();
        if (configMap.isEmpty()) return false;

        var structureManager = player.serverLevel().structureManager();
        var structureRegistry = player.serverLevel().registryAccess()
                .registryOrThrow(Registries.STRUCTURE);
        boolean applied = false;

        for (var configEntry : configMap.entrySet()) {
            ResourceKey<Structure> structureKey = configEntry.getKey();
            List<EffectEntry> effects = configEntry.getValue();

            Structure structure = structureRegistry.get(structureKey);
            if (structure == null) continue;

            StructureStart start = structureManager.getStructureAt(player.blockPosition(), structure);
            if (start == null || !start.isValid()) continue;

            BoundingBox bb = start.getBoundingBox();
            AABB aabb = new AABB(bb.minX(), bb.minY(), bb.minZ(),
                    bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
            if (!player.getBoundingBox().intersects(aabb)) continue;

            for (EffectEntry ee : effects) {
                if (ee.advancement().isPresent()) {
                    var progress = player.getAdvancements()
                            .getOrStartProgress(ee.advancement().get());
                    if (progress.isDone()) continue;
                }

                if (player.addEffect(new MobEffectInstance(
                        ee.effect(), ee.durationTicks(), ee.amplifier(),
                        false, ee.showParticles(), true
                ))) {
                    applied = true;
                }
            }
        }
        return applied;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ChunkPos currentChunk = player.chunkPosition();
            ChunkPos lastChunk = playerLastChunk.get(player.getUUID());

            if (lastChunk == null || !currentChunk.equals(lastChunk)) {
                playerLastChunk.put(player.getUUID(), currentChunk);
                checkAndApply(player);
            }
        }
    }

    @SubscribeEvent
    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            refreshWatchedEffects();
            if (checkAndApply(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            refreshWatchedEffects();
            if (checkAndApply(player)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLastChunk.remove(event.getEntity().getUUID());
    }
}
