package com.zonlong.beloong.mixin;

import com.wintercogs.beyonddimensions.common.item.NetMagnetItem;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.util.ClaimProtectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 为超越维度的网络磁铁流体收集添加 FTB Chunks 领地保护兼容。
 *
 * <p>在 {@code fluidCollect()} 的 {@code Level.getFluidState()} 调用处注入，
 * 认领区块内返回空流体状态，使后续的存储插入和方块修改全部跳过。</p>
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
     * 重定向 {@code Level.getFluidState()} —— 认领区块内返回空流体，阻断整个收集流程。
     */
    @Redirect(
            method = "fluidCollect",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
            ),
            remap = false
    )
    private FluidState beloong$redirectGetFluidState(Level level, BlockPos pos) {
        if (ClaimProtectionHelper.isClaimed(this.beloong$capturedHolder, pos, Config.BD_FTBCHUNKS_COMPAT::get)) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return level.getFluidState(pos);
    }
}
