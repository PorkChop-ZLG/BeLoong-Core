package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.DSAttributes;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DSAttributes.class, remap = false)
public abstract class DSAttributesMixin {

    @Unique
    private static Holder<Attribute> beloong$FLIGHT_LEVEL;

    /** 在 DSAttributes 静态初始化末尾注册 FLIGHT_LEVEL */
    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void registerFlightLevel(CallbackInfo ci) {
        beloong$FLIGHT_LEVEL = DSAttributes.REGISTRY.register(
                "flight_level",
                () -> new RangedAttribute(
                        "dragonsurvival.flight_level",
                        0.0,
                        -1024.0,
                        1024.0
                ).setSyncable(true)
        );
    }

    /** 在 attachAttributes 尾部将 FLIGHT_LEVEL attach 到 PLAYER */
    @Inject(method = "attachAttributes", at = @At("TAIL"))
    private static void attachFlightLevel(EntityAttributeModificationEvent event, CallbackInfo ci) {
        event.add(EntityType.PLAYER, beloong$FLIGHT_LEVEL);
    }
}
