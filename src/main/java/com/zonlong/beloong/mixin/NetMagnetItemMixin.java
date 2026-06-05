package com.zonlong.beloong.mixin;

import com.wintercogs.beyonddimensions.common.item.NetMagnetItem;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为超越维度的网络磁铁流体收集添加 FTB Chunks 领地保护兼容。
 *
 * <p>拦截 {@code fluidCollect()} 中的 {@code Level.setBlock()} 和
 * {@code BucketPickup.pickupBlock()} 调用，在执行前检查 FTB Chunks 领地归属，
 * 已被认领的区块内禁止移除流体。</p>
 *
 * <p>若 FTB Chunks 未安装或配置开关关闭，此 Mixin 不做任何拦截。</p>
 */
@Mixin(NetMagnetItem.class)
public abstract class NetMagnetItemMixin {

    @Unique
    private Entity beloong$capturedHolder;

    /**
     * 在 {@code workContent()} 调用 {@code fluidCollect()} 之前捕获物品持有者。
     */
    @Inject(
            method = "workContent",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/wintercogs/beyonddimensions/common/item/NetMagnetItem;fluidCollect(Lcom/wintercogs/beyonddimensions/common/machine/FilterMode;Ljava/util/List;Lcom/wintercogs/beyonddimensions/api/dimensionnet/UnifiedStorage;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/AABB;)V"
            ),
            remap = false
    )
    private void beloong$captureHolder(ItemStack stack, Level level, Entity holder, int slotId, boolean isSelected, CallbackInfo ci) {
        this.beloong$capturedHolder = holder;
    }

    /**
     * 重定向 {@code Level.setBlock()} —— 认领区块内跳过方块修改。
     */
    @Redirect(
            method = "fluidCollect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            ),
            remap = false
    )
    private boolean beloong$redirectSetBlock(Level level, BlockPos pos, BlockState state, int flags) {
        if (ClaimProtectionHelper.isClaimed(this.beloong$capturedHolder, pos, Config.BD_FTBCHUNKS_COMPAT::get)) {
            return false;
        }
        return level.setBlock(pos, state, flags);
    }

    /**
     * 重定向 {@code BucketPickup.pickupBlock()} —— 认领区块内跳过流体拾取。
     */
    @Redirect(
            method = "fluidCollect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/BucketPickup;pickupBlock(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/item/ItemStack;"
            ),
            remap = false
    )
    private ItemStack beloong$redirectPickupBlock(BucketPickup pickup, Player player, LevelAccessor levelAccessor, BlockPos pos, BlockState state) {
        if (ClaimProtectionHelper.isClaimed(this.beloong$capturedHolder, pos, Config.BD_FTBCHUNKS_COMPAT::get)) {
            return ItemStack.EMPTY;
        }
        return pickup.pickupBlock(player, levelAccessor, pos, state);
    }
}
