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
 * 对 {@code run/exported_vanilla_biomes.json}（7593 参数点 / 53 群系）做全量比对：
 * <b>53/53 全覆盖，无遗漏</b>。其中 14 个由
 * {@link com.zonlong.beloong.mixin.DisasterBiomeSubstitution} 的白名单保留原版，
 * 其余 <b>39 个</b>映射到 <b>31 个</b> BWG 群系。
 *
 * <h3>映射依据</h3>
 * 按 {@code run/exported_vanilla_biomes.json}（该导出为 BWG 接管<strong>之前</strong>的原版基线，
 * 5 个海洋温度带齐全）计算各原版群系的气候中心，在 BWG 的「温度带 × 湿度档」上取最近邻。
 * 同一 BWG 群系被多个原版群系复用是刻意的（如 {@code savanna} 与 {@code savanna_plateau}
 * 都归 {@code baobab_savanna}），因为本表只负责气候对应，不负责保持原版多样性。
 *
 * <h3>维护提示</h3>
 * 新增/修改条目后必须核对该 BWG 群系在 {@code config/biomeswevegone/world_generation.json}
 * 中为 {@code true}——被禁用的目标会被 {@code DisasterBiomeSubstitution} 判为不可用并降级到兜底群系。
 * 当前 31 个目标全部启用。
 *
 * @see com.zonlong.beloong.mixin.DisasterBiomeSubstitution
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
     * 白名单群系（海洋 9 / 河流 2 / 洞穴 3）<strong>不会</strong>进入本表的任何 case，
     * 由调用方在替换前先行放行；本表返回 {@code null} 仅表示「本表未覆盖」，
     * 调用方应降级到兜底群系。
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
            case "sunflower_plains" -> "amaranth_grassland";
            case "forest" -> "temperate_grove";
            case "flower_forest" -> "rose_fields";
            case "birch_forest" -> "aspen_boreal";
            case "old_growth_birch_forest" -> "aspen_boreal";
            case "dark_forest" -> "black_forest";

            // ---------- 寒带 / 冰带内陆 ----------
            case "snowy_plains" -> "crimson_tundra";
            case "ice_spikes" -> "shattered_glacier";
            case "snowy_taiga" -> "frosted_taiga";
            case "taiga" -> "coniferous_forest";
            case "old_growth_pine_taiga" -> "frosted_coniferous_forest";
            case "old_growth_spruce_taiga" -> "frosted_coniferous_forest";
            case "grove" -> "eroded_borealis";
            case "meadow" -> "coconino_meadow";

            // ---------- 山地 / 峰 / 坡 ----------
            case "snowy_slopes" -> "howling_peaks";
            case "frozen_peaks" -> "howling_peaks";
            case "jagged_peaks" -> "howling_peaks";
            case "stony_peaks" -> "dacite_ridges";
            case "windswept_hills" -> "dacite_ridges";
            case "windswept_gravelly_hills" -> "canadian_shield";
            case "windswept_forest" -> "black_forest";

            // ---------- 暖带 / 热带内陆 ----------
            case "savanna" -> "baobab_savanna";
            case "savanna_plateau" -> "baobab_savanna";
            case "windswept_savanna" -> "firecracker_chaparral";
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
            case "beach" -> "dacite_shore";
            case "snowy_beach" -> "basalt_barrera";
            case "stony_shore" -> "basalt_barrera";

            // ---------- 特殊 ----------
            case "mushroom_fields" -> "crag_gardens";

            // 白名单以外的任何原版群系若落到这里，说明 exported_vanilla_biomes.json
            // 出现了本表编写时未见过的新群系（例如原版更新）。返回 null 让调用方
            // 降级到 fallbackBiome 并打 WARN，不要在这里抛异常。
            default -> null;
        };
        if (bwgName == null) {
            return null;
        }
        return ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath(BWG, bwgName));
    }
}
