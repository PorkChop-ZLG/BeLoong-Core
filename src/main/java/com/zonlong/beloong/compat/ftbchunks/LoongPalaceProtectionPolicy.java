package com.zonlong.beloong.compat.ftbchunks;

import com.zonlong.beloong.BeLoongCore;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

public final class LoongPalaceProtectionPolicy {

    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace"));

    private LoongPalaceProtectionPolicy() {}

    public static boolean isLoongPalace(LevelAccessor level) {
        return level instanceof Level concreteLevel
                && DIMENSION.equals(concreteLevel.dimension());
    }

    public static boolean isLoongPalace(@Nullable Entity actor) {
        return actor != null && DIMENSION.equals(actor.level().dimension());
    }

    public static boolean shouldPrevent(LevelAccessor level, @Nullable Entity actor) {
        if (level instanceof Level concreteLevel && concreteLevel.isClientSide) {
            return false;
        }
        return isLoongPalace(level) && !hasBypass(actor);
    }

    public static boolean hasBypass(@Nullable Entity actor) {
        if (!(actor instanceof ServerPlayer player) || player instanceof FakePlayer) {
            return false;
        }

        FTBChunksAPI.API api = FTBChunksAPI.api();
        return api.isManagerLoaded()
                && api.getManager().getBypassProtection(player.getUUID());
    }
}
