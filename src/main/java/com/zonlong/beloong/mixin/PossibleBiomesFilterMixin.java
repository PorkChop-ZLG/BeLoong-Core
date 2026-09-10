package com.zonlong.beloong.mixin;

import com.zonlong.beloong.worldgen.DisasterBiomeSubstitution;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 让天灾维度的 {@code BiomeSource.possibleBiomes()} 只暴露白名单原版群系。
 *
 * <h3>为什么注入点是 {@code appendDeferredBiomesList} 而不是 {@code collectPossibleBiomes}</h3>
 * {@code possibleBiomes()} 的实现是 {@code possibleBiomes.get()}——读一个 supplier 字段。
 * TerraBlender 的 {@code MixinBiomeSource.appendDeferredBiomesList} 会把这个字段整体替换为
 * 一个<b>缓存闭包</b>：
 * <pre>
 *   this.possibleBiomes = () -> new ObjectLinkedOpenHashSet&lt;&gt;(possibleBiomes.stream().distinct()...);
 * </pre>
 * 闭包捕获的是<b>替换那一刻</b>算出来的集合。因此只过滤
 * {@code MultiNoiseBiomeSource.collectPossibleBiomes()} 是无效的——实测注入确实触发了、
 * 判据也正确，但 {@code possibleBiomes()} 读的是那份已固化的缓存，不再经过被过滤的方法。
 * <p>
 * 唯一有效的做法是在<b>缓存被建立的那一点</b>就让内容干净：这里同时处理
 * <ul>
 *   <li>「现有集合」——关卡自己那份未替换的 {@code values} 算出来的原版群系；</li>
 *   <li>「本次追加的列表」——TerraBlender 传入的 BWG 群系列表（其中不含原版群系）。</li>
 * </ul>
 *
 * <h3>如何识别天灾维度</h3>
 * 用语义判据：TerraBlender 只在 {@code RegionType.OVERWORLD} 维度调用本方法，
 * 而实测五个维度中只有天灾维度的参数列表里含 {@code biomeswevegone:} 群系
 * （主世界 53 个全原版、下界 5 个全原版、末地走 TheEndBiomeSource、龙宫走 FixedBiomeSource）。
 * 因此「追加列表里含非 {@code minecraft:} 命名空间群系」即天灾维度，
 * 不需要依赖对象身份登记（实测关卡持有的 BiomeSource 并非初始化时那一个实例）。
 *
 * @see com.zonlong.beloong.worldgen.DisasterBiomeSubstitution
 * @see CloneParameterListMixin 生成路径的对应修复
 */
@Mixin(BiomeSource.class)
public abstract class PossibleBiomesFilterMixin {

    @Shadow
    public Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Inject(method = "appendDeferredBiomesList", at = @At("HEAD"), remap = false, cancellable = true)
    private void beloong$filterPossibleBiomes(List<Holder<Biome>> biomesToAppend, CallbackInfo ci) {
        if (biomesToAppend == null || biomesToAppend.isEmpty()) {
            return;
        }
        // 语义判据：追加列表里是否含 mod 群系（只有天灾维度会含）
        boolean modded = biomesToAppend.stream().anyMatch(h -> h.unwrapKey()
                .map(k -> !"minecraft".equals(k.location().getNamespace()))
                .orElse(false));
        if (!modded) {
            return;
        }

        List<Holder<Biome>> merged = new ArrayList<>();
        Set<Holder<Biome>> current;
        try {
            current = this.possibleBiomes == null ? Set.of() : this.possibleBiomes.get();
        } catch (Throwable t) {
            current = Set.of();
        }
        merged.addAll(current);
        merged.addAll(biomesToAppend);

        Set<Holder<Biome>> filtered = new LinkedHashSet<>();
        for (Holder<Biome> h : merged) {
            boolean blocklisted = h.unwrapKey()
                    .map(k -> DisasterBiomeSubstitution.isBlocklisted(k.location()))
                    .orElse(false);
            if (!blocklisted) {
                filtered.add(h);
            }
        }

        this.possibleBiomes = () -> Set.copyOf(filtered);
        // 自行完成追加，跳过 TerraBlender 的原始逻辑（它会用未过滤的集合重建缓存闭包）
        ci.cancel();
    }
}
