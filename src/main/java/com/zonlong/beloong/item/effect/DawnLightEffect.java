package com.zonlong.beloong.item.effect;

import com.finderfeed.fdbosses.content.entities.chesed_boss.ChesedEntity;
import com.finderfeed.fdbosses.content.entities.geburah.GeburahEntity;
import com.finderfeed.fdbosses.content.entities.malkuth_boss.MalkuthEntity;
import com.finderfeed.fdlib.FDLibCalls;
import com.finderfeed.fdlib.init.FDScreenEffects;
import com.finderfeed.fdlib.systems.screen.screen_effect.instances.datas.ScreenColorData;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.mixin.fdbosses.MalkuthEntityAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class DawnLightEffect extends Item {

    private static final int SCAN_RADIUS = 32;
    private static final int COOLDOWN_TICKS = 200;
    private static final int SCREEN_IN_TIME = 5;
    private static final int SCREEN_STAY_TIME = 0;
    private static final int SCREEN_OUT_TIME = 5;

    public DawnLightEffect() {
        super(new Item.Properties()
                .stacksTo(16)
                .rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        BeLoongCore.LOGGER.info("[DawnLight] Used by player {} at {}", player.getName().getString(), player.blockPosition());

        List<Object> bosses = new ArrayList<>();
        bosses.addAll(level.getEntitiesOfClass(
                ChesedEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));
        bosses.addAll(level.getEntitiesOfClass(
                MalkuthEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));
        bosses.addAll(level.getEntitiesOfClass(
                GeburahEntity.class,
                new AABB(player.blockPosition()).inflate(SCAN_RADIUS)));

        BeLoongCore.LOGGER.info("[DawnLight] Scan result: {} boss(es) found (radius={})", bosses.size(), SCAN_RADIUS);

        if (bosses.isEmpty()) {
            BeLoongCore.LOGGER.info("[DawnLight] No boss found — sending fail message, item not consumed");
            player.displayClientMessage(
                    Component.translatable("item.beloong.dawn_light.no_boss"),
                    true);
            return InteractionResultHolder.fail(stack);
        }

        stack.consume(1, player);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        BeLoongCore.LOGGER.info("[DawnLight] Item consumed, cooldown set to {} ticks", COOLDOWN_TICKS);

        for (Object boss : bosses) {
            if (boss instanceof ChesedEntity chesed) {
                int before = chesed.getRemainingHits();
                chesed.decreaseHitCount(1);
                int after = chesed.getRemainingHits();
                BeLoongCore.LOGGER.info("[DawnLight] Chesed: hits {} -> {}", before, after);
            } else if (boss instanceof MalkuthEntity malkuth) {
                int before = malkuth.getCurrentHits();
                int newHits = Math.max(0, before - 1);
                ((MalkuthEntityAccessor) malkuth).setHits(newHits);
                if (newHits == 0) {
                    malkuth.kill();
                    BeLoongCore.LOGGER.info("[DawnLight] Malkuth: hits {} -> 0 — killed", before);
                } else {
                    int after = malkuth.getCurrentHits();
                    BeLoongCore.LOGGER.info("[DawnLight] Malkuth: hits {} -> {} (bypass allowedToBeDamaged)", before, after);
                }
            } else if (boss instanceof GeburahEntity geburah) {
                int before = geburah.getSinnedTimes();
                geburah.setSinnedTimes(geburah.getSinnedTimes() + 1);
                int after = geburah.getSinnedTimes();
                BeLoongCore.LOGGER.info("[DawnLight] Geburah: sins {} -> {}", before, after);
            }
        }

        if (player instanceof ServerPlayer serverPlayer) {
            BeLoongCore.LOGGER.info("[DawnLight] Sending white screen effect to {}", serverPlayer.getName().getString());
            FDLibCalls.sendScreenEffect(
                    serverPlayer,
                    FDScreenEffects.SCREEN_COLOR,
                    new ScreenColorData(1f, 1f, 1f, 1f),
                    SCREEN_IN_TIME,
                    SCREEN_STAY_TIME,
                    SCREEN_OUT_TIME
            );
        }

        BeLoongCore.LOGGER.info("[DawnLight] Playing totem sound");
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1f, 1f);

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(
                Component.translatable("item.beloong.dawn_light.tooltip")
                        .withStyle(ChatFormatting.GRAY));
    }
}
