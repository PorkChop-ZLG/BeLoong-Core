package com.zonlong.beloong.item.effect;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public class EternalPorkchopEffect extends Item {

    // 直接使用原版猪排的饥饿值与饱和度
    private static final FoodProperties COOKED_PORKCHOP_FOOD = new FoodProperties.Builder()
            .nutrition(8)          // 饥饿值
            .saturationModifier(0.8f) // 饱和度系数 (12.8 = 0.8 * 2 * 8)
            .build();

    private static final int EAT_DURATION_TICKS = 32; // 1.6 秒
    private static final int COOLDOWN_TICKS = 600;    // 30 秒
    private static final int DURABILITY_DAMAGE = 1;

    public EternalPorkchopEffect() {
        super(new Properties()
                .food(COOKED_PORKCHOP_FOOD)
                .durability(256)  // 可食用 256 次
        );
    }

    // 食用动画
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.EAT;
    }

    // 食用时长
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return EAT_DURATION_TICKS;
    }

    // 食用音效
    @Override
    public SoundEvent getEatingSound() {
        return SoundEvents.GENERIC_EAT;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        // 1. 应用食物效果（恢复饥饿值/饱和度）
        if (entity instanceof Player player) {
            player.getFoodData().eat(
                    COOKED_PORKCHOP_FOOD.nutrition(),
                    COOKED_PORKCHOP_FOOD.saturation()
            );
        }

        // 2. 设置冷却
        if (entity instanceof Player player) {
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }

        // 3. 伤害耐久（同时通过 hurtAndBreak 的最后一击自动破坏物品）
        InteractionHand hand = entity.getUsedItemHand();
        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ?
                EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(DURABILITY_DAMAGE, entity, slot);

        // 4. 防止物品数量被扣除 —— 无限消耗
        //    当 hurtAndBreak 导致物品损坏时，物品本身就会消失，无需额外处理；
        //    未损坏时，我们将数量加回来，抵消父类默认的减一操作。
        if (!stack.isEmpty()) {
            stack.setCount(stack.getCount() + 1);
        }

        return stack; // 返回处理后的物品栈，此时数量永远为 1（未坏）或 0（已坏）
    }
}