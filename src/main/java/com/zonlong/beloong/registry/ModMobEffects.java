package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
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
}
