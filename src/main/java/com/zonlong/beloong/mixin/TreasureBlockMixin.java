package com.zonlong.beloong.mixin;

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
 * 取消财宝堆的 FallingBlockEntity 创建逻辑，使其像雪片一样不再下落，
 * 从根源上杜绝刷沙机复制。
 */
@Mixin(targets = "by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock", remap = false)
public abstract class TreasureBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void beloong$cancelFalling(BlockState state, ServerLevel level, BlockPos blockPos,
            RandomSource randomSource, CallbackInfo ci) {
        if (Config.FIX_TREASURE_DUPLICATION.get()) {
            ci.cancel();
        }
    }
}
