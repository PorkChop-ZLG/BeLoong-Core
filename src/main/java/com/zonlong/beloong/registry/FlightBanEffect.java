package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.network.flight.SyncWingsSpread;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.FlightData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 禁空效果：每级降低 1 点飞行等级，若有效飞行等级 < 0 则强制收翅。
 * attribute modifier 由 ModMobEffects 注册时添加。
 */
public class FlightBanEffect extends MobEffect {
    public FlightBanEffect() {
        super(MobEffectCategory.HARMFUL, 0x8B0000);
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        if (!entity.level().isClientSide()
                && entity instanceof Player player
                && DragonStateProvider.isDragon(player)) {

            FlightData flightData = FlightData.getData(player);
            if (flightData.areWingsSpread && ModAttributes.getFlightLevel(player) < 0.0) {
                flightData.areWingsSpread = false;
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new SyncWingsSpread(player.getId(), false));
                player.sendSystemMessage(Component.translatable("message.beloong.flight_banned"));
            }
        }
    }
}
