package com.zonlong.beloong.mixin.dragonsurvival;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.MiscCodecs;
import by.dragonsurvivalteam.dragonsurvival.server.handlers.DragonDestructionHandler;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为龙之生存的方块破坏行为添加 FTB Chunks 领地保护兼容。
 *
 * <p>拦截 {@link by.dragonsurvivalteam.dragonsurvival.server.handlers.DragonDestructionHandler} 中的
 * 连锁挖掘（multi-mining）和大型龙碰撞破坏（trample）的底层 {@code destroyBlock}/{@code removeBlock} 调用，
 * 在执行前检查 FTB Chunks 的领地归属，阻止越权破坏。</p>
 *
 * <p>若 FTB Chunks 未安装或配置开关关闭，此 Mixin 不做任何拦截。</p>
 *
 * <p>目标 Dragon Survival 版本：2.0.53+</p>
 */
@Mixin(DragonDestructionHandler.class)
public abstract class DragonDestructionHandlerMixin {

    // ========== 连锁挖掘 ==========

    // remap = false: Dragon Survival 方法名不使用 Mojang 映射
    // target 中的 Minecraft 类名（ServerPlayerGameMode.destroyBlock）默认 remap = true，无需显式设置

    @Inject(
            method = "lambda$destroyBlocksInRadius$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeMultiMiningDestroyBlock(BlockEvent.BreakEvent event, float centerSpeed, ServerPlayer player, BlockPos pos, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(player, pos)) {
            ci.cancel();
        }
    }

    // ========== 大型龙碰撞破坏 ==========

    @Inject(
            method = "lambda$checkAndDestroyCollidingBlocks$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeTrampleDestroyBlock(MiscCodecs.DestructionData destructionData, PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(event.getEntity(), pos)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "lambda$checkAndDestroyCollidingBlocks$0",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeTrampleRemoveBlock(MiscCodecs.DestructionData destructionData, PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (ClaimProtectionHelper.isClaimed(event.getEntity(), pos)) {
            ci.cancel();
        }
    }
}
