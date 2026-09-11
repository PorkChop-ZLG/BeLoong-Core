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
 * <h3>覆盖范围（第二阶段起）</h3>
 * 对原版参数空间基线（7593 参数点 / 53 群系）做全量比对：本表<strong>覆盖全部 53 个</strong>
 * {@code minecraft:} 群系，<b>不再有白名单例外</b>（{@link DisasterBiomeSubstitution#WHITELIST} 已清空）。
 * <p>
 * 目标分三类：
 * <ol>
 *   <li><b>{@code beloong:} 自制群系（14 项 → 5 个）</b>：海洋 2、河流 1、洞穴 1、碎裂地形 1。
 *       这些位置 BWG 结构上确实给不出替代（见逐项表）</li>
 *   <li><b>{@code biomeswevegone:} 交给 BWG（7 项）</b>：暖/热海洋、碎石海岸、风袭热带草原、
 *       暖带裸岩峰、冰海滩</li>
 *   <li><b>原有的 32 项气候对照</b></li>
 * </ol>
 *
 * <h3>维护提示</h3>
 * <ol>
 *   <li>新增/修改条目后必须核对该 BWG 群系在 {@code config/biomeswevegone/world_generation.json}
 *       中为 {@code true}。被禁用的目标会被 {@link DisasterBiomeSubstitution} 判为不可用，
 *       结果是<b>该原版群系被保留</b>（无兜底群系）并在日志留 ERROR。</li>
 *   <li><b>优先选 BWG 默认启用的群系。</b>BWG 在 {@code BWGWorldGenConfig.getDefaultBiomes()}
 *       里硬编码禁用了 {@code biomeswevegone:eroded_borealis}，因此本表<b>刻意不使用它</b>——
 *       否则在未手工改过 BWG 配置的环境上会静默失效。</li>
 *   <li><b>⚠️ 选 BWG 目标时必须核对该群系有没有 BWG 地表规则。</b>
 *       BWG 只给 55 个群系中的 42 个写了规则（{@code BWGOverworldSurfaceRules.makeRules()}）；
 *       没有规则的群系会落到原版规则，而原版规则按 {@code minecraft:} ID 分支、一条都不命中
 *       ⇒ 掉到默认<b>草/土</b>。因此"地表本身就是其身份"的群系（如 {@code stony_peaks} 的方解石）
 *       绝不能指向无规则的 BWG 群系。已核对清单见逐项表 §6.2。</li>
 * </ol>
 *
 * <h3>距离指标说明</h3>
 * 早期的 32 项气候对照条目按其原版群系中心与目标群系中心的 5 维归一化欧氏距离挑选。
 * 注意该指标对<b>跨度大的群系</b>会产生假阳性：中心可能落在它并不拥有的格上，
 * 因此"格命中"（温度带 × 湿度档是否重合）优先于中心距离。
 * 例如 {@code dark_forest} 与 {@code weeping_witch_forest} 中心完全重合（d=0.000），
 * 这比中心距离看似更近但格不重合的候选更可靠。
 *
 * @see DisasterBiomeSubstitution
 * @see com.zonlong.beloong.mixin.CloneParameterListMixin
 */
public final class DisasterBiomeMapping {

    private DisasterBiomeMapping() {
    }

    /**
     * 把原版群系映射为天灾维度应使用的群系。
     * <p>
     * <b>白名单已清空</b>（第二阶段）：21 个白名单原版群系全部有了目标，因此本表必须覆盖
     * 原版参数空间里出现的<strong>每一个</strong> {@code minecraft:} 群系。
     * 覆盖不全会让 {@code filter()} 走"保留原版 + 记 ERROR"分支，日志出现「未能求解 &gt; 0」。
     * <p>
     * 目标是<strong>完整资源位置</strong>（含命名空间），因此可以是
     * {@code biomeswevegone:*} 或 {@code beloong:*}。自制群系清单的唯一来源是
     * {@link DisasterBiomeSubstitution#CUSTOM_BIOMES}。
     *
     * @param vanilla 原版群系的资源位置
     * @return 目标群系键；非 minecraft 命名空间或本表未覆盖时返回 {@code null}
     */
    public static ResourceKey<Biome> substitute(ResourceLocation vanilla) {
        if (vanilla == null || !"minecraft".equals(vanilla.getNamespace())) {
            return null;
        }
        String target = switch (vanilla.getPath()) {
            // ---------- 温带内陆 ----------
            case "plains" -> "biomeswevegone:prairie";
            // 干旱中性带；BWG 自己在 MIDDLE_BIOMES_BWG[2][0] 用的就是 prairie
            case "sunflower_plains" -> "biomeswevegone:prairie";
            case "forest" -> "biomeswevegone:temperate_grove";
            case "flower_forest" -> "biomeswevegone:rose_fields";
            case "birch_forest" -> "biomeswevegone:aspen_boreal";
            case "old_growth_birch_forest" -> "biomeswevegone:aspen_boreal";
            // 原版独占 NEUTRAL/HUMID；weeping_witch_forest 同格，中心完全重合
            case "dark_forest" -> "biomeswevegone:weeping_witch_forest";

            // ---------- 寒带 / 冰带内陆 ----------
            case "snowy_plains" -> "biomeswevegone:crimson_tundra";
            case "ice_spikes" -> "biomeswevegone:shattered_glacier";
            case "snowy_taiga" -> "biomeswevegone:frosted_taiga";
            case "taiga" -> "biomeswevegone:coniferous_forest";
            case "old_growth_pine_taiga" -> "biomeswevegone:frosted_coniferous_forest";
            case "old_growth_spruce_taiga" -> "biomeswevegone:frosted_coniferous_forest";
            // 覆雪山林。刻意避开 BWG 默认禁用的 eroded_borealis
            case "grove" -> "biomeswevegone:frosted_taiga";
            case "meadow" -> "biomeswevegone:coconino_meadow";

            // ---------- 山地 / 峰 / 坡 ----------
            case "snowy_slopes" -> "biomeswevegone:howling_peaks";
            case "frozen_peaks" -> "biomeswevegone:howling_peaks";
            case "jagged_peaks" -> "biomeswevegone:howling_peaks";

            // ---------- 暖带 / 热带内陆 ----------
            case "savanna" -> "biomeswevegone:baobab_savanna";
            case "savanna_plateau" -> "biomeswevegone:baobab_savanna";
            case "jungle" -> "biomeswevegone:jacaranda_jungle";
            case "sparse_jungle" -> "biomeswevegone:fragment_jungle";
            case "bamboo_jungle" -> "biomeswevegone:tropical_rainforest";
            case "desert" -> "biomeswevegone:mojave_desert";
            case "badlands" -> "biomeswevegone:rugged_badlands";
            case "eroded_badlands" -> "biomeswevegone:sierra_badlands";
            case "wooded_badlands" -> "biomeswevegone:red_rock_valley";
            case "cherry_grove" -> "biomeswevegone:sakura_grove";

            // ---------- 沼泽 ----------
            case "swamp" -> "biomeswevegone:bayou";
            case "mangrove_swamp" -> "biomeswevegone:cypress_swamplands";

            // ---------- 海岸 ----------
            // basalt_barrera 覆盖 NEUTRAL 带全部湿度格，与 beach 中心距离 0.013
            case "beach" -> "biomeswevegone:basalt_barrera";

            // ---------- 特殊 ----------
            // ⚠️ 已知例外：原版 mushroom_fields 的地表是 MYCELIUM（SurfaceRuleData.java:178），
            // 而 crag_gardens **没有** BWG 地表规则 ⇒ 该地表会丢失、掉到默认草/土。
            // 这是第一阶段就有的映射，本轮未改：BWG 没有语义更贴的"菌丝"群系，
            // 换任何一个候选都只是换个不搭法。保留并在此显式记录。
            case "mushroom_fields" -> "biomeswevegone:crag_gardens";

            // =====================================================================
            // 以下为第二阶段接管的白名单 21 项。
            // 定案见 docs/plans/2026-09-11-disaster-phase2-biome-table.md
            // =====================================================================

            // ---------- 自制：海洋 ----------
            // 原版 9 个海洋中只有 frozen 系列温度是 0.0，其余 7 个全是 0.5
            case "frozen_ocean" -> "beloong:frozen_ocean";
            case "deep_frozen_ocean" -> "beloong:frozen_ocean";
            case "cold_ocean" -> "beloong:ocean";
            case "deep_cold_ocean" -> "beloong:ocean";
            case "ocean" -> "beloong:ocean";
            case "deep_ocean" -> "beloong:ocean";
            // 暖/热两列的格子 BWG 本来就填了东西，交给它
            case "lukewarm_ocean" -> "biomeswevegone:lush_stacks";
            case "deep_lukewarm_ocean" -> "biomeswevegone:lush_stacks";
            case "warm_ocean" -> "biomeswevegone:dead_sea";

            // ---------- 自制：河流 ----------
            // BWG 完全没有河流群系。只做 1 个 ⇒ 冻河不再结冰（已接受）
            case "river" -> "beloong:river";
            case "frozen_river" -> "beloong:river";

            // ---------- 自制：洞穴 ----------
            // BWG 没有任何 depth > 0 的群系。合并成 1 个，基底 lush_caves
            case "lush_caves" -> "beloong:caves";
            case "dripstone_caves" -> "beloong:caves";
            case "deep_dark" -> "beloong:caves";

            // ---------- 自制：碎裂地形 ----------
            // 三者 JSON 本就相同，差别只在地表规则；自制以保住"碎裂丘陵"地貌
            case "windswept_hills" -> "beloong:windswept";
            case "windswept_gravelly_hills" -> "beloong:windswept";
            case "windswept_forest" -> "beloong:windswept";

            // ---------- 交给 BWG：海岸 / 峰 / 海滩 ----------
            // 有同格或近格的 BWG 群系。**除 araucaria_savanna 外都带 BWG 地表规则**
            // （见逐项表 §6.2）；araucaria_savanna 没有规则，但原版 windswept_savanna 的
            // 规则（噪声 1.75 以上露石头）影响很小，属可接受例外。
            case "stony_shore" -> "biomeswevegone:basalt_barrera";
            case "windswept_savanna" -> "biomeswevegone:araucaria_savanna";
            // 有地表规则（红岩峰）；crag_gardens 温度更接近但**没有地表规则**，会变草坡
            case "stony_peaks" -> "biomeswevegone:red_rock_peaks";
            // 规则产出 WHITE_SAND + WHITE_DACITE，与沙质冷海岸吻合
            case "snowy_beach" -> "biomeswevegone:dacite_shore";

            // 走到这里说明出现了本表未覆盖的原版群系（例如 MC 升级）。返回 null 让调用方
            // 保留原版群系并记 ERROR，不要在这里抛异常。
            default -> null;
        };
        if (target == null) {
            return null;
        }
        return ResourceKey.create(Registries.BIOME, ResourceLocation.parse(target));
    }
}
