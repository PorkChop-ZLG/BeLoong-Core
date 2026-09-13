package com.zonlong.beloong.mixin;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.worldgen.RegionBiomeRewriter;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 TerraBlender 交出去的每一个 {@link Region} 包成 {@link RegionBiomeRewriter}。
 *
 * <h3>为什么注入 {@code Regions.get(RegionType)}</h3>
 * 它是 TerraBlender 把 region 交给消费方的<b>唯一出口</b>，TB 内部全部调用点都经过它：
 * <ul>
 *   <li>{@code MixinParameterList.initializeForTerraBlender}（建每棵 region 的 RTree —— 实际生成来源）</li>
 *   <li>{@code LevelUtils.initializeBiomes}（建 {@code appendDeferredBiomesList} 的追加列表）</li>
 *   <li>{@code InitialLayer.createEntries}（uniqueness 分层：只判"该 region 有没有输出"，装饰器照常
 *       输出 ⇒ 行为不变）</li>
 *   <li>本模组账目 {@code DisasterBiomeSubstitution#logRegionTreeBiomes}（自行调用 {@code addBiomes}）</li>
 * </ul>
 * 因此一处注入即覆盖所有路径，<b>替代了原先只认 BWG 类名的 {@code BwgRegionBiomeRewriteMixin}</b>
 * （该 per-mod 写法正是 VanillaBackport 的 region 绕过改写、导致群系泄漏的原因，见
 * {@link RegionBiomeRewriter} 的说明）。
 *
 * <h3>为什么可以安全地改变实例身份</h3>
 * 已逐条核实 TerraBlender 内部对 {@code Region} <b>没有强转、没有身份比较</b>：只用
 * {@code getName()}（{@code Regions.getIndex}）、{@code getType()}（{@code InitialLayer:59}）、
 * {@code getWeight()}（{@code InitialLayer:60}）与 {@code addBiomes(..)}。
 * 装饰器通过 {@code super(name, type, weight)} 原样转发这三者 ⇒ 索引、计数与 uniqueness 权重都不变。
 *
 * <h3>异常安全</h3>
 * 本功能无配置开关、无逃生舱（决策 19/23）。注入体整体 {@code try/catch}：任何异常都<b>不改变</b>
 * TB 的返回值（退化为未装饰，等价于修复前的行为），绝不让异常穿透。
 * 另：{@code beloong.mixins.json} 的 {@code injectors.defaultRequire = 1} 保证"找不到注入目标时
 * 直接启动报错"——即失败是响亮的，而不是静默泄漏。
 *
 * @see RegionBiomeRewriter
 */
@Mixin(value = Regions.class, remap = false)
public class RegionsGetMixin {

    /**
     * 包装 {@code Regions.get(type)} 的返回值。
     *
     * @param type 查询的 region 类型
     * @param cir  返回值回调；仅在确有 region 需要包装时才改写
     */
    @Inject(method = "get", at = @At("RETURN"), cancellable = true, remap = false)
    private static void beloong$wrapRegions(RegionType type, CallbackInfoReturnable<List<Region>> cir) {
        try {
            List<Region> original = cir.getReturnValue();
            if (original == null || original.isEmpty()) {
                return;
            }
            List<Region> wrapped = new ArrayList<>(original.size());
            boolean changed = false;
            for (Region region : original) {
                // 幂等：已包装则原样保留（Regions.get 每次返回新列表，但同一实例可能被多次取出）
                if (region instanceof RegionBiomeRewriter) {
                    wrapped.add(region);
                } else {
                    wrapped.add(new RegionBiomeRewriter(region));
                    changed = true;
                }
            }
            if (changed) {
                cir.setReturnValue(wrapped);
            }
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] failed to wrap TerraBlender regions; using them unwrapped this time", t);
        }
    }
}
