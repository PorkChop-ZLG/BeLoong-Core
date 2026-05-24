package com.zonlong.beloong.item.custom;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public class EternalPorkchopItem extends Item {
    private static final int MAX_USES = 256;
    private static final int COOLDOWN_TICKS = 30 * 20;
    private static final int DURABILITY_DAMAGE_PER_USE = 1;

    private static final FoodProperties FOOD_PROPERTIES = new FoodProperties.Builder()
            .nutrition(8)
            .saturationModifier(0.8F)
            .build();

    public EternalPorkchopItem() {
        super(new Properties()
                .food(FOOD_PROPERTIES)
                .durability(MAX_USES));
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return FOOD_PROPERTIES.eatDurationTicks();
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        /*
         * Let vanilla handle the food effects, sound, and game event on a one-item
         * copy. The real stack is never consumed as food; it only loses durability.
         */
        super.finishUsingItem(stack.copyWithCount(1), level, entity);

        if (!level.isClientSide) {
            normalizeStackSize(stack);
            applyCooldown(entity);
            damageHeldStack(stack, entity);
        }

        return stack;
    }

    private static void normalizeStackSize(ItemStack stack) {
        // Older builds could create stacked durable porkchops; keep future uses legal.
        if (stack.getCount() > 1) {
            stack.setCount(1);
        }
    }

    private void applyCooldown(LivingEntity entity) {
        if (entity instanceof Player player) {
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }
    }

    private static void damageHeldStack(ItemStack stack, LivingEntity entity) {
        EquipmentSlot slot = LivingEntity.getSlotForHand(entity.getUsedItemHand());
        stack.hurtAndBreak(DURABILITY_DAMAGE_PER_USE, entity, slot);
    }
}
