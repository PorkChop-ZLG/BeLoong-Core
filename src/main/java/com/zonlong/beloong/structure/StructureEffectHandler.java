package com.zonlong.beloong.structure;

import com.zonlong.beloong.Config;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
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

/**
 * 结构药水效果处理器。
 * <p>
 * 当玩家进入已配置的结构区域时自动施加药水效果，
 * 通过区块变化检测、效果过期、维度切换、登录和重生事件触发重检。
 * </p>
 */
public class StructureEffectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StructureEffectHandler.class);

    private Map<ResourceKey<Structure>, List<EffectEntry>> configMap = Map.of();
    private Set<ResourceKey<MobEffect>> watchedEffects = Set.of();
    private int lastConfigHash;
    private final Map<UUID, ChunkPos> playerLastChunk = new HashMap<>();
    private boolean refreshing;

    /**
     * 刷新并解析配置文件中的结构效果配置。
     * 仅在配置内容发生变更时重新解析，使用哈希值做快速比较。
     */
    private void refreshConfig() {
        int currentHash = Config.StructureEffects.entries.get().hashCode()
                ^ Config.StructureEffects.watchedEffects.get().hashCode();
        if (currentHash == lastConfigHash) return;

        Map<ResourceKey<Structure>, List<EffectEntry>> newConfigMap = new HashMap<>();
        Set<ResourceKey<MobEffect>> newWatchedEffects = new HashSet<>();

        for (String entry : Config.StructureEffects.entries.get()) {
            String[] parts = entry.split("\\|");
            if (parts.length != 4) {
                LOGGER.warn("[BeLoong] structure_effects: invalid entry format (expected 4 fields): {}", entry);
                continue;
            }

            ResourceLocation structureLoc;
            try {
                structureLoc = ResourceLocation.parse(parts[0].trim());
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid structure ID: {}", parts[0].trim());
                continue;
            }
            ResourceKey<Structure> structureKey = ResourceKey.create(Registries.STRUCTURE, structureLoc);

            ResourceLocation effectLoc;
            try {
                effectLoc = ResourceLocation.parse(parts[1].trim());
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid effect ID: {}", parts[1].trim());
                continue;
            }
            ResourceKey<MobEffect> effectKey = ResourceKey.create(Registries.MOB_EFFECT, effectLoc);

            int amplifier;
            int duration;
            try {
                amplifier = Integer.parseInt(parts[2].trim());
                duration = Integer.parseInt(parts[3].trim());
            } catch (NumberFormatException e) {
                LOGGER.warn("[BeLoong] structure_effects: failed to parse amplifier/duration: {}", entry);
                continue;
            }

            Holder<MobEffect> effectHolder = BuiltInRegistries.MOB_EFFECT
                    .getHolder(effectKey).orElse(null);
            if (effectHolder == null) {
                LOGGER.warn("[BeLoong] structure_effects: mob effect not found: {}", parts[1].trim());
                continue;
            }

            newConfigMap.computeIfAbsent(structureKey, k -> new ArrayList<>())
                    .add(new EffectEntry(effectHolder, amplifier, duration));
        }

        for (String effectId : Config.StructureEffects.watchedEffects.get()) {
            ResourceLocation watchedEffectLoc;
            try {
                watchedEffectLoc = ResourceLocation.parse(effectId.trim());
            } catch (Exception e) {
                LOGGER.warn("[BeLoong] structure_effects: invalid watched effect ID: {}", effectId.trim());
                continue;
            }
            ResourceKey<MobEffect> key = ResourceKey.create(Registries.MOB_EFFECT, watchedEffectLoc);
            newWatchedEffects.add(key);
        }

        this.configMap = Collections.unmodifiableMap(newConfigMap);
        this.watchedEffects = Collections.unmodifiableSet(newWatchedEffects);
        this.lastConfigHash = currentHash;
        this.playerLastChunk.clear();
        LOGGER.debug("[BeLoong] structure_effects config loaded: {} structures, {} watched effects",
                configMap.size(), watchedEffects.size());
    }

    /**
     * 检测玩家当前位置是否处于配置的结构区域内，
     * 若是则施加对应的药水效果。
     */
    private void checkAndApply(ServerPlayer player) {
        if (refreshing) return;
        refreshing = true;
        try {
            doCheckAndApply(player);
        } finally {
            refreshing = false;
        }
    }

    private void doCheckAndApply(ServerPlayer player) {
        refreshConfig();
        if (configMap.isEmpty()) return;

        var structureManager = player.serverLevel().structureManager();
        var structureRegistry = player.serverLevel().registryAccess().registryOrThrow(Registries.STRUCTURE);

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
            if (player.getBoundingBox().intersects(aabb)) {
                for (EffectEntry ee : effects) {
                    player.addEffect(new MobEffectInstance(
                            ee.effect(), ee.durationTicks(), ee.amplifier(),
                            false, true, true
                    ));
                }
            }
        }
    }

    /**
     * 服务端 tick 末尾：仅检测区块发生变化的玩家，
     * 通过 {@link #playerLastChunk} 做轻量级过滤。
     */
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

    /**
     * 当被监视的药水效果过期时，触发结构重检。
     */
    @SubscribeEvent
    public void onEffectExpired(MobEffectEvent.Expired event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            checkAndApply(player);
        }
    }

    /**
     * 当被监视的药水效果被移除时触发结构重检。
     * 覆盖自然过期（Expired 事件之后的实际移除）、喝牛奶、
     * /effect clear 等所有移除场景。重入保护由 {@link #refreshing} 标记提供。
     */
    @SubscribeEvent
    public void onEffectRemoved(MobEffectEvent.Remove event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ResourceKey<MobEffect> effectKey = event.getEffectInstance().getEffect().getKey();
        if (effectKey != null && watchedEffects.contains(effectKey)) {
            checkAndApply(player);
        }
    }

    /**
     * 维度切换时清除缓存并检测新维度的结构。
     */
    @SubscribeEvent
    public void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    /**
     * 玩家登录时清除缓存并检测当前位置的结构。
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    /**
     * 玩家重生时清除缓存并检测重生位置的结构。
     */
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        playerLastChunk.remove(player.getUUID());
        checkAndApply(player);
    }

    /**
     * 玩家登出时清理追踪数据，防止内存泄漏。
     */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        playerLastChunk.remove(event.getEntity().getUUID());
    }
}
