package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Either;
import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
import com.zonlong.beloong.mixin.ChunkGeneratorBiomeSourceAccessor;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import terrablender.mixin.MultiNoiseBiomeSourceAccess;

/**
 * 天灾维度参数表延迟对齐。
 * <p>
 * 时序事实(实测日志 + Lithostitched 1.8.0b4 字节码):
 * <ul>
 *   <li>天灾维度构造期,共享预设参数表只有原版点——此时 Lithostitched 尚未把 RU 注入写进共享表;</li>
 *   <li>LH 的合并发生在主世界来源构造时(Either.right 分支会把"原值+注入对"经
 *       ParameterListAccessor.lithostitched$setValues 就地写回共享预设表,或 Either.left 写自己的合并表)
 *       ——同样在 createLevels 阶段,但维度顺序不保证先主世界后天灾;</li>
 *   <li>因此不能在天灾来源构造期一次性取全,必须把交换推迟到天灾维度 createState 完成、
 *       且主世界来源已就绪的时点——即 ChunkMapMixin 的 redirect 尾部调用本类的 swapNow。</li>
 * </ul>
 * 交换后 Either.left(过滤表) 装回天灾来源(未初始化参数表 → TB 最近邻匹配);
 * 并调用 refreshFeaturesPerStep() 使特性步骤按新群系集惰性重建,否则 RU 群系特性缺失。
 * 结构状态机的 placementsForStructure 是惰性(ensureStructuresGenerated),首次使用时才按交换后的群系集计算。
 * 天灾区块此时尚未生成,交换对世界生成安全。
 */
public final class DisasterBiomeSwapHandler {
    private DisasterBiomeSwapHandler() {}

