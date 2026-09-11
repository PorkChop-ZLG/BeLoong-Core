package com.zonlong.beloong.mixin;

import com.google.common.collect.ImmutableSet;
import com.zonlong.beloong.BeLoongCore;
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
 * {@code possibleBiomes()} 的实现是 {@code possibleBiomes.get()}——读一个 supplier 字段，
 * 而原版构造器<b>已经把它包成 {@code Suppliers.memoize}</b>（已用 {@code javap} 核实）。
 * TerraBlender 的 {@code MixinBiomeSource.appendDeferredBiomesList} 又会把这个字段整体替换为
 * 一个新的缓存闭包。因此：
 * <ul>
 *   <li>只过滤 {@code MultiNoiseBiomeSource.collectPossibleBiomes()} 是<b>无效</b>的——
 *       缓存建立后不再经过那个方法（已实测确认）</li>
 *   <li>唯一有效的做法是在<b>缓存被建立的那一点</b>就让内容干净</li>
 * </ul>
 *
 * <h3>如何识别天灾维度</h3>
 * 用 {@link DisasterBiomeSubstitution#isSubstitutionApplied()}——由生成层的
 * {@link CloneParameterListMixin} 在真的替换成功时置位。
 * <p>
 * <b>为什么不用"追加列表里是否含 BWG 群系"这个判据</b>（本类早期实现）：该列表来自
 * <b>全局</b> region 表，与"当前是哪个维度"无关。任何进入
 * {@code terrablender:overworld_regions} tag 的维度，其追加列表都必然含 BWG 群系，
 * 判据恒真。主世界目前之所以安全，靠的是本模组用自己的 datapack 以
 * {@code "replace": true} 把该 tag 覆盖成只含 {@code beloong:disaster}——
 * 一旦有数据包把 {@code minecraft:overworld} 放回去，主世界的原版群系就会被静默剔除。
 * <p>
 * <b>已知局限（刻意不修）</b>：该标志由服务端初始化时置位，因此<b>专用服务器的客户端不成立</b>，
 * 那里会回退为本模组修复前的行为（自然罗盘的列表会显示原版群系在天灾维度）。
 * 生成与 {@code /locate}（服务端）不受影响。
 *
 * <h3>保序与记忆化</h3>
 * 写入的新 supplier 必须<b>保序</b>：{@code possibleBiomes()} 的迭代顺序会进入
 * {@code ChunkGenerator} 的 {@code FeatureSorter.buildFeaturesPerStep}，决定
 * {@code PlacedFeature} 的全局编号，进而影响 {@code setFeatureSeed} 与地物放置顺序。
 * 原版用 Guava {@code ImmutableSet}、TerraBlender 用 {@code ObjectLinkedOpenHashSet}，
 * 两者都保序；而 {@code Set.copyOf} 返回的 {@code ImmutableCollections$SetN} 其迭代顺序由
 * 每次 JVM 启动随机化的 {@code SALT32L} 决定，<b>不保序</b>。
 * <p>
 * 并且必须<b>记忆化</b>：算一次快照、返回常量，而不是每次调用都重建集合
 * （原版被 {@code Suppliers.memoize} 包裹就是这个意图，TerraBlender 与本类早先的实现都把它丢了）。
 *
 * @see DisasterBiomeSubstitution
 * @see CloneParameterListMixin 生成路径的对应修复
 */
@Mixin(BiomeSource.class)
public abstract class PossibleBiomesFilterMixin {

    @Shadow
    public Supplier<Set<Holder<Biome>>> possibleBiomes;

    @Inject(method = "appendDeferredBiomesList", at = @At("HEAD"), remap = false, cancellable = true)
    private void beloong$filterPossibleBiomes(List<Holder<Biome>> biomesToAppend, CallbackInfo ci) {
        // 生成层没有真的替换过 → 查询层也不过滤，保持两层一致
        if (!DisasterBiomeSubstitution.isSubstitutionApplied()) {
            return;
        }
        if (biomesToAppend == null || biomesToAppend.isEmpty()) {
            return;
        }

        List<Holder<Biome>> merged = new ArrayList<>();
        try {
            Set<Holder<Biome>> current = this.possibleBiomes == null
                    ? Set.of() : this.possibleBiomes.get();
            merged.addAll(current);
        } catch (Throwable t) {
            // 不要把"现有集合"整个丢掉——那会让白名单里的原版群系也从查询层消失，
            // 只留 BWG。保持原 supplier 不动，让 TerraBlender 按原逻辑追加（退化为未过滤）。
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 读取 possibleBiomes 现状失败，本次不做查询层过滤"
                            + "（/locate 与自然罗盘会照旧显示原版群系）", t);
            return;
        }
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

        // ImmutableSet.copyOf 保序，与原版 ImmutableSet / TerraBlender ObjectLinkedOpenHashSet
        // 语义一致；且这是一次性快照，返回常量即完成记忆化。
        Set<Holder<Biome>> snapshot = ImmutableSet.copyOf(filtered);
        this.possibleBiomes = () -> snapshot;

        // 自行完成追加，跳过 TerraBlender 的原始逻辑（它会用未过滤的集合重建缓存闭包）
        ci.cancel();
    }
}
