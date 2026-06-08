package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMobEffects {
    public static final DeferredRegister<MobEffect> REGISTRY =
            DeferredRegister.create(Registries.MOB_EFFECT, "beloong");

    public static final Holder<MobEffect> GROWTH_ACCELERATION = REGISTRY.register(
            "growth_acceleration",
            () -> new MobEffect(MobEffectCategory.BENEFICIAL, 0xFFD700) { }
                    .addAttributeModifier(
                            ModAttributes.GROWTH_SPEED,
                            ResourceLocation.fromNamespaceAndPath("beloong", "growth_acceleration"),
                            1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    )
    );

    /**
     * 禁空效果：每级降低 1 点飞行等级。<br>
     * 若有效飞行等级 &lt; 0 则调用 {@link FlightBanEffect#onEffectStarted} 强制收翅。
     * 原版自动按 (amplifier + 1) 缩放，amount = -1 时：
     *   I 级 (amp 0) → -1 × 1 = -1
     *   II 级 (amp 1) → -1 × 2 = -2
     */
    public static final Holder<MobEffect> FLIGHT_BAN = REGISTRY.register(
            "flight_ban",
            () -> {
                Attribute flightLevelAttr = BuiltInRegistries.ATTRIBUTE.get(
                        ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
                FlightBanEffect effect = new FlightBanEffect();
                if (flightLevelAttr != null) {
                    effect.addAttributeModifier(
                            BuiltInRegistries.ATTRIBUTE.wrapAsHolder(flightLevelAttr),
                            ResourceLocation.fromNamespaceAndPath("beloong", "flight_ban"),
                            -1.0,
                            AttributeModifier.Operation.ADD_VALUE
                    );
                }
                return effect;
            }
    );
}
