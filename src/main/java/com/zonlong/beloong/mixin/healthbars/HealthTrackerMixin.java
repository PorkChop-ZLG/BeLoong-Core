package com.zonlong.beloong.mixin.healthbars;

import by.dragonsurvivalteam.dragonsurvival.common.capability.DragonStateProvider;
import com.zonlong.beloong.client.health.GrowthHealthDeltaTracker;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves Health Bars' health values and bar animation untouched; filters floating numbers only. */
@Pseudo
@Mixin(targets = "fuzs.healthbars.client.helper.HealthTracker", remap = false)
public abstract class HealthTrackerMixin {
    @Shadow private float lastHealthDelta;
    @Unique private final GrowthHealthDeltaTracker beloong$growthHealth = new GrowthHealthDeltaTracker();
    @Unique private LivingEntity beloong$trackedEntity;
    @Unique private Level beloong$trackedLevel;

    @Inject(method = "tick(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("TAIL"), remap = false)
    private void beloong$excludeGrowthFromHealing(LivingEntity entity, CallbackInfo ci) {
        if (!entity.level().isClientSide() || !(entity instanceof Player player)
                || !DragonStateProvider.isDragon(player)) {
            beloong$growthHealth.reset();
            beloong$trackedEntity = null;
            beloong$trackedLevel = null;
            return;
        }
        if (beloong$trackedEntity != entity || beloong$trackedLevel != entity.level()) {
            beloong$growthHealth.reset();
            beloong$trackedEntity = entity;
            beloong$trackedLevel = entity.level();
        }
        lastHealthDelta = beloong$growthHealth.sample(entity.tickCount, entity.getHealth(), entity.getMaxHealth(),
                DragonStateProvider.getData(player).getGrowth());
    }
}
