package com.zonlong.beloong.treasure;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.TreasureRestData;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 监听龙玩家在财宝上的休息状态，给予 growth_acceleration 效果并在 actionbar 显示信息 */
@EventBusSubscriber
public class TreasureGrowthHandler {
    /** 按玩家 UUID 记录 tick 计数，用于间隔检查 */
    private static final Map<UUID, Integer> TICK_COUNTERS = new HashMap<>();

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
            double treasureValue = TreasureValueCalculator.calculateWeightedValue(player);
            // 财宝值封顶
            int maxValue = Config.TreasureGrowth.maxTreasureValue.get();
            treasureValue = Math.min(treasureValue, maxValue);

            int step = Config.TreasureGrowth.amplifierStep.get();
            int maxAmp = Config.TreasureGrowth.maxAmplifier.get();
            int amplifier = TreasureValueCalculator.valueToAmplifier(treasureValue, step, maxAmp);
            double multiplier = TreasureValueCalculator.amplifierToMultiplier(amplifier);

            int duration = Config.TreasureGrowth.effectDurationTicks.get();
            player.addEffect(new MobEffectInstance(
                    ModMobEffects.GROWTH_ACCELERATION, duration, amplifier,
                    false, true, true
            ));

            // actionbar：金色财宝值 + 绿色成长速度
            String valueText = String.format(Locale.ROOT, "%.1f", treasureValue);
            String multText = String.format(Locale.ROOT, "%.1fx", multiplier);
            Component message = Component.literal("")
                    .append(Component.translatable("title.beloong.treasure_value", valueText).withStyle(ChatFormatting.GOLD))
                    .append(Component.literal("  "))
                    .append(Component.translatable("title.beloong.growth_multiplier", multText).withStyle(ChatFormatting.GREEN));
            player.displayClientMessage(message, true);
        }
        // 离开休息后不做清理，effect 和 actionbar 自然消失
    }

    /** 玩家退出时清理 tick 计数器，防止内存泄漏 */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TICK_COUNTERS.remove(event.getEntity().getUUID());
    }
}
