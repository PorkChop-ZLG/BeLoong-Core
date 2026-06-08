package com.zonlong.beloong.registry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
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

    /**
     * 获取玩家的有效飞行等级（已应用所有 attribute modifier）。
     * FLIGHT_LEVEL 由 DSAttributesMixin 注册为 dragonsurvival:flight_level。
     *
     * @return 有效飞行等级，若属性未注册则返回 0
     */
    public static double getFlightLevel(Player player) {
        Attribute attr = BuiltInRegistries.ATTRIBUTE.get(
                ResourceLocation.fromNamespaceAndPath("dragonsurvival", "flight_level"));
        if (attr == null) {
            return 0.0;
        }
        return player.getAttributeValue(BuiltInRegistries.ATTRIBUTE.wrapAsHolder(attr));
    }

    @SubscribeEvent
    public static void attachAttributes(EntityAttributeModificationEvent event) {
        event.add(EntityType.PLAYER, GROWTH_SPEED);
    }
}
