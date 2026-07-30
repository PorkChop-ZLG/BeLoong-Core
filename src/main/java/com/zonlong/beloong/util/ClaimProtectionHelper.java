package com.zonlong.beloong.util;

import com.zonlong.beloong.Config;
import com.zonlong.beloong.compat.ftbchunks.FTBChunksProtectionBridge;
import com.zonlong.beloong.compat.ftbchunks.LoongPalaceProtectionPolicy;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * FTB Chunks 领地保护的统一入口。
 * 通过 {@link dev.ftb.mods.ftbchunks.api.FTBChunksAPI} 判断指定位置是否处于已认领区块，
 * 无论玩家是否有团队权限，已认领区块一律拦截破坏行为。
 */
public class ClaimProtectionHelper {

    private ClaimProtectionHelper() {}

    public static boolean isClaimed(Entity actor, BlockPos pos) {
        return isClaimed(actor, pos, Config.DS_FTBCHUNKS_COMPAT::get);
    }

    public static boolean isClaimed(Entity actor, BlockPos pos, BooleanSupplier configGate) {
        if (!configGate.getAsBoolean()) {
            return false;
        }
        return isClaimedInternal(actor, pos);
    }

    private static boolean isClaimedInternal(Entity actor, BlockPos pos) {
        if (actor == null || pos == null) {
            return false;
        }

        if (!ModList.get().isLoaded("ftbchunks")) {
            return false;
        }

        if (LoongPalaceProtectionPolicy.isLoongPalace(actor)) {
            return !LoongPalaceProtectionPolicy.hasBypass(actor);
        }

        return FTBChunksProtectionBridge.isClaimed(actor, pos);
    }
}
