package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = BeLoongCore.MODID, bus = EventBusSubscriber.Bus.MOD)
public class AbilityEffectRegistry {

    private AbilityEffectRegistry() {}

    @SubscribeEvent
    static void registerEntityEffects(final RegisterEvent event) {
        if (event.getRegistry() == AbilityEntityEffect.REGISTRY) {
            event.register(AbilityEntityEffect.REGISTRY_KEY,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "tp_loong_palace"),
                    () -> TpLoongPalaceEffect.CODEC);
            event.register(AbilityEntityEffect.REGISTRY_KEY,
                    ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "air_strike"),
                    () -> AirStrikeEffect.CODEC);
        }
    }
}
