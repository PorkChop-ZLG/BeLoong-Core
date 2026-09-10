package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.worldgen.DisasterBiomeSubstitution;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
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

import java.util.List;

@Pseudo
@Mixin(value = LevelUtils.class, remap = false)
public class CloneParameterListMixin {

    /**
     * 克隆共享的 {@link Climate.ParameterList} 并隔离 TerraBlender 的初始化，
     * 同时把兜底树里的原版群系替换成 BWG 群系。
     * <p>
     * <b>职责一（原有）：防止跨维度污染。</b>
     * 主世界与天灾共用同一个 {@code minecraft:overworld} preset，因此两个维度的
     * {@code MultiNoiseBiomeSource.parameters()} 返回的是<strong>同一个
     * ParameterList 对象</strong>（持有 {@code preset} 一侧时，取值链路是
     * {@code Holder.value() → MultiNoiseBiomeSourceParameterList.parameters()}，
     * 即注册表单例）。若不干预，初始化天灾会把共享对象标记为 initialized 并写入带 BWG 的
     * {@code uniqueTrees}，使 BWG 群系泄漏回主世界。
     * <p>
     * <b>职责二（新增）：剔除天灾维度的原版群系。</b>
     * {@code MixinParameterList.initializeForTerraBlender} 内部以
     * {@code Climate.RTree.create(this.values)} 构建 index 0 兜底树，而 TerraBlender 在
     * 命中 {@code Region.DEFERRED_PLACEHOLDER} 时会回退到这棵树。因此替换 {@code values}
     * 的时机必须<strong>早于</strong>该调用——本回调体正是唯一满足「每维度独立 + 早于建树 +
     * 能整体替换 final 字段」三个条件的位置。
     * <p>
     * 替换走 {@link DisasterBiomeSubstitution}；其 {@code possibleBiomes()} 会随之干净，
     * 因为 {@code possibleBiomes()} 由 {@code parameters().values()} 每次调用现算，非构造期快照。
     * <p>
     * <b>主世界不受影响：</b>{@code terrablender:overworld_regions} tag 不含
     * {@code minecraft:overworld}，{@code LevelUtils.getRegionTypeForDimension} 返回 null，
     * {@code initializeBiomes} 在该调用点之前就已 return，本重定向根本不会触发。
     */
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
        Climate.ParameterList cloned = (Climate.ParameterList) receiver.clone();

        // 先替换 values，再初始化——uniqueTrees[0] 是在 initializeForTerraBlender 内部
        // 由 this.values 构建的，晚于此处的任何修改都不会影响兜底树。
        //
        // 必须限定维度：本重定向对**所有**被 TerraBlender 判定为 OVERWORLD region 的
        // 维度都会触发（实测 minecraft:the_nether 也会命中，因为 TerraBlender 按
        // RegionType 而非维度身份处理）。不加守卫会把下界群系也换成 BWG 地表群系。
        if (DisasterBiomeSubstitution.isTargetDimension(levelKey)) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> filtered =
                    DisasterBiomeSubstitution.filter(targetRA,
                            ((ParameterListAccessor) cloned).beloong$getValues());
            ((ParameterListAccessor) cloned).beloong$setValues(filtered);
        }

        ((IExtendedParameterList) cloned).initializeForTerraBlender(targetRA, regionType, targetSeed);

        MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource) chunkGenerator.getBiomeSource();
        ((MultiNoiseBiomeSourceAccess) biomeSource).setParameters(Either.left(cloned));
    }
}
