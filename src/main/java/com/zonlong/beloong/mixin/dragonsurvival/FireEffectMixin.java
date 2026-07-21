package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.FireEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FTB Chunks 兼容：拦截 {@code FireEffect.apply()}，阻止在已认领区块内点燃方块。
 */
@Mixin(FireEffect.class)
public abstract class FireEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
