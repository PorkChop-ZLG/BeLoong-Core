package com.zonlong.beloong.mixin;

import com.example.fsweep.logic.ButtonControlResolver;
import com.zonlong.beloong.Config;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复 fsweep 打开马/载具等容器时因 {@code AbstractContainerMenu.getType()}
 * 抛出 {@link UnsupportedOperationException} 导致的连接丢失崩溃。
 *
 * <p>fsweep 的 {@code ButtonControlResolver.resolveMenuId} 调用
 * {@code menu.getType()} 获取菜单类型 ID，但某些容器子类（如
 * {@code HorseInventoryMenu}）覆写了该方法并在无法返回 {@link MenuType} 时
 * 直接抛出异常。本 Mixin 拦截该调用，捕获异常后返回 {@code null}，
 * 使 fsweep 的现有 null 检查逻辑自然接管（视为无法识别菜单 → 不显示按钮）。</p>
 *
 * <p>由公共配置项 {@code 修复F-Sweep打开部分容器崩溃} 控制（默认开启）。</p>
 */
@Mixin(value = ButtonControlResolver.class, remap = false)
public abstract class FSweepButtonControlMixin {

    @Redirect(
            method = "resolveMenuId",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;getType()Lnet/minecraft/world/inventory/MenuType;"
            )
    )
    private static MenuType<?> safeGetType(AbstractContainerMenu menu) {
        if (!Config.FIX_FSWEEP_CONTAINER_CRASH.get()) {
            return menu.getType();
        }
        try {
            return menu.getType();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }
}
