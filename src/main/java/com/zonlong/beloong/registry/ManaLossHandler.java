package com.zonlong.beloong.registry;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import by.dragonsurvivalteam.dragonsurvival.common.handlers.magic.ManaHandler;
import by.dragonsurvivalteam.dragonsurvival.network.syncing.SyncMana;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 处理魔力流逝（mana_loss）效果的每 tick 法力扣除。
 *
 * <p>在 {@link PlayerTickEvent.Post} 中检查玩家是否拥有
 * {@link ModMobEffects#MANA_LOSS} 效果，若有则调用 Dragon Survival 的
 * {@link ManaHandler#consumeMana} 扣除法力。</p>
 *
 * <p>扣除量：{@code 0.025 × (amplifier + 1)} 每 tick</p>
 */
public class ManaLossHandler {

    /** 每级效果等级扣除的法力量（tick） */
    private static final float BASE_DRAIN = 0.025f;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();

        if (player.level().isClientSide()) {
            return;
        }

        if (!player.isAlive()) {
            return;
        }

        if (!DragonStateProvider.isDragon(player)) {
            return;
        }

        MobEffectInstance instance = player.getEffect(ModMobEffects.MANA_LOSS);
        if (instance == null) {
            return;
        }

        float deduction = BASE_DRAIN * (instance.getAmplifier() + 1);
        ManaHandler.consumeMana(player, deduction);

        if (player.tickCount % 20 == 0) {
            BeLoongCore.LOGGER.debug(
                    "[ManaLoss] Player={} amplifier={} deduction={} mana_after={}",
                    player.getName().getString(),
                    instance.getAmplifier(),
                    deduction,
                    MagicData.getData(player).getCurrentMana());
        }

        if (player.tickCount % 5 == 0) {
            PacketDistributor.sendToPlayer((ServerPlayer) player,
                    new SyncMana(MagicData.getData(player).getCurrentMana()));
        }
    }
}
