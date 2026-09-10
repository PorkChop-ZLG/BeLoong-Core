package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Either;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.worldgen.DisasterBiomeSourceFactory;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import terrablender.api.RegionType;
import terrablender.mixin.MultiNoiseBiomeSourceAccess;
import terrablender.util.LevelUtils;
import terrablender.worldgen.IExtendedParameterList;

/**
 * TerraBlender 维度初始化拦截。
 * <p>
 * 对主世界（以及除天灾外的所有维度）：与历史行为一致——克隆共享参数表并按本维度
 * 初始化 TerraBlender，避免多维度共用 {@code minecraft:overworld} 预设时相互污染。
 * <p>
 * 对天灾维度（{@code beloong:disaster}）且 {@code disaster_biomes.enabled} 时：
 * <b>不</b>走 TerraBlender 初始化，而是用 {@link DisasterBiomeSourceFactory}
 * 重建一张纯白名单参数表（BWG + RU 命名空间群系 + 额外精确白名单），并装回
 * {@code Either.left(...)}。未初始化的列表在 TB 的 {@code findValuePositional}
 * 中自动退化为最近邻匹配——因此河流/海洋/洞穴的参数点不会被区域唯一性噪声稀释，
 * 原版陆地也不会再主宰整片气候空间。
 */
@Pseudo
@Mixin(value = LevelUtils.class, remap = false)
public class CloneParameterListMixin {

    @Redirect(
        method = "initializeBiomes",
        at = @At(
            value = "INVOKE",
            target = "Lterrablender/worldgen/IExtendedParameterList;initializeForTerraBlender(Lnet/minecraft/core/RegistryAccess;Lterrablender/api/RegionType;J)V"
        ),
        remap = false
    )
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void cloneBeforeInit(
        IExtendedParameterList receiver,
        RegistryAccess targetRA,
        RegionType regionType,
        long targetSeed,
        RegistryAccess enclosingRA,
        Holder<DimensionType> dimensionType,
        ResourceKey<LevelStem> levelKey,
        ChunkGenerator chunkGenerator,
        long enclosingSeed
    ) {
        if (isDisasterDimension(levelKey) && Config.DisasterBiomes.enabled.get()) {
            if (applyDisasterFilter(receiver, targetRA, (MultiNoiseBiomeSource) chunkGenerator.getBiomeSource())) {
                return;  // 天灾维度：白名单参数表已装好，不再走 TB 初始化
            }
            // 收集失败（BWG/RU 缺失且额外白名单为空）→ 落回通用路径，保证可玩性
        }

        // ===== 通用路径：与历史版本行为完全一致 =====
        Climate.ParameterList cloned = (Climate.ParameterList) receiver.clone();
        ((IExtendedParameterList) cloned).initializeForTerraBlender(targetRA, regionType, targetSeed);

        MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource) chunkGenerator.getBiomeSource();
        ((MultiNoiseBiomeSourceAccess) biomeSource).setParameters(Either.left(cloned));
    }

    /** 天灾维度判断：仅精确匹配 {@code beloong:disaster}。 */
    private static boolean isDisasterDimension(ResourceKey<LevelStem> levelKey) {
        return levelKey != null && DisasterBiomeSourceFactory.DISASTER_DIMENSION.equals(levelKey.location());
    }

    /**
     * 构建并装回白名单参数表。
     *
     * @return 是否成功装回（false = 保持原样，走通用路径）
     */
    @SuppressWarnings("unchecked")
    private static boolean applyDisasterFilter(
            IExtendedParameterList receiver, RegistryAccess registryAccess, MultiNoiseBiomeSource biomeSource) {
        // receiver 即共享预设参数表；clone() 只复制原版点集（TB 初始化不修改 values）。
        Climate.ParameterList<Holder<Biome>> sharedList = (Climate.ParameterList<Holder<Biome>>) receiver.clone();

        Climate.ParameterList<Holder<Biome>> filtered =
                DisasterBiomeSourceFactory.buildFilteredParameterList(sharedList, registryAccess);
        if (filtered == null) {
            return false;
        }

        // 关键：Either.left(普通列表)。该列表同样是 IExtendedParameterList（TB 对
        // Climate.ParameterList 的类注入），且未初始化 → findValuePositional 走
        // 未初始化分支 = 全表最近邻。这正是白名单选择器想要的语义。
        ((MultiNoiseBiomeSourceAccess) biomeSource).setParameters(Either.left(filtered));
        return true;
    }
}
