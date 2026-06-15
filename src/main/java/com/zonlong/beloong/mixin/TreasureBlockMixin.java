package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除财宝堆重力行为，阻止其下落，从根源杜绝刷沙机复制。
 */
@Mixin(TreasureBlock.class)
public abstract class TreasureBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$cancelFalling(BlockState state, ServerLevel level, BlockPos pos,
            RandomSource random, CallbackInfo ci) {
        if (!Config.FIX_TREASURE_DUPLICATION.get()) {
            return;
        }
        ci.cancel();
    }
}