    /** 在天灾维度 createState 完成后,用主世界的合并表重装过滤参数表。 */
    public static void swapNow(ChunkGenerator disasterGenerator, RegistryAccess registryAccess) {
        if (!Config.DisasterBiomes.enabled.get()) {
            BeLoongCore.LOGGER.info("[beloong] disaster_biomes: deferred swap skipped — disabled");
            return;
        }
        try {
            if (!(disasterGenerator instanceof ChunkGeneratorBiomeSourceAccessor disasterAcc)) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: deferred swap skipped — no biomeSource accessor");
                return;
            }
            if (!(disasterAcc.beloong$biomeSource() instanceof MultiNoiseBiomeSource disasterMnbs)) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: deferred swap skipped — disaster source not multi-noise: {}",
                        disasterAcc.beloong$biomeSource().getClass().getName());
                return;
            }

            // 无论构造期装了什么(shared+TB 的 BWG-only 表 / 原版表),此处一律用
            // 主世界合并表覆盖升级——构造期 Lithostitched 注入尚未写共享表,只有这里能拿到全量。
            Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> current =
                    ((MultiNoiseBiomeSourceAccess) disasterMnbs).getParameters();

            // 主世界来源的合并参数表(两侧均解析:left=LH合并表;right=被LH就地写过的共享预设表)
            var overworldStem = registryAccess.lookupOrThrow(Registries.LEVEL_STEM)
                    .get(LevelStem.OVERWORLD).orElse(null);
            if (overworldStem == null) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: deferred swap skipped — no overworld stem");
                return;
            }
            if (!(overworldStem.value().generator() instanceof ChunkGeneratorBiomeSourceAccessor accessor)) {
                BeLoongCore.LOGGER.info("[beloong] disaster_biomes: deferred swap skipped — overworld source unreadable");
                return;
            }
            Climate.ParameterList<Holder<Biome>> merged = resolveMerged(accessor.beloong$biomeSource());
            if (merged == null) {
                BeLoongCore.LOGGER.info(
                        "[beloong] disaster_biomes: deferred swap skipped — overworld source unresolved");
                return;
            }

            // 并集合并:以天灾现有表(构造期,含 TB 采集的 BWG 点)为基础,追加主世界合并表中
            // 天灾缺少的(点,群系)对——LH 注入的 RU 点由此进入,构造期已收的 BWG 点不重复不丢失。
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> collected = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // 1) 天灾现有表全部保留(它们已按白名单过滤过)
            current.left().ifPresent(currentList -> collected.addAll(currentList.values()));
            for (var pair : collected) {
                var key = pair.getSecond().unwrapKey().orElse(null);
                if (key != null) seen.add(pair.getFirst() + "\u0000" + key.location());
            }
            // 2) 主世界合并表里按白名单过滤后追加(按 点+群系 去重)
            for (var pair : DisasterBiomeSourceFactory.filterSharedPairs(merged.values())) {
                var key = pair.getSecond().unwrapKey().orElse(null);
                if (key != null && seen.add(pair.getFirst() + "\u0000" + key.location())) {
                    collected.add(pair);
                }
            }
            Climate.ParameterList<Holder<Biome>> filtered =
                    DisasterBiomeSourceFactory.finish(collected, "deferred-union-merged");
            if (filtered == null) return;

            ((MultiNoiseBiomeSourceAccess) disasterMnbs).setParameters(Either.left(filtered));
            disasterGenerator.refreshFeaturesPerStep();
            BeLoongCore.LOGGER.info(
                    "[beloong] disaster_biomes: deferred parameter swap installed at disaster createState;"
                            + " featuresPerStep refreshed");
        } catch (Exception e) {
            BeLoongCore.LOGGER.warn(
                    "[beloong] disaster_biomes: deferred swap failed, keeping constructor-time list", e);
        }
    }

    /** 解析主世界来源参数表:left=合并表;right=被就地写过的共享预设表(两者此时都含注入对)。
     *  主世界来源是 Lithostitched 的 InjectorBiomeSource(非 MNBS 子类)——先经反射解包其
     *  public directDelegate()/rootDelegate() 至 MNBS,再读参数表。 */
    @SuppressWarnings("unchecked")
    private static Climate.ParameterList<Holder<Biome>> resolveMerged(
            net.minecraft.world.level.biome.BiomeSource source) {
        if (source instanceof MultiNoiseBiomeSource mnbs) {
            return readParameters(mnbs);
        }
        // 解包(最多三层,与 DisasterBiomeSourceFactory.unwrapToMultiNoise 同策略)
        MultiNoiseBiomeSource inner = DisasterBiomeSourceFactory.unwrapToMultiNoise(source);
        if (inner == null) {
            BeLoongCore.LOGGER.info(
                    "[beloong] disaster_biomes: overworld source drill failed at {}",
                    source.getClass().getName());
            return null;
        }
        return readParameters(inner);
    }

    private static Climate.ParameterList<Holder<Biome>> readParameters(MultiNoiseBiomeSource mnbs) {
        Either<Climate.ParameterList<Holder<Biome>>, Holder<MultiNoiseBiomeSourceParameterList>> parameters =
                ((MultiNoiseBiomeSourceAccess) mnbs).getParameters();
        Climate.ParameterList<Holder<Biome>> resolved = parameters.map(
                left -> left,
                right -> (Climate.ParameterList<Holder<Biome>>) right.value().parameters());
        java.util.Set<String> ns = new java.util.HashSet<>();
        for (var pair : resolved.values()) {
            var key = pair.getSecond().unwrapKey().orElse(null);
            if (key != null) ns.add(key.location().getNamespace());
        }
        java.util.List<String> ruPoints = new java.util.ArrayList<>();
        for (var pair : resolved.values()) {
            var key = pair.getSecond().unwrapKey().orElse(null);
            if (key != null && key.location().getNamespace().equals("regions_unexplored"))
                ruPoints.add(key.location() + "@[" + pair.getFirst().temperature() + "]");
        }
        BeLoongCore.LOGGER.info(
                "[beloong] disaster_biomes: overworld resolved parameters: eitherSide={}, points={}, namespaces={}, ruPointsDetails={}",
                parameters.left().isPresent() ? "left" : "right", resolved.values().size(), ns, ruPoints);
        return resolved;
    }
}
