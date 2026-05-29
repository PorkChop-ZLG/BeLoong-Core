package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.common.blocks.TreasureBlock;
import com.zonlong.beloong.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 移除财宝堆重力行为，使其像雪片一样：失去底部支撑时直接破碎且无掉落物，
 * 从根源杜绝刷沙机复制。
 */
@Mixin(TreasureBlock.class)
public abstract class TreasureBlockMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void beloong$cancelFalling(BlockState state, ServerLevel level, BlockPos pos,
            RandomSource random, CallbackInfo ci) {
        if (!Config.FIX_TREASURE_DUPLICATION.get()) {
            return;
        }

        BlockState below = level.getBlockState(pos.below());
        boolean belowEmpty = below.isAir() && pos.getY() >= level.getMinBuildHeight();
        boolean lowerLayer = below.getBlock() == state.getBlock()
                && below.getValue(TreasureBlock.LAYERS) < 8;

        if (belowEmpty || lowerLayer) {
            level.destroyBlock(pos, false); // 无掉落物
        }
        ci.cancel();
    }
}
