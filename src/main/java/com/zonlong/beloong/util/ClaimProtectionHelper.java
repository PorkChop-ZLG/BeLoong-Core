package com.zonlong.beloong.util;

import com.zonlong.beloong.Config;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.api.ProtectionPolicy;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

/**
 * FTB Chunks 领地保护的统一入口。
 * 通过 {@link dev.ftb.mods.ftbchunks.api.FTBChunksAPI} 判断指定位置是否处于已认领区块，
 * 无论玩家是否有团队权限，已认领区块一律拦截破坏行为。
 */
public class ClaimProtectionHelper {

    private ClaimProtectionHelper() {}

    private static final Protection ALWAYS_BLOCK =
            (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;

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

        var manager = FTBChunksAPI.api().getManager();
        if (manager == null) {
            return false;
        }

        return manager.shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, ALWAYS_BLOCK, null);
    }
}
