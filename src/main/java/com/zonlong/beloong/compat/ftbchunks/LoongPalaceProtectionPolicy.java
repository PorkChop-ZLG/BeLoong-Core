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
import net.neoforged.fml.ModList;
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

    /**
     * 供<b>第 3 方 FTB 兼容模组</b>（如建筑之杖）使用：该玩家在该维度是否应被当作
     * 「身处他人已认领的区块」，即无权在此编辑。
     *
     * <p>判据与既有玩家编辑保护（{@code LoongPalaceProtectionHandler#shouldPreventEdit}）一致，
     * 且沿用同一道<b>注册门</b>：FTB Chunks 缺席时该保护本就不存在，这里同样返回 {@code false}，
     * 以免出现「建筑之杖被拦、手挖却放行」的不对称。
     *
     * <p>⚠️ <b>只对龙宫生效</b>：非龙宫维度一律返回 {@code false}。不要改用
     * {@code util/ClaimProtectionHelper#isClaimed} —— 它会对所有位置套用 FTB 标准判定，
     * 而本模组并不想改变建筑之杖在别处的行为（它自己用的是认白名单的 {@code Protection.EDIT_BLOCK}）。
     */
    public static boolean shouldTreatAsClaimed(LevelAccessor level, @Nullable Entity actor) {
        return ModList.get().isLoaded("ftbchunks") && shouldPrevent(level, actor);
    }
}
