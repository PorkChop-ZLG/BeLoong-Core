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

    public EternalPorkchopEffect() {
        super(new Properties()
                .food(COOKED_PORKCHOP_FOOD)
                // 不设置耐久度：物品永远不会消失！
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
        // 1. 应用食物效果（仅一次）
        if (entity instanceof Player player) {
            player.getFoodData().eat(
                    COOKED_PORKCHOP_FOOD.nutrition(),
                    COOKED_PORKCHOP_FOOD.saturation()
            );
        }

        // 2. 设置冷却（游戏平衡）
        if (entity instanceof Player player) {
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        }

        // ✅ 直接返回原物品，永远不消耗！
        // 3. 伤害耐久，如果耐久耗尽物品会自动消失
        InteractionHand hand = entity.getUsedItemHand();
        EquipmentSlot slot = hand == InteractionHand.MAIN_HAND ?
                EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
        stack.hurtAndBreak(DURABILITY_DAMAGE, entity, slot);

        // 4. 直接返回，物品数量不变（不增加也不减少）
        return stack;
    }
}