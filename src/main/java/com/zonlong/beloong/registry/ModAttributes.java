package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@EventBusSubscriber(modid = "beloong", bus = EventBusSubscriber.Bus.MOD)
public class ModAttributes {
    public static final DeferredRegister<Attribute> REGISTRY =
            DeferredRegister.create(Registries.ATTRIBUTE, "beloong");

    public static final Holder<Attribute> GROWTH_SPEED = REGISTRY.register("growth_speed",
            () -> new RangedAttribute(
                    "attribute.beloong.growth_speed",
                    1.0,
                    -1024.0,
                    1024.0
            ).setSyncable(true)
    );

    @SubscribeEvent
    public static void attachAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, GROWTH_SPEED);
    }
}
