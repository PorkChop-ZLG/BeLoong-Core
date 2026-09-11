package com.zonlong.beloong.mixin;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.worldgen.DisasterBiomeSubstitution;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * 第三条注入路径：改写 <b>BWG region 树</b>输出的参数对。
 *
 * <h3>为什么必须有这一条</h3>
 * TerraBlender 的 {@code MixinParameterList.initializeForTerraBlender} 为<strong>每个 region</strong>
 * 各建一棵 RTree（{@code MixinParameterList.java:74-95}），而 {@code findValuePositional}
 * <strong>先查 region 树</strong>，只有拿到 {@code Region.DEFERRED_PLACEHOLDER} 才回退到
 * index 0 的兜底树（{@code :144-149}）。
 * <p>
 * index 0 才是 {@link DisasterBiomeSubstitution#filter} 改写的那一份
 * （{@code MixinParameterList.java:79-82} 用 {@code this.values} 建树）。
 * 因此凡是被 region 树<strong>直接吐出的具体群系</strong>，现有两条注入路径都无法触及。
 * <p>
 * 实测（见 {@code docs/reviews/2026-09-11-disaster-region-tree-probe.md}）：
 * BWG 的三个 region 各自吐出 11–12 种 {@code minecraft:} 群系，其中
 * <ul>
 *   <li><b>7 项是白名单里的</b>（{@code river} {@code frozen_river} {@code lush_caves}
 *       {@code dripstone_caves} {@code deep_dark} {@code stony_shore} {@code windswept_savanna}）
 *       —— 不修就无法在第二阶段接管</li>
 *   <li><b>5 项是黑名单里的</b>（{@code badlands} {@code eroded_badlands} {@code wooded_badlands}
 *       {@code mushroom_fields} {@code beach}）—— 第一阶段的<strong>实际泄漏</strong>：
 *       生成层照常产出，只是被查询层过滤器从 {@code possibleBiomes()} 隐藏，
 *       构成设计决策 19 判定不可接受的「第三种状态」。本 Mixin 同时修掉它们</li>
 * </ul>
 *
 * <h3>为什么注入点是 {@code addBiomes}</h3>
 * {@code BWGTerraBlenderRegion.addBiomes} 的最后一个分支
 * （{@code BWGTerraBlenderRegion.java:165-168}）会把既不在 BWG 数组、也不在 swapper 里的
 * 群系<strong>原样放行</strong>。而它恰好是<strong>唯一一个两条子路径都会经过</strong>的位置：
 * <ul>
 *   <li>建 RTree —— {@code MixinParameterList.java:85-89}</li>
 *   <li>建追加列表 —— {@code LevelUtils.java:117-120}</li>
 * </ul>
 * 因此<strong>一处注入同时覆盖两者</strong>。目标是公开方法、签名稳定。
 *
 * <h3>维度守卫</h3>
 * 与查询层一致，读 {@link DisasterBiomeSubstitution#isSubstitutionApplied()} ——
 * 生成层没有真的替换过就完全不动手（设计决策 21）。
 * <p>
 * 该标志是<strong>全局</strong>的，因此还需说明为何不会误伤其他维度：
 * <ul>
 *   <li><b>主世界</b>：不在 {@code terrablender:overworld_regions} tag 内，
 *       {@code LevelUtils.initializeBiomes} 在 region 循环<strong>之前</strong>就已 return</li>
 *   <li><b>下界</b>：它确实会走 region 循环（实测日志有
 *       {@code Initialized TerraBlender biomes for level stem minecraft:the_nether}），
 *       但循环取的是 {@code Regions.get(RegionType.NETHER)} = 只有
 *       {@code DefaultNetherRegion}，<strong>不会调用 BWG 的 {@code BWGTerraBlenderRegion}</strong>，
 *       故本 Mixin 根本不触发</li>
 * </ul>
 * ⚠️ 若将来 BWG 注册了 NETHER 类型的 region，上面的第二条就不再成立，
 * 届时必须把守卫改为按维度判定。
 *
 * <h3>异常安全</h3>
 * 本功能<strong>没有配置开关、没有逃生舱</strong>（设计决策 19、23）。因此单条改写失败时
 * <b>原样放行</b>，绝不让异常穿透——异常若逃出会进入 {@code initializeForTerraBlender}
 * 或 {@code LevelUtils} 的 level stem 循环，导致该维度之后的所有 level stem 不再初始化。
 *
 * <h3>为什么用字符串 targets 而不是 import</h3>
 * BWG 在本项目中<strong>只有 {@code localRuntime}、没有 {@code compileOnly}</strong>
 * （见 {@code build.gradle} 与设计决策 18：其群系启用状态通过反射读取，
 * 避免 {@code NoClassDefFoundError} 发生在类校验期、外层 try/catch 捕获不到）。
 * 用 {@code targets} 字符串 + {@link Pseudo} 可以<strong>不产生任何编译期依赖</strong>地
 * mixin 进 BWG 的类，维持这一现状。处理器签名只用原版类型，因此无需引用 BWG 的任何类。
 *
 * @see DisasterBiomeSubstitution#rewriteKey
 */
@Pseudo
@Mixin(targets = "net.potionstudios.biomeswevegone.world.level.levelgen.biome.BWGTerraBlenderRegion", remap = false)
public class BwgRegionBiomeRewriteMixin {

    /**
     * 包裹 BWG 传入的 mapper，使每个参数对在交出去之前先经过群系改写。
     *
     * @param mapper BWG 原本要写入的消费者
     * @return 改写后的消费者；不需要改写时原样返回
     */
    @ModifyVariable(method = "addBiomes", at = @At("HEAD"), argsOnly = true, remap = false)
    private Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> beloong$rewriteRegionBiomes(
            Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {

        Registry<Biome> registry = DisasterBiomeSubstitution.activeBiomeRegistry();
        // 维度判据用**作用域**标志（由 CloneParameterListMixin 在 LevelUtils.initializeBiomes
        // 的 HEAD/RETURN 用 levelKey 精确包夹），不能用全局的 isSubstitutionApplied()——
        // 后者一旦天灾处理过就永久为真。见该标志的 javadoc。
        if (!DisasterBiomeSubstitution.isFilteringTargetBiomeList() || registry == null) {
            return mapper;
        }

        return pair -> {
            ResourceKey<Biome> from = pair.getSecond();
            ResourceKey<Biome> to;
            try {
                // logErrors=false：addBiomes 每个 region 会被调用两次，逐条记 ERROR 会刷屏。
                // 残留项由 DisasterBiomeSubstitution#logRegionTreeBiomes 聚合上报。
                to = DisasterBiomeSubstitution.rewriteKey(registry, from, false);
            } catch (Throwable t) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] 天灾 region 树单条改写失败，该参数对原样放行：{}", from, t);
                to = from;
            }
            mapper.accept(Pair.of(pair.getFirst(), to));
        };
    }
}
