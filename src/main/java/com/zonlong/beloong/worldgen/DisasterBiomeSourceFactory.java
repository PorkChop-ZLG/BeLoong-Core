package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.RegistryAccess;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.mixin.ChunkGeneratorBiomeSourceAccessor;

import net.minecraft.world.level.chunk.ChunkGenerator;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;
import terrablender.mixin.MultiNoiseBiomeSourceAccess;
/**
 * 天灾维度生物群系来源工厂。
 * <p>
 * 机制（替代 TerraBlender 的区域唯一性噪声路由）：
 * <ol>
 *   <li>从共享的 {@code minecraft:overworld} 预设参数表（vanilla 点集）中
 *       摘取命名空间/精确 ID 白名单命中的条目 —— 主要覆盖原版水体/洞穴类群系
 *       （当用户在白名单中手动加入它们时）；</li>
 *   <li>遍历所有注册的 TerraBlender OVERWORLD 区域，调用其公开的
 *       {@link Region#addBiomes(Registry, java.util.function.Consumer)}，
 *       按同样的白名单规则摘取该区域贡献的 (参数点, 群系) 对 ——
 *       这是 BWG / RU 群系进入天灾维度的通道；</li>
 *   <li>用摘取结果重建一张<b>普通</b> {@link Climate.ParameterList} 并通过
 *       {@code MultiNoiseBiomeSourceAccess#setParameters(Either.left(...))} 装回。</li>
 * </ol>
 * <p>
 * 该列表<b>不</b>调用 {@code initializeForTerraBlender}，即天灾维度的生物群系
 * 选择走 TB 的"未初始化"分支（{@code findValuePositional} 直接退化为
 * 全表最近邻匹配）。这与原版多噪声语义一致：参数点越近越优先。
 * 因此 BWG 陆地点与原版/RU 水体洞穴点在各自气候区间内互不挤占，
 * 河流/海洋/洞穴自然生成。
 * <p>
 * 若白名单过滤后没有收集到任何条目（例如 BWG/RU 未安装且额外白名单为空），
 * 则返回 {@code null}，调用方保持原样行为以保证不崩坏。
 */
public final class DisasterBiomeSourceFactory {
    private DisasterBiomeSourceFactory() {}

