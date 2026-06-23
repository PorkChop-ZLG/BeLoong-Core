package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;

/**
 * 气定神闲效果——提升法力回复速度。
 *
 * <p>每级 +0.001 到 {@code dragonsurvival:mana_regeneration} 属性（ADD_VALUE）。</p>
 */
public class SereneEffect extends MobEffect {

    public SereneEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x87CEEB);
    }

    @Override
    public void onEffectStarted(LivingEntity entity, int amplifier) {
        if (!entity.level().isClientSide() && entity instanceof Player player) {
            Attribute attr = BuiltInRegistries.ATTRIBUTE.get(
                    ResourceLocation.fromNamespaceAndPath("dragonsurvival", "mana_regeneration"));
            if (attr != null) {
                double currentRegen = player.getAttributeValue(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attr));
                BeLoongCore.LOGGER.debug(
                        "[Serene] Applied to {} (amplifier={}), mana_regeneration now: {}",
                        player.getName().getString(), amplifier, currentRegen);
            } else {
                BeLoongCore.LOGGER.debug(
                        "[Serene] Applied to {} (amplifier={}), but mana_regeneration attribute not found",
                        player.getName().getString(), amplifier);
            }
        }
    }
}
