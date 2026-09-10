package com.zonlong.beloong.worldgen;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.mixin.StructurePlacementAccessor;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 天灾维度专用的结构集视图：白名单过滤 + 放置参数覆写。
 * <p>
 * 供 {@link com.zonlong.beloong.mixin.ChunkMapMixin} 在天灾维度构建
 * {@link net.minecraft.world.level.chunk.ChunkGeneratorStructureState} 时替代
 * 原始注册表查询传入原版逻辑。原版在 {@code createForNormal} 中只会调用
 * {@link #listElements()}，其余方法仅做兼容转发。
 * <p>
 * 覆写规则（读取 {@code disaster_biomes} 配置）：
 * <ul>
 *   <li>白名单：结构集 ID ∈ {@code structureSetWhitelist} 才会出现在本视图；</li>
 *   <li>spacing/separation 覆写：配置值 ≥ 0 时替换原值（separation 必须小于
 *       spacing，否则由原版校验器抛错——已在计算时做钳制）；</li>
 *   <li>frequency 覆写：配置值 ∈ [0,1] 时替换原值；</li>
 *   <li>仅覆写 {@link RandomSpreadStructurePlacement}；同心环等其它 placement
 *       类型保持原值（并在日志提示一次）。</li>
 * </ul>
 * <p>
 * 覆写后的 {@link StructureSet} 由 {@link DisasterStructureSetHolder}（
 * {@link Holder.Reference} 子类）承载，保持 key()/kind() 语义完整；
 * 原版状态机只消费 {@code holder.value()}，不依赖注册表绑定身份。
 */
public final class DisasterStructureSetLookup implements HolderLookup<StructureSet>, HolderOwner<StructureSet> {

    /** 覆写结果缓存：原结构集引用 → 覆写后的引用（含 spacing/separation/frequency 覆写）。 */
    private final Map<Holder.Reference<StructureSet>, Holder.Reference<StructureSet>> rewrittenCache =
            new IdentityHashMap<>();

    private final HolderLookup<StructureSet> delegate;
    private final int spacingOverride;
    private final int separationOverride;
    private final double frequencyOverride;
    private boolean warnedNonSpreadPlacement = false;

    private DisasterStructureSetLookup(HolderLookup<StructureSet> delegate) {
        this.delegate = delegate;
        this.spacingOverride = Config.DisasterBiomes.structureSpacingOverride.get();
        this.separationOverride = Config.DisasterBiomes.structureSeparationOverride.get();
        this.frequencyOverride = Config.DisasterBiomes.structureFrequencyOverride.get();
        if (needsPlacementRewrite()) {
            BeLoongCore.LOGGER.info(
                    "[beloong] disaster_biomes: placement overrides active, spacingOverride={},"
                            + " separationOverride={}, frequencyOverride={}",
                    spacingOverride, separationOverride, frequencyOverride);
        }
    }

    /**
     * 包装策略：无 placement 覆写 → 原样透传。结构集去留完全由原版
     * "结构群系标签 ∩ 维度群系集"的交集逻辑决定，与结构罗盘等按标签判定的一方
     * 使用同一条规则，判定结果一致(写死,不进配置)。
     */
    public static HolderLookup<StructureSet> wrap(HolderLookup<StructureSet> delegate) {
        if (!Config.DisasterBiomes.enabled.get()) {
            return delegate;
        }
        DisasterStructureSetLookup lookup = new DisasterStructureSetLookup(delegate);
        return lookup.needsPlacementRewrite() ? lookup : delegate;
    }

    private boolean needsPlacementRewrite() {
        return spacingOverride >= 0 || separationOverride >= 0 || frequencyOverride >= 0;
    }

    @Override
    public Stream<Holder.Reference<StructureSet>> listElements() {
        if (!needsPlacementRewrite()) {
            return delegate.listElements();
        }
        return delegate.listElements().map(this::rewritten);
    }

    /** 结构集 → 应用 spacing/separation/frequency 覆写。 */
    private Holder.Reference<StructureSet> rewritten(Holder.Reference<StructureSet> ref) {
        return rewrittenCache.computeIfAbsent(ref, source -> {
            StructureSet original = source.value();
            StructurePlacement placement = original.placement();

            if (!(placement instanceof RandomSpreadStructurePlacement spread)) {
                if (!warnedNonSpreadPlacement) {
                    warnedNonSpreadPlacement = true;
                    BeLoongCore.LOGGER.info(
                            "[beloong] disaster_biomes: structure set '{}' uses a non random-spread placement ({});"
                                    + " keep-as-is (frequency/spacing overrides apply to random-spread placements only)",
                            source.key().location(), placement.getClass().getSimpleName());
                }
                return source;
            }

            int spacing = spacingOverride >= 0 ? spacingOverride : spread.spacing();
            int separation = separationOverride >= 0 ? separationOverride : spread.separation();
            // 原版校验: spacing > separation。钳制保证不炸。
            if (separation >= spacing) {
                separation = spacing - 1;
            }
            if (separation < 0) {
                separation = 0;
            }
            StructurePlacementAccessor acc = (StructurePlacementAccessor) placement;
            float originalFrequency = acc.beloong$frequency();
            float frequency = frequencyOverride >= 0 ? (float) frequencyOverride : originalFrequency;

            RandomSpreadStructurePlacement overridden = new RandomSpreadStructurePlacement(
                    acc.beloong$locateOffset(),
                    acc.beloong$frequencyReductionMethod(),
                    frequency,
                    acc.beloong$salt(),
                    acc.beloong$exclusionZone(),
                    spacing,
                    separation,
                    spread.spreadType());

            BeLoongCore.LOGGER.info(
                    "[beloong] disaster_biomes: '{}' placement rewritten: spacing {}→{}, separation {}→{}, frequency {}→{}",
                    source.key().location(),
                    spread.spacing(), spacing,
                    spread.separation(), separation,
                    originalFrequency, frequency);

            // 本视图自身实现 HolderOwner：子类引用保持 key()/kind() 语义完整。
            return new DisasterStructureSetHolder(this, source.key(),
                    new StructureSet(original.structures(), overridden));
        });
    }

    // ===== 以下为兼容转发：原版链路只用 listElements()，结构集不裁剪(交集判定) =====

    @Override
    public Optional<Holder.Reference<StructureSet>> get(ResourceKey<StructureSet> key) {
        return delegate.get(key);
    }

    @Override
    public Optional<HolderSet.Named<StructureSet>> get(TagKey<StructureSet> tag) {
        return delegate.get(tag);
    }

    @Override
    public Stream<HolderSet.Named<StructureSet>> listTags() {
        return delegate.listTags();
    }
}
