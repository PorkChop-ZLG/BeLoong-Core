package com.zonlong.beloong.compat.ftbchunks;

import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import dev.ftb.mods.ftbchunks.api.ProtectionPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

public final class FTBChunksProtectionBridge {

    private static final Protection ALWAYS_BLOCK =
            (player, pos, hand, chunk, entity) -> ProtectionPolicy.CHECK;

    private FTBChunksProtectionBridge() {}

    public static boolean isClaimed(Entity actor, BlockPos pos) {
        var manager = FTBChunksAPI.api().getManager();
        return manager != null
                && manager.shouldPreventInteraction(
                        actor, InteractionHand.MAIN_HAND, pos, ALWAYS_BLOCK, null);
    }
}
