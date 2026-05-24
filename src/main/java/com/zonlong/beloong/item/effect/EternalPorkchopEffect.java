package com.zonlong.beloong.item.effect;

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
            .saturationModifier(0.8F)
            .build();

    private static final int COOLDOWN_TICKS = 30 * 20;

    public EternalPorkchopEffect() {
        super(new Properties()
                .food(COOKED_PORKCHOP_FOOD)
                .stacksTo(1));
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return COOKED_PORKCHOP_FOOD.eatDurationTicks();
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        /*
         * Run vanilla food handling on a temporary one-item copy. The real stack is
         * an infinite-use item, so it must never be consumed or damaged here.
         */
        super.finishUsingItem(stack.copyWithCount(1), level, entity);

        if (!level.isClientSide && entity instanceof Player player) {
            repairInvalidStackSize(stack);
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }

        return stack;
    }

    private static void repairInvalidStackSize(ItemStack stack) {
        // Older builds could create stacked durable porkchops; keep the artifact singular.
        if (stack.getCount() > 1) {
            stack.setCount(1);
        }
    }
}
