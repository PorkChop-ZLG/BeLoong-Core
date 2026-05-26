package com.zonlong.beloong.util;

import com.zonlong.beloong.Config;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.api.ProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.ModList;

public class ClaimProtectionHelper {

    private static final Protection ALWAYS_BLOCK =
            (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;

    public static boolean isClaimed(Entity actor, BlockPos pos) {
        if (actor == null || pos == null) {
            return false;
        }

        if (!ModList.get().isLoaded("ftbchunks")) {
            return false;
        }

        if (!Config.DS_FTBCHUNKS_COMPAT.get()) {
            return false;
        }

        var manager = FTBChunksAPI.api().getManager();
        if (manager == null) {
            return false;
        }

        return manager.shouldPreventInteraction(actor, InteractionHand.MAIN_HAND, pos, ALWAYS_BLOCK, null);
    }
}
