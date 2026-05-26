package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.block_effects.BlockConversionEffect;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * FTB Chunks 兼容：拦截 {@code BlockConversionEffect.apply()}，阻止在已认领区块内转换方块。
 */
@Mixin(BlockConversionEffect.class)
public abstract class BlockConversionEffectMixin {

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$beforeApply(ServerPlayer dragon, DragonAbilityInstance ability,
            BlockPos position, Direction direction, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(dragon, position)) {
            ci.cancel();
        }
    }
}
