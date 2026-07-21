package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.handlers.DragonGrowthHandler;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.stage.DragonStage;
import com.zonlong.beloong.registry.ModAttributes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DragonGrowthHandler.class)
public class MixinDragonGrowthHandler {

    @Redirect(
            method = "onPlayerUpdate",
            at = @At(
                    value = "INVOKE",
                    target = "Lby/dragonsurvivalteam/dragonsurvival/registry/dragon/stage/DragonStage;ticksToGrowth(I)D"
            ),
            remap = false
    )
    private static double redirectTicksToGrowth(
            DragonStage stage,
            int ticks,
            PlayerTickEvent.Pre event
    ) {
        double baseGrowth = stage.ticksToGrowth(ticks);

        if (event.getEntity() instanceof ServerPlayer player) {
            AttributeInstance attr = player.getAttribute(ModAttributes.GROWTH_SPEED);
            if (attr != null) {
                return baseGrowth * attr.getValue();
            }
        }

        return baseGrowth;
    }
}
