package com.zonlong.beloong.treasure;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.TreasureRestData;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModMobEffects;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundClearTitlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 监听龙玩家在财宝上的休息状态，给予 growth_acceleration 效果并显示原版 title */
@EventBusSubscriber
public class TreasureGrowthHandler {
    /** 按玩家 UUID 记录 tick 计数，用于间隔检查 */
    private static final Map<UUID, Integer> TICK_COUNTERS = new HashMap<>();
    /** 缓存的权重表，配置变更时重建 */
    private static Map<Block, Double> weightMap = Map.of();
    private static long weightMapConfigHash;

    /** 使用 LOW 优先级，确保在 DragonSurvival 的 tick handler 更新 isResting 状态之后执行 */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isAlive()) return;
        if (!Config.TreasureGrowth.enabled.get()) return;

        UUID uuid = player.getUUID();
        int counter = TICK_COUNTERS.getOrDefault(uuid, 0);
        int interval = Config.TreasureGrowth.checkIntervalTicks.get();

        if (counter < interval) {
            TICK_COUNTERS.put(uuid, counter + 1);
            return;
        }
        TICK_COUNTERS.put(uuid, 0);

        if (!DragonStateProvider.isDragon(player)) return;

        TreasureRestData restData = TreasureRestData.getData(player);

        if (restData.isResting()) {
            long currentHash = Config.TreasureGrowth.treasureWeights.get().hashCode();
            if (weightMap.isEmpty() || weightMapConfigHash != currentHash) {
                weightMap = TreasureValueCalculator.buildWeightMap();
                weightMapConfigHash = currentHash;
            }

            double treasureValue = TreasureValueCalculator.calculateWeightedValue(player, weightMap);
            int step = Config.TreasureGrowth.amplifierStep.get();
            int maxAmp = Config.TreasureGrowth.maxAmplifier.get();
            int amplifier = TreasureValueCalculator.valueToAmplifier(treasureValue, step, maxAmp);
            double multiplier = TreasureValueCalculator.amplifierToMultiplier(amplifier);

            int duration = Config.TreasureGrowth.effectDurationTicks.get();
            player.addEffect(new MobEffectInstance(
                    ModMobEffects.GROWTH_ACCELERATION, duration, amplifier,
                    false, true, true
            ));

            // 主标题：成长速度
            String multText = String.format(Locale.ROOT, "%.1fx", multiplier);
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.translatable("title.beloong.growth_multiplier", multText)
            ));
            // 副标题：财宝价值
            String valueText = String.format(Locale.ROOT, "%.1f", treasureValue);
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                    Component.translatable("title.beloong.treasure_value", valueText)
            ));
            // 动画：立即显示，停留 2 秒，快速淡出
            player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 40, 5));
        } else {
            if (player.hasEffect(ModMobEffects.GROWTH_ACCELERATION)) {
                player.removeEffect(ModMobEffects.GROWTH_ACCELERATION);
                // 清除 title
                player.connection.send(new ClientboundClearTitlesPacket(false));
            }
        }
    }

    /** 玩家退出时清理 tick 计数器，防止内存泄漏 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TICK_COUNTERS.remove(event.getEntity().getUUID());
    }
}
