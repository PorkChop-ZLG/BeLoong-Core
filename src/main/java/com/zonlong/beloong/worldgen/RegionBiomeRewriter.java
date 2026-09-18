package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;

import java.util.function.Consumer;

/**
 * 通用 <b>TerraBlender region 装饰器</b>：把任意 region 输出的 {@code minecraft:} 群系改写成天灾维度的目标群系。
 *
 * <h3>为什么需要它（替代 per-mod mixin）</h3>
 * TerraBlender 的 {@code MixinParameterList.initializeForTerraBlender} 会遍历
 * {@code Regions.get(regionType)}，对每个 region 调 {@code addBiomes(registry, consumer)} 建 RTree；
 * 而 {@code findValuePositional} <b>先查 region 树</b>，只有拿到 {@code Region.DEFERRED_PLACEHOLDER}
 * 才回退到 index 0 兜底树。因此凡是被 region 树<b>直接吐出的具体群系</b>都必须在这里改写。
 * <p>
 * 旧实现 {@code BwgRegionBiomeRewriteMixin} 用 {@code @Mixin(targets = "...BWGTerraBlenderRegion")}
 * 只认 BWG 自己的 region 类 —— VanillaBackport 注册了自己的 {@code vanillabackport:overworld}
 * region 后完全绕开改写，导致 {@code minecraft:pale_garden} / {@code sulfur_caves} 泄漏进天灾维度
 * （2026-09-13 实机取证）。本类由 {@code RegionsGetMixin} 注入 TerraBlender 的
 * {@code Regions.get(...)} 出口，把返回列表里的<b>每一个</b> region 都包一层，
 * 从而一次覆盖所有现有与未来的 region 实现，不再需要 per-mod mixin。
 *
 * <h3>为什么在 {@code addBiomes} 内部包 mapper（而不是在 TB 的调用点包 consumer）</h3>
 * 这样<b>任何 caller</b> 都能读到改写后的结果 —— 包括 TB 的 region 树、{@code LevelUtils} 的追加列表、
 * {@code InitialLayer} 的 uniqueness 探测，以及本模组自己的账目
 * {@link DisasterBiomeSubstitution#logRegionTreeBiomes}（它自行调用 {@code addBiomes}）。
 * 账目因此与真实路径一致，不会自相矛盾。
 *
 * <h3>维度守卫与等价性</h3>
 * 只有 {@link DisasterBiomeSubstitution#isFilteringTargetBiomeList()} 为真（即正在初始化
 * {@code beloong:disaster}）时才改写；否则<b>原样透传</b>给被包装的 region，行为与未装饰完全一致
 * （主世界、下界、末地以及其它 TerraBlender 维度都不受影响）。判据用<b>作用域</b>标志而非全局的
 * {@code isSubstitutionApplied()}（决策 21）。
 *
 * <h3>异常安全</h3>
 * 本功能无配置开关、无逃生舱（决策 19/23），因此单条改写失败时<b>原样放行该条</b>，
 * 绝不让异常穿透 {@code addBiomes} —— 那会中断 TerraBlender 的初始化。
 * 残留项由 {@link DisasterBiomeSubstitution#logRegionTreeBiomes} 聚合上报。
 *
 * @see DisasterBiomeSubstitution#rewriteKey
 * @see com.zonlong.beloong.mixin.RegionsGetMixin
 */
public final class RegionBiomeRewriter extends Region {

    /** 被包装的真实 region。 */
    private final Region delegate;

    /**
     * @param delegate 被包装的 region；其 name / type / weight 原样转发，保证 TerraBlender 的
     *                 {@code getIndex} / {@code getCount} / uniqueness 分层判据不受影响
     */
    public RegionBiomeRewriter(Region delegate) {
        super(delegate.getName(), delegate.getType(), delegate.getWeight());
        this.delegate = delegate;
    }

    /**
     * 取回被包装的 region（供幂等判断：已包装则不再重复包装）。
     *
     * @return 被包装的真实 region
     */
    public Region delegate() {
        return this.delegate;
    }

    /**
     * 转发给被包装的 region，并在需要时改写其输出的群系。
     * <p>
     * 透传条件（两者任一成立即完全等价于未装饰）：① 当前不是在初始化天灾维度
     * （{@link DisasterBiomeSubstitution#isFilteringTargetBiomeList()} 为假）；
     * ② 群系注册表尚未就绪（{@link DisasterBiomeSubstitution#activeBiomeRegistry()} 为 null）。
     *
     * @param registry 群系注册表
     * @param mapper   TB（或本模组账目）传入的消费者
     */
    @Override
    public void addBiomes(Registry<Biome> registry,
                          Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
        if (!DisasterBiomeSubstitution.isFilteringTargetBiomeList()
                || DisasterBiomeSubstitution.activeBiomeRegistry() == null) {
            this.delegate.addBiomes(registry, mapper);
            return;
        }

        this.delegate.addBiomes(registry, pair -> {
            ResourceKey<Biome> from = pair.getSecond();
            ResourceKey<Biome> to;
            try {
                // logErrors=false：addBiomes 每个 region 会被调用两次（建树 + 建追加列表），
                // 逐条记 ERROR 会刷屏；残留项由 logRegionTreeBiomes 聚合上报。
                to = DisasterBiomeSubstitution.rewriteKey(registry, from, false);
            } catch (Throwable t) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] region tree rewrite failed for one entry, passing it through unchanged: {}",
                        from, t);
                to = from;
            }
            mapper.accept(Pair.of(pair.getFirst(), to));
        });
    }
}
