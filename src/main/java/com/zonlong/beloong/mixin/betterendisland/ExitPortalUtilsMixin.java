package com.zonlong.beloong.mixin.betterendisland;

import com.yungnickyoung.minecraft.betterendisland.world.IBetterDragonFight;
import com.yungnickyoung.minecraft.betterendisland.world.util.ExitPortalUtils;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.compat.betterendisland.CustomEndPortalAppearance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 YUNG's Better End Island 放置底部返回传送门后，覆盖为 BeLoong Core 的自定义 NBT。
 *
 * <p>只在以下条件同时满足时生效：</p>
 * <ul>
 *   <li>{@code DragonSummon.enabled} 为 true（共享总开关）</li>
 *   <li>当前放置的是底部传送门（{@code isBottomOnly == true}）</li>
 *   <li>龙已经被击杀/复活过（{@code hasDragonEverSpawned() == true}），首次生成不干预</li>
 * </ul>
 */
@Mixin(value = ExitPortalUtils.class, remap = false)
public abstract class ExitPortalUtilsMixin {

    @Inject(
            method = "spawnPortal(Lcom/yungnickyoung/minecraft/betterendisland/world/IBetterDragonFight;Lnet/minecraft/server/level/ServerLevel;ZZZ)V",
            at = @At("RETURN"),
            remap = false
    )
    private static void beloong$applyCustomEndPortal(
            IBetterDragonFight dragonFight,
            ServerLevel serverLevel,
            boolean isActive,
            boolean isBottomOnly,
            boolean noCrystalsOverride,
            CallbackInfo ci
    ) {
        if (!Config.DragonSummon.enabled.get()) {
            return;
        }
        if (!isBottomOnly) {
            return;
        }
        if (!dragonFight.hasDragonEverSpawned()) {
            return;
        }

        CustomEndPortalAppearance.apply(serverLevel, (EndDragonFight) dragonFight, isActive);
    }
}
