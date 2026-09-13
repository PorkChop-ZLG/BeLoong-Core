package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
        // 维度都会触发（实测 minecraft:the_nether 也会命中）。不加守卫会把下界群系
        // 换成 BWG 地表群系——已由实机日志确认会发生。
        //
        // 异常安全：本功能**没有配置开关**，没有"关掉逃生"的退路，因此这里必须兜住异常。
        // 失败时**继续**执行原有的 initializeForTerraBlender + setParameters，
        // 让 TerraBlender 按原行为工作，而不是让异常穿透 initializeOnServerStart
        // 的 level stem 循环（那会导致该维度之后的所有 level stem 全部不再初始化）。
        if (DisasterBiomeSubstitution.isTargetDimension(levelKey)) {
            try {
                List<Pair<Climate.ParameterPoint, Holder<Biome>>> filtered =
                        DisasterBiomeSubstitution.filter(targetRA,
                                ((ParameterListAccessor) cloned).beloong$getValues());
                ((ParameterListAccessor) cloned).beloong$setValues(filtered);
            } catch (Throwable t) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] disaster biome substitution failed, initializing with TerraBlender's"
                                + " default behaviour this time (the disaster dimension will keep vanilla biomes)", t);
            }

            // 账目：region 树里还剩哪些 minecraft: 群系。
            // 必须放在 filter() 之后——它依赖 filter() 写入的注册表与生效标志；
            // 也必须放在 initializeForTerraBlender 之前或之后均可（region 树在本调用之后才建），
            // 这里紧随 filter() 以便日志顺序与账目相邻。
            DisasterBiomeSubstitution.logRegionTreeBiomes(targetRA);
        }

        ((IExtendedParameterList) cloned).initializeForTerraBlender(targetRA, regionType, targetSeed);

        MultiNoiseBiomeSource biomeSource = (MultiNoiseBiomeSource) chunkGenerator.getBiomeSource();
        ((MultiNoiseBiomeSourceAccess) biomeSource).setParameters(Either.left(cloned));
    }

    /**
     * 为整个 {@code initializeBiomes} 调用设置<strong>作用域</strong>维度标志。
     * <p>
     * <b>为什么需要它：</b>{@code PossibleBiomesFilterMixin}（注入在
     * {@code appendDeferredBiomesList} 的 HEAD）与 {@code BwgRegionBiomeRewriteMixin}
     * （注入在 {@code BWGTerraBlenderRegion.addBiomes} 的 HEAD）都<strong>拿不到维度身份</strong>。
     * 它们原先读全局的 {@link DisasterBiomeSubstitution#isSubstitutionApplied()}，
     * 于是产生了一个真实缺陷：{@code LevelUtils.initializeOnServerStart} 遍历
     * <strong>所有</strong> level stem，处理完天灾之后标志已为真，轮到
     * {@code minecraft:the_nether} 时它的 {@code appendDeferredBiomesList} 也被过滤
     * ⇒ 下界 5 个原版群系从 {@code possibleBiomes()} 消失、结构集被
     * {@code hasBiomesForStructureSet} 整条剔除。
     * <p>
     * <b>为什么包夹整个方法而不是只包夹那一处调用：</b>{@code initializeBiomes} 内依次执行
     * 「{@code initializeForTerraBlender}（建 index≠0 的 RTree）→ region 循环（{@code LevelUtils:117-120}）
     * → {@code appendDeferredBiomesList}」，<strong>两条注入路径的目标都在这个区间内</strong>。
     * 用 HEAD/RETURN 一次包住，比逐个调用点重定向更简单也更不易漏。
     * {@code @At("RETURN")} 会匹配该方法<strong>所有</strong> return（它有两处提前返回），
     * 因此标志一定被清除。
     *
     * @param levelKey 本次初始化的 level stem 键——判定的唯一依据
     */
    @Inject(method = "initializeBiomes", at = @At("HEAD"))
    private static void beloong$beginTargetDimension(
        RegistryAccess registryAccess,
        Holder<DimensionType> dimensionType,
        ResourceKey<LevelStem> levelKey,
        ChunkGenerator chunkGenerator,
        long seed,
        CallbackInfo ci
    ) {
        if (DisasterBiomeSubstitution.isTargetDimension(levelKey)) {
            DisasterBiomeSubstitution.beginTargetBiomeList();
        }
    }

    /** 与 {@link #beloong$beginTargetDimension} 配对，清除作用域标志。 */
    @Inject(method = "initializeBiomes", at = @At("RETURN"))
    private static void beloong$endTargetDimension(
        RegistryAccess registryAccess,
        Holder<DimensionType> dimensionType,
        ResourceKey<LevelStem> levelKey,
        ChunkGenerator chunkGenerator,
        long seed,
        CallbackInfo ci
    ) {
        if (DisasterBiomeSubstitution.isTargetDimension(levelKey)) {
            DisasterBiomeSubstitution.endTargetBiomeList();
        }
    }
}
