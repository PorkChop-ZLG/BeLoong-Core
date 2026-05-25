package com.zonlong.beloong.mixin;

import by.dragonsurvivalteam.dragonsurvival.server.handlers.DragonDestructionHandler;
import com.zonlong.beloong.Config;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DragonDestructionHandler.class)
public abstract class DragonDestructionHandlerMixin {

    private static boolean isProtected(Entity actor, BlockPos pos) {
        if (!ModList.get().isLoaded("ftbchunks")) {
            return false;
        }

        if (!Config.FIX_FTB_CHUNKS_COMPAT.get()) {
            return false;
        }

        return FTBChunksAPI.api().getManager()
                .shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, Protection.EDIT_BLOCK, null);
    }

    // ========== 连锁挖掘 ==========

    @Inject(
            method = "lambda$destroyBlocksInRadius$1",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayerGameMode;destroyBlock(Lnet/minecraft/core/BlockPos;)Z"
            ),
            cancellable = true,
            remap = false
    )
    private static void beforeMultiMiningDestroyBlock(ServerPlayer player, BlockPos pos, CallbackInfo ci) {
        if (isProtected(player, pos)) {
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
    private static void beforeTrampleDestroyBlock(PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (isProtected(event.getEntity(), pos)) {
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
    private static void beforeTrampleRemoveBlock(PlayerTickEvent event, BlockPos pos, CallbackInfo ci) {
        if (isProtected(event.getEntity(), pos)) {
            ci.cancel();
        }
    }
}
