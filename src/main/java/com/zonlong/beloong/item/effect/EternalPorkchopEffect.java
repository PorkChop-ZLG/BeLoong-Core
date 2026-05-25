package com.zonlong.beloong.item.effect;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public class EternalPorkchopEffect extends Item {

    private static final FoodProperties COOKED_PORKCHOP_FOOD = new FoodProperties.Builder()
            .nutrition(8)
            .saturationModifier(0.8f)
            .build();

    private static final int EAT_DURATION_TICKS = 32;
    private static final int COOLDOWN_TICKS = 600;

    public EternalPorkchopEffect() {
        super(new Properties().food(COOKED_PORKCHOP_FOOD));
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return EAT_DURATION_TICKS;
    }

    @Override
    public SoundEvent getEatingSound() {
        return SoundEvents.GENERIC_EAT;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return stack;
        }

        if (player.getCooldowns().isOnCooldown(this)) {
            return stack;
        }

        player.getFoodData().eat(
                COOKED_PORKCHOP_FOOD.nutrition(),
                COOKED_PORKCHOP_FOOD.saturation()
        );

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_BURP, SoundSource.PLAYERS,
                0.5F, level.random.nextFloat() * 0.1F + 0.9F);

        player.awardStat(Stats.ITEM_USED.get(this));

        if (player instanceof ServerPlayer serverPlayer) {
            CriteriaTriggers.CONSUME_ITEM.trigger(serverPlayer, stack);
        }

        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);

        return stack;
    }
}