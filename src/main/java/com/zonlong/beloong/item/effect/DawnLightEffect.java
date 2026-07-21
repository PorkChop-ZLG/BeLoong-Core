package com.zonlong.beloong.item.effect;

import com.finderfeed.fdbosses.content.entities.chesed_boss.ChesedEntity;
import com.finderfeed.fdbosses.content.entities.geburah.GeburahEntity;
import com.finderfeed.fdbosses.content.entities.malkuth_boss.MalkuthEntity;
import com.finderfeed.fdlib.FDLibCalls;
import com.finderfeed.fdlib.init.FDScreenEffects;
import com.finderfeed.fdlib.systems.screen.screen_effect.instances.datas.ScreenColorData;
import com.zonlong.beloong.mixin.fdbosses.MalkuthEntityAccessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EquipmentSlot;
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
    private static final int COOLDOWN_TICKS = 1200;
    private static final int SCREEN_IN_TIME = 5;
    private static final int SCREEN_STAY_TIME = 0;
    private static final int SCREEN_OUT_TIME = 5;

    public DawnLightEffect() {
        super(new Item.Properties()
                .stacksTo(1)
                .durability(10)
                .rarity(Rarity.EPIC));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

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

        if (bosses.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("item.beloong.dawn_light.no_boss"),
                    true);
            return InteractionResultHolder.fail(stack);
        }

        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND
                ? EquipmentSlot.MAINHAND
                : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(1, player, slot);
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        for (Object boss : bosses) {
            if (boss instanceof ChesedEntity chesed) {
                chesed.decreaseHitCount(1);
            } else if (boss instanceof MalkuthEntity malkuth) {
                int newHits = Math.max(0, malkuth.getCurrentHits() - 1);
                ((MalkuthEntityAccessor) malkuth).setHits(newHits);
                if (newHits == 0) {
                    malkuth.kill();
                }
            } else if (boss instanceof GeburahEntity geburah) {
                geburah.setSinnedTimes(geburah.getSinnedTimes() + 1);
            }
        }

        if (player instanceof ServerPlayer serverPlayer) {
            FDLibCalls.sendScreenEffect(
                    serverPlayer,
                    FDScreenEffects.SCREEN_COLOR,
                    new ScreenColorData(1f, 1f, 1f, 1f),
                    SCREEN_IN_TIME,
                    SCREEN_STAY_TIME,
                    SCREEN_OUT_TIME
            );
        }

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
