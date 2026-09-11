package com.zonlong.beloong.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.registries.Registries;

/**
 * 天灾维度「原版群系 → BWG 群系」气候对照表。
 *
 * <h3>存在意义</h3>
 * 天灾维度与主世界共用 {@code minecraft:overworld} 的 {@code multi_noise} preset，
 * 因此 {@code Climate.ParameterList.values} 就是原版 {@code OverworldBiomeBuilder} 的完整参数列表
 * （7593 个参数点）。TerraBlender 的 region 树在遇到 {@code Region.DEFERRED_PLACEHOLDER} 时
 * 会回退到这棵「index 0 兜底树」，于是原版群系会在天灾维度大量生成——实测原版点占比约 65%。
 *
 * <p>本表把兜底树里的原版群系整体换成气候最接近的 BWG 群系，从根上消除回退来源：
 * 不必逐一去填 BWG 自己的选择器数组（{@code OCEANS_BWG} 缺 60%、{@code SHATTERED_BIOMES_BWG} 缺 100%）。
 *
 * <h3>覆盖范围</h3>
 * 对原版参数空间基线（7593 参数点 / 53 群系）做全量比对：
 * <b>53 = 白名单 21 + 本表 32，双向集合相等、无遗漏、无多余、无交集</b>。
 * 32 个原版群系映射到 <b>25 个</b> BWG 群系。
 *
 * <h3>映射依据</h3>
 * 按 {@code run/exported_vanilla_biomes.json}（该导出为 BWG 接管<strong>之前</strong>的原版基线，
 * 5 个海洋温度带齐全）计算各原版群系的气候中心，在 BWG 的「温度带 × 湿度档」上取最近邻。
 * 同一 BWG 群系被多个原版群系复用是刻意的（如 {@code savanna} 与 {@code savanna_plateau}
 * 都归 {@code baobab_savanna}），因为本表只负责气候对应，不负责保持原版多样性。
 *
 * <h3>维护提示</h3>
 * <ol>
 *   <li>新增/修改条目后必须核对该 BWG 群系在 {@code config/biomeswevegone/world_generation.json}
 *       中为 {@code true}。被禁用的目标会被 {@link DisasterBiomeSubstitution} 判为不可用，
 *       结果是<b>该原版群系被保留</b>（无兜底群系）并在日志留 ERROR。</li>
 *   <li><b>优先选 BWG 默认启用的群系。</b>BWG 在 {@code BWGWorldGenConfig.getDefaultBiomes()}
 *       里硬编码禁用了 {@code biomeswevegone:eroded_borealis}，因此本表<b>刻意不使用它</b>——
 *       否则在未手工改过 BWG 配置的环境上会静默失效。</li>
 * </ol>
 *
 * <h3>距离指标说明</h3>
 * 条目按其原版群系中心与目标群系中心的 5 维归一化欧氏距离挑选。
 * 注意该指标对<b>跨度大的群系</b>会产生假阳性：中心可能落在它并不拥有的格上，
 * 因此"格命中"（温度带 × 湿度档是否重合）优先于中心距离。
 * 例如 {@code dark_forest} 与 {@code weeping_witch_forest} 中心完全重合（d=0.000），
 * 这比中心距离看似更近但格不重合的候选更可靠。
 *
 * @see DisasterBiomeSubstitution
 * @see com.zonlong.beloong.mixin.CloneParameterListMixin
 */
public final class DisasterBiomeMapping {

    /** BWG 命名空间。 */
    private static final String BWG = "biomeswevegone";

    private DisasterBiomeMapping() {
    }

    /**
     * 把原版群系映射为气候最接近的 BWG 群系。
     * <p>
     * 白名单群系（21 项，见 {@link DisasterBiomeSubstitution}）<strong>不会</strong>进入本表的
     * 任何 case，由调用方在替换前先行放行。
     *
     * @param vanilla 原版群系的资源位置
     * @return 对应的 BWG 群系键；非 minecraft 命名空间或本表未覆盖时返回 {@code null}
     */
    public static ResourceKey<Biome> substitute(ResourceLocation vanilla) {
        if (vanilla == null || !"minecraft".equals(vanilla.getNamespace())) {
            return null;
        }
        String bwgName = switch (vanilla.getPath()) {
            // ---------- 温带内陆 ----------
            case "plains" -> "prairie";
            // 干旱中性带；BWG 自己在 MIDDLE_BIOMES_BWG[2][0] 用的就是 prairie
            case "sunflower_plains" -> "prairie";
            case "forest" -> "temperate_grove";
            case "flower_forest" -> "rose_fields";
            case "birch_forest" -> "aspen_boreal";
            case "old_growth_birch_forest" -> "aspen_boreal";
            // 原版独占 NEUTRAL/HUMID；weeping_witch_forest 同格，中心完全重合
            case "dark_forest" -> "weeping_witch_forest";

            // ---------- 寒带 / 冰带内陆 ----------
            case "snowy_plains" -> "crimson_tundra";
            case "ice_spikes" -> "shattered_glacier";
            case "snowy_taiga" -> "frosted_taiga";
            case "taiga" -> "coniferous_forest";
            case "old_growth_pine_taiga" -> "frosted_coniferous_forest";
            case "old_growth_spruce_taiga" -> "frosted_coniferous_forest";
            // 覆雪山林。刻意避开 BWG 默认禁用的 eroded_borealis
            case "grove" -> "frosted_taiga";
            case "meadow" -> "coconino_meadow";

            // ---------- 山地 / 峰 / 坡 ----------
            case "snowy_slopes" -> "howling_peaks";
            case "frozen_peaks" -> "howling_peaks";
            case "jagged_peaks" -> "howling_peaks";

            // ---------- 暖带 / 热带内陆 ----------
            case "savanna" -> "baobab_savanna";
            case "savanna_plateau" -> "baobab_savanna";
            case "jungle" -> "jacaranda_jungle";
            case "sparse_jungle" -> "fragment_jungle";
            case "bamboo_jungle" -> "tropical_rainforest";
            case "desert" -> "mojave_desert";
            case "badlands" -> "rugged_badlands";
            case "eroded_badlands" -> "sierra_badlands";
            case "wooded_badlands" -> "red_rock_valley";
            case "cherry_grove" -> "sakura_grove";

            // ---------- 沼泽 ----------
            case "swamp" -> "bayou";
            case "mangrove_swamp" -> "cypress_swamplands";

            // ---------- 海岸 ----------
            // basalt_barrera 覆盖 NEUTRAL 带全部湿度格，与 beach 中心距离 0.013
            case "beach" -> "basalt_barrera";

            // ---------- 特殊 ----------
            case "mushroom_fields" -> "crag_gardens";

            // 走到这里说明出现了本表未覆盖的原版群系（例如 MC 升级）。返回 null 让调用方
            // 保留原版群系并记 ERROR，不要在这里抛异常。
            default -> null;
        };
        if (bwgName == null) {
            return null;
        }
        return ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath(BWG, bwgName));
    }
}