    /** 天灾维度 ID（与 DisasterPortalBlock#DISASTER_DIM 保持一致）。 */
    public static final ResourceLocation DISASTER_DIMENSION =
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "disaster");

    /** 主世界 LevelStem 键。 */
    private static final ResourceKey<LevelStem> LEVEL_STEM_OVERWORLD = LevelStem.OVERWORLD;

    /** 单次告警去重。 */
    private static boolean warnedEmpty = false;

    /** 最近一次回退路径中被跳过的 TB 区域数（用于日志）。 */
    private static int skippedTotal = 0;


    /**
     * 构建天灾维度过滤后的参数表。
     *
     * @param sharedList 共享的 {@code minecraft:overworld} 预设参数表
     *                   （TB 未合并时其 {@code values()} 只含原版点集）
     * @param registryAccess 注册表访问器
     * @return 过滤后的参数表；收集结果为空时返回 {@code null}（调用方保持原样）
     */
    public static Climate.ParameterList<Holder<Biome>> buildFilteredParameterList(
            Climate.ParameterList<Holder<Biome>> sharedList, RegistryAccess registryAccess) {
        List<String> extraIds = allowedBiomes();

        // 首选来源：主世界生物群系来源的参数表。Lithostitched 的
        // BiomeInjectorManager 在 initServer 尾部把主世界的来源替换为
        // InjectorBiomeSource，其参数表（Either.left）已合并
        // 原版 + BWG(TB) + RU(LH注入) 的全部 (参数点, 群系) 对——
        // 对它做白名单过滤即得完整答案，且天然跟随任何注入器的增删。
        Climate.ParameterList<Holder<Biome>> merged = readMergedOverworldList(registryAccess);
        if (merged != null) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected =
                    filterPairs(merged.values(), extraIds);
            Climate.ParameterList<Holder<Biome>> result = finish(collected, "overworld-merged");
            if (result != null) {
                return result;
            }
            BeLoongCore.LOGGER.info(
                    "[beloong] disaster_biomes: overworld-merged source produced no pairs;"
                            + " falling back to sharedList + TerraBlender regions");
        }
        // （Lithostitched 缺失/未合并时的保底，也是 BWG 单独可用时的路径）。
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected =
                filterPairs(sharedList.values(), extraIds);
        collected.addAll(harvestTerraBlenderRegions(registryAccess, extraIds));
        return finish(collected, "shared+TB");
    }

    /**
     * 读取主世界来源的参数表。仅当其 {@code parameters} 已是
     * {@code Either.left}（即已被 Lithostitched/TerraBlender 合并过）时返回；
     * 仍是 {@code Either.right(预设)} 时返回 {@code null}（此时合并尚未发生，
     * 内容与共享预设一致，继续走回退路径）。
     */
    @SuppressWarnings("unchecked")
    private static Climate.ParameterList<Holder<Biome>> readMergedOverworldList(RegistryAccess registryAccess) {
        try {
            var levelStems = registryAccess.lookupOrThrow(Registries.LEVEL_STEM);
            var overworldHolder = levelStems.get(LEVEL_STEM_OVERWORLD).orElse(null);
            if (overworldHolder == null) return null;
            MultiNoiseBiomeSource overworldSource =
                    unwrapToMultiNoise(((ChunkGeneratorBiomeSourceAccessor) overworldHolder.value().generator()).beloong$biomeSource());
            if (overworldSource == null) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: overworld biome source is not a multi-noise source"
                        + " (or no delegate drill); using shared+TB fallback");
                return null;
            }
            Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters =
                    ((MultiNoiseBiomeSourceAccess) overworldSource).getParameters();
            if (parameters.left().isEmpty()) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: overworld source still preset-bound (no merged list);"
                        + " using shared+TB fallback");
            }
            return parameters.left().orElse(null);
        } catch (Exception e) {
            BeLoongCore.LOGGER.info("[beloong] disaster_biomes: overworld parameters unavailable: {}", e.toString());
            return null;
        }
    }

    /**
     * 逐层解包到真正的多噪声来源：Lithostitched 会把主世界来源替换为其
     * {@code InjectorBiomeSource}（非 MultiNoiseBiomeSource 子类，公共
     * {@code directDelegate()} 指向被其合并后的多噪声来源）。为避免对
     * Lithostitched 的编译期依赖，这里用反射调用该公开访问器（最多三层）。
     */
    private static MultiNoiseBiomeSource unwrapToMultiNoise(BiomeSource source) {
        for (int depth = 0; source != null && depth < 3; depth++) {
            if (source instanceof MultiNoiseBiomeSource mnbs) {
                return mnbs;
            }
            BiomeSource next = delegateOf(source, "directDelegate");
            if (next == null) next = delegateOf(source, "rootDelegate");
            if (next == source || next == null) return null;
            source = next;
        }
        return null;
    }

    /** 反射调用无参公共访问器，失败返回 null。 */
    private static BiomeSource delegateOf(BiomeSource source, String methodName) {
        try {
            var method = source.getClass().getMethod(methodName);
            method.setAccessible(true);
            return (BiomeSource) method.invoke(source);
        } catch (Exception e) {
            return null;
        }
    }

    /** 白名单过滤 + 去重。 */
    private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> filterPairs(
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> values, List<String> extraIds) {
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : values) {
            ResourceLocation biomeId = biomeId(entry.getSecond());
            if (biomeId == null) continue;
            if (!isAllowed(biomeId, extraIds)) continue;
            if (seen.add(entry.getFirst() + "\u0000" + biomeId)) {
                collected.add(entry);
            }
        }
        return collected;
    }

    /**
     * 直接遍历注册在 TerraBlender 上的 OVERWORLD 区域并采集其 (点, 群系) 对。
     * {@code Region#addBiomes} 的 Consumer 接收 {@code Pair<ParameterPoint, ResourceKey<Biome>>}。
     */
    private static List<Pair<Climate.ParameterPoint, Holder<Biome>>> harvestTerraBlenderRegions(
            RegistryAccess registryAccess, List<String> extraIds) {
        Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int skippedRegions = 0;
        for (Region region : Regions.get(RegionType.OVERWORLD)) {
            if (!isNamespaceAllowed(region.getName().getNamespace())) {
                skippedRegions++;
                continue;
            }
            region.addBiomes(biomeRegistry, pair -> {
                Holder<Biome> holder = biomeRegistry.getHolder(pair.getSecond()).orElse(null);
                if (holder == null) return;  // 理论不可达：区域注册时已校验
                ResourceLocation biomeId = biomeId(holder);
                if (biomeId == null || !isAllowed(biomeId, extraIds)) return;
                if (seen.add(pair.getFirst() + "\u0000" + biomeId)) {
                    collected.add(Pair.of(pair.getFirst(), holder));
                }
            });
        }
        skippedTotal = skippedRegions;
        return collected;
    }

    /** 汇总日志 + 构造不可变参数表；空集时返回 null 并告警一次。 */
    private static Climate.ParameterList<Holder<Biome>> finish(
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected, String sourceName) {
        if (collected.isEmpty()) {
            if (!warnedEmpty) {
                warnedEmpty = true;
                BeLoongCore.LOGGER.warn(
                        "[beloong] disaster_biomes: no biomes collected via {} (allowedNamespaces={}, extraIds={});"
                                + " falling back to the previous dimension behavior. Install BWG/RU or add biome IDs to"
                                + " disaster_biomes.allowedBiomes",
                        sourceName, allowedNamespacesValue(), allowedBiomes());
            }
            return null;
        }
        // 统计与自检日志（冒烟验证锚点）。
        Set<String> byNamespace = new HashSet<>();
        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : collected) {
            ResourceLocation id = biomeId(entry.getSecond());
            if (id != null) byNamespace.add(id.getNamespace());
        }
        BeLoongCore.LOGGER.info(
                "[beloong] disaster_biomes: built parameter list from '{}' with {} biome points, namespaces={},"
                        + " allowedNamespaces={}, extraBiomes={}, skippedTBRegions={}",
                sourceName, collected.size(), byNamespace, allowedNamespacesValue(), allowedBiomes(), skippedTotal);
        return new Climate.ParameterList<>(List.copyOf(collected));
    }

    private static List<String> allowedNamespacesValue() {
        return Config.DisasterBiomes.allowedNamespaces.get().stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static boolean isNamespaceAllowed(String namespace) {
        return allowedNamespacesValue().contains(namespace);
    }

    private static List<String> allowedBiomes() {
        return Config.DisasterBiomes.allowedBiomes.get().stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 命名空间规则 ∨ 精确 ID 规则。 */
    private static boolean isAllowed(ResourceLocation biomeId, List<String> extraIds) {
        if (isNamespaceAllowed(biomeId.getNamespace())) return true;
        return extraIds.contains(biomeId.toString());
    }

    /**
     * 把 Holder 解析为群系 ID。注册表引用（本流程的所有来源）必含键；
     * 无法解析时返回 {@code null}，调用方按"不在白名单内"丢弃。
     */
    private static ResourceLocation biomeId(Holder<Biome> holder) {
        return holder.unwrapKey().map(ResourceKey::location).orElse(null);
    }
}
