package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 天灾维度的群系替换求解器。
 * <p>
 * 由 {@link com.zonlong.beloong.mixin.CloneParameterListMixin} 在 TerraBlender 初始化参数列表
 * <strong>之前</strong>调用，把 {@link Climate.ParameterList} 里的原版群系替换成 BWG 群系，
 * 从而实现两个层面的目标：
 * <ol>
 *   <li><b>生成层</b>：天灾维度不再生成黑名单原版群系</li>
 *   <li><b>查询层</b>：这些群系从 {@code BiomeSource.possibleBiomes()} 中消失，
 *       {@code /locate biome} 搜不到、自然罗盘只把它们显示在主世界</li>
 * </ol>
 * 两层是<strong>两条独立路径</strong>，需分别处理——生成层见
 * {@link com.zonlong.beloong.mixin.CloneParameterListMixin}，
 * 查询层见 {@link com.zonlong.beloong.mixin.PossibleBiomesFilterMixin}。
 *
 * <h3>无配置项</h3>
 * 本功能<strong>不提供任何配置开关</strong>，也<strong>没有兜底群系配置</strong>。
 * 白名单与映射表都是结构性决策（由参数空间的形状决定），硬编码在此与
 * {@link DisasterBiomeMapping}。映射目标不可用时一律<b>保留原版群系并记 ERROR</b>——
 * 这比"换成一个语义不搭的兜底群系"更安全。
 *
 * <h3>白名单：刻意保留的 21 个原版群系</h3>
 * 判据统一为「<b>BWG 在结构上不覆盖该参数区</b>」：
 * <table border="1">
 *   <tr><th>类别</th><th>数量</th><th>BWG 为何不覆盖</th></tr>
 *   <tr><td>海洋</td><td>9</td><td>BWG 只填了暖/热两列（{@code lush_stacks} / {@code dead_sea}），
 *       寒/冷/中性三列（占 60%）主动留空</td></tr>
 *   <tr><td>河流</td><td>2</td><td>BWG 完全没有河流群系（源码内 {@code river} 零命中，
 *       也不声明 {@code minecraft:is_river} 标签）</td></tr>
 *   <tr><td>洞穴</td><td>3</td><td>BWG 不注册任何 {@code depth > 0} 的群系，
 *       而原版洞穴群系占 {@code depth ≥ 0.2}</td></tr>
 *   <tr><td>碎裂地形</td><td>3</td><td>BWG 的 {@code SHATTERED_BIOMES_BWG} 与
 *       {@code SHATTERED_BIOMES_TERRABLENDER} 两个数组 <b>25 格全空</b></td></tr>
 *   <tr><td>暖带裸岩峰</td><td>1</td><td>BWG 的 {@code PEAK_BIOMES_BWG} 对 WARM 带最干旱两格
 *       主动 {@code DEFERRED} 回原版</td></tr>
 *   <tr><td>冰海滩</td><td>1</td><td>BWG 的 {@code BEACH_BIOMES_BWG} ICY 行全 {@code DEFERRED}</td></tr>
 *   <tr><td>其他硬编码位置</td><td>2</td><td>{@code stony_shore} / {@code windswept_savanna} 由
 *       {@code OverworldBiomeBuilder} 中<b>未被 TerraBlender 覆写</b>的私有方法产生</td></tr>
 * </table>
 * 这 19 项是当前阶段的已知缺口，计划由 {@code beloong:} 命名空间的自制群系接管，
 * 详见 {@code docs/天灾维度总设计.md} 第十节。
 *
 * @see DisasterBiomeMapping
 * @see com.zonlong.beloong.mixin.CloneParameterListMixin
 * @see com.zonlong.beloong.mixin.PossibleBiomesFilterMixin
 */
public final class DisasterBiomeSubstitution {

    /** BWG 的模组 ID。 */
    private static final String BWG_MOD_ID = "biomeswevegone";

    /** BWG 命名空间。 */
    private static final String BWG_NAMESPACE = "biomeswevegone";

    /** 原版命名空间。 */
    private static final String VANILLA_NAMESPACE = "minecraft";

    /**
     * BWG 世界生成配置类的全限定名。
     * <p>
     * 刻意写成字符串而非直接 import——见 {@link #isBwgEnabled(ResourceLocation)} 的说明。
     */
    private static final String BWG_WORLDGEN_CONFIG_CLASS =
            "net.potionstudios.biomeswevegone.config.configs.BWGWorldGenConfig";

    /** 目标维度 ID。 */
    private static final ResourceLocation TARGET_DIMENSION =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster");

    /**
     * 「本模组在本进程内是否真的对天灾维度做过替换」。
     * <p>
     * 由 {@link #filter} 设置，供 {@link com.zonlong.beloong.mixin.PossibleBiomesFilterMixin}
     * 判断是否应该过滤查询层的 {@code possibleBiomes()}。
     * <p>
     * <b>为什么需要这个标志：</b>查询层与生成层是两条独立路径。若替换实际未发生
     * （例如全部映射目标都被 BWG 配置禁用），却仍然过滤查询层，就会出现
     * 「群系照常生成，但 {@code /locate} 搜不到、结构集被剔除」的不一致状态。
     * 让两层共用同一个「是否真的生效」信号可以避免这一点。
     * <p>
     * <b>已知局限（多人游戏客户端）</b>：该标志由服务端初始化时置位，因此
     * <b>专用服务器的客户端不成立</b>——那里回退为本模组修复前的行为
     * （自然罗盘的列表会显示原版群系在天灾维度，但实际生成与 {@code /locate}
     * 不受影响）。<b>这是刻意维持的现状，不修复。</b>
     */
    private static volatile boolean substitutionApplied = false;

    /**
     * 刻意保留在天灾维度的原版群系白名单（21 项）。
     * <p>
     * 判据：<b>BWG 在结构上不覆盖该参数区</b>。详见类文档的表格。
     */
    private static final Set<String> WHITELIST = Set.of(
            // ---- 海洋（9）：BWG 只填了暖/热两列，寒/冷/中性三列主动留空 ----
            "frozen_ocean", "deep_frozen_ocean",
            "cold_ocean", "deep_cold_ocean",
            "ocean", "deep_ocean",
            "lukewarm_ocean", "deep_lukewarm_ocean",
            "warm_ocean",
            // ---- 河流（2）：BWG 完全没有河流群系 ----
            "river", "frozen_river",
            // ---- 洞穴（3）：BWG 不注册任何 depth > 0 的群系 ----
            "lush_caves", "dripstone_caves", "deep_dark",
            // ---- 碎裂地形（3）：BWG 的 SHATTERED_BIOMES 数组 25 格全空 ----
            "windswept_hills", "windswept_gravelly_hills", "windswept_forest",
            // ---- 暖带裸岩峰（1）：BWG 对 WARM 带最干旱两格主动 defer ----
            "stony_peaks",
            // ---- 冰海滩（1）：BWG 的 BEACH_BIOMES_BWG ICY 行全 defer ----
            "snowy_beach",
            // ---- 其他硬编码位置（2）：位于未被 TerraBlender 覆写的私有方法中 ----
            "stony_shore", "windswept_savanna"
    );

    private DisasterBiomeSubstitution() {
    }

    /**
     * 本进程内是否真的对天灾维度做过替换。
     *
     * @return true 表示生成层替换确有发生
     */
    public static boolean isSubstitutionApplied() {
        return substitutionApplied;
    }

    /**
     * 判断某个 LevelStem 是否为本方案的目标维度（{@code beloong:disaster}）。
     * <p>
     * <b>为什么必须做这个判断：</b>{@code CloneParameterListMixin} 重定向的
     * {@code LevelUtils.initializeBiomes} 对<strong>所有</strong>被 TerraBlender 判定为
     * {@code RegionType.OVERWORLD} 的维度都会触发。实测 {@code minecraft:the_nether}
     * 也会命中该调用点，若不限定维度，下界的 5 个群系（{@code nether_wastes} /
     * {@code soul_sand_valley} / {@code crimson_forest} / {@code warped_forest} /
     * {@code basalt_deltas}）会被替换成 BWG 地表群系——已由实机日志确认会发生。
     * <p>
     * 主世界不需要在这里排除：本模组用自己的 datapack 以 {@code "replace": true} 覆盖了
     * {@code terrablender:overworld_regions}，只保留 {@code beloong:disaster}，
     * 因此主世界的 {@code getRegionTypeForDimension} 返回 {@code null}，
     * {@code initializeBiomes} 在调用点之前就已 return。
     * <p>
     * <b>副作用提示</b>：该覆盖的全局后果是<b>主世界彻底退出 TerraBlender 管理</b>
     * （BWG 群系不在主世界生成）。这是设计意图，不是缺陷。
     *
     * @param levelKey 当前正在初始化的 LevelStem 键
     * @return true 表示目标维度
     */
    public static boolean isTargetDimension(ResourceKey<LevelStem> levelKey) {
        return levelKey != null && TARGET_DIMENSION.equals(levelKey.location());
    }

    /**
     * 判断某个原版群系是否在白名单内（应保留在天灾维度）。
     *
     * @param vanillaId 原版群系的资源位置
     * @return true 表示保留
     */
    public static boolean isWhitelisted(ResourceLocation vanillaId) {
        return VANILLA_NAMESPACE.equals(vanillaId.getNamespace())
                && WHITELIST.contains(vanillaId.getPath());
    }

    /**
     * 判断某个群系是否应当从天灾维度<strong>剔除</strong>——即「原版命名空间 且 不在白名单」。
     * <p>
     * 供查询路径过滤（{@code possibleBiomes()}）与生成路径替换共用同一判定，
     * 避免两处口径漂移。
     *
     * @param id 群系资源位置
     * @return true 表示应剔除
     */
    public static boolean isBlocklisted(ResourceLocation id) {
        return VANILLA_NAMESPACE.equals(id.getNamespace()) && !isWhitelisted(id);
    }

    /**
     * 对参数列表做替换，返回替换后的列表。
     * <p>
     * <b>无开关、无兜底</b>：本方法总是执行替换（没有配置可以跳过它）。对每个条目：
     * <ul>
     *   <li>非 {@code minecraft:} 命名空间 → 原样保留</li>
     *   <li>命中白名单 → 原样保留</li>
     *   <li>映射表命中且目标可用 → 替换为 BWG 群系</li>
     *   <li>映射表未覆盖 / 目标不可用 → <b>保留原版群系并记 ERROR</b></li>
     * </ul>
     * 单条处理失败不会中断整体（逐条 try/catch），失败条目计为 {@code unsolved} 并保留原版。
     * <p>
     * <b>返回值语义</b>：始终返回<b>新列表</b>（不做"是否与原列表同一实例"的优化判断），
     * 由调用方无条件写回。真正的"是否生效"由 {@link #isSubstitutionApplied()} 表达。
     *
     * @param registryAccess 服务器注册表访问（群系注册表由此取得，不使用
     *                       {@code BuiltInRegistries.BIOME}——该静态字段在本项目的
     *                       ModDevGradle 生产映射下不可用）
     * @param original       从共享 {@link Climate.ParameterList} 克隆出来的原始条目序列
     * @return 替换后的新列表（始终非 null）
     */
    public static List<Pair<Climate.ParameterPoint, Holder<Biome>>> filter(
            RegistryAccess registryAccess,
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> original) {

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> out =
                new ArrayList<>(original.size());
        int replaced = 0;
        int unsolved = 0;

        Registry<Biome> biomeRegistry;
        try {
            biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：无法取得群系注册表，本次不替换", t);
            substitutionApplied = false;
            return original;
        }

        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : original) {
            Pair<Climate.ParameterPoint, Holder<Biome>> current = entry;
            try {
                Optional<ResourceKey<Biome>> keyOpt = entry.getSecond().unwrapKey();
                if (keyOpt.isPresent() && isBlocklisted(keyOpt.get().location())) {
                    ResourceLocation from = keyOpt.get().location();
                    ResourceKey<Biome> resolved = resolveTarget(biomeRegistry, from);
                    if (resolved != null) {
                        Optional<Holder.Reference<Biome>> holder = biomeRegistry.getHolder(resolved);
                        if (holder.isPresent()) {
                            current = Pair.of(entry.getFirst(), (Holder<Biome>) holder.get());
                            replaced++;
                        } else {
                            unsolved++;
                        }
                    } else {
                        unsolved++;
                    }
                }
            } catch (Throwable t) {
                // 单条失败不影响整体：保留原版群系，仅计数
                unsolved++;
                BeLoongCore.LOGGER.error(
                        "[BeLoong] 天灾群系替换：单条处理失败，该参数点保留原版群系", t);
            }
            out.add(current);
        }

        substitutionApplied = replaced > 0;

        // 逐点完整：替换 + 白名单保留 + 未能求解 == 总数
        BeLoongCore.LOGGER.info(
                "[BeLoong] 天灾维度群系替换：参数点 {} 个 = 替换 {} + 白名单保留 {} + 未能求解 {}"
                        + "（替换{}生效）",
                out.size(), replaced, out.size() - replaced - unsolved, unsolved,
                substitutionApplied ? "" : "未");
        if (unsolved > 0) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：有 {} 个参数点未能求解，这些位置会保留原版群系。"
                            + "常见原因：映射目标被 BWG 的 world_generation.json 禁用，"
                            + "或映射表未覆盖该群系", unsolved);
        }

        return out;
    }

    /**
     * 解析某个原版群系在天灾维度的替换目标。
     * <p>
     * 无兜底：映射表未覆盖或目标不可用时返回 {@code null}，由调用方保留原版群系。
     *
     * @param registry 群系注册表
     * @param from     被替换的原版群系
     * @return 可用的目标群系键；不可用时返回 null
     */
    private static ResourceKey<Biome> resolveTarget(Registry<Biome> registry, ResourceLocation from) {
        ResourceKey<Biome> mapped = DisasterBiomeMapping.substitute(from);
        if (mapped == null) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：{} 在映射表中没有对应项，该群系将保留原版", from);
            return null;
        }
        if (!isUsable(registry, mapped)) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：{} 的目标 {} 不可用（未注册或被 BWG 配置禁用），"
                            + "该群系将保留原版", from, mapped.location());
            return null;
        }
        return mapped;
    }

    /**
     * 判断一个群系键是否可用于天灾维度：必须已注册，且若属于 BWG 则必须在其配置中启用。
     * <p>
     * BWG 的 {@code BWGTerraBlenderRegion.addBiomes} 会把被禁用的群系改写为
     * {@code Region.DEFERRED_PLACEHOLDER}，说明 BWG 自己都回避使用它们；这里做同样的回避，
     * 否则会往参数列表里塞进一个 BWG 主动弃用的群系。
     * <p>
     * 用 {@code getHolder} 而非 {@code containsKey}：NeoForge 的 {@code MappedRegistry}
     * 会对未注册的位置做 registry alias 解析（{@code BaseMappedRegistry#resolve}），
     * 两者对"可用"的定义因此并不相同；统一走 {@code getHolder} 消除这个不对称。
     *
     * @param registry 群系注册表
     * @param key      待检查的群系键
     * @return true 表示可用
     */
    private static boolean isUsable(Registry<Biome> registry, ResourceKey<Biome> key) {
        if (registry.getHolder(key).isEmpty()) {
            return false;
        }
        return isBwgEnabled(key.location());
    }

    /**
     * 查询 BWG 配置中某个群系是否启用。
     * <p>
     * <b>为什么用反射而不是直接 import {@code BWGWorldGenConfig}：</b>
     * 直接引用其类会让包含该引用的方法在<strong>类校验期</strong>就解析
     * {@code BWGWorldGenConfig}，BWG 缺席时抛出 {@code NoClassDefFoundError}——这个错误发生在
     * 进入方法体之前，外层的 {@code try/catch} 捕获不到，会导致 Mixin 应用失败。
     * 反射把类解析推迟到运行期，从而让 {@code try/catch} 真正生效。
     * <p>
     * 读不到配置时保守返回 {@code false}（按禁用处理），结果是该群系保留原版并记 ERROR——
     * 宁可保留原版，也不要往参数列表里塞一个 BWG 主动弃用的群系。
     * <p>
     * 注意 BWG 现在是 {@code required} 依赖，因此正常情况下不应出现"读不到"。
     * 一旦出现即为异常状况，故按 ERROR 级别记录。
     *
     * @param id 群系资源位置
     * @return true 表示启用、或不由本模组判断（非 BWG 命名空间）
     */
    private static boolean isBwgEnabled(ResourceLocation id) {
        if (!BWG_NAMESPACE.equals(id.getNamespace())) {
            return true;
        }
        if (!ModList.get().isLoaded(BWG_MOD_ID)) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：BWG（{}）未加载——这是硬依赖，功能无法工作", BWG_MOD_ID);
            return false;
        }
        try {
            Class<?> configClass = Class.forName(BWG_WORLDGEN_CONFIG_CLASS);
            Object instance = configClass.getField("INSTANCE").get(null);
            if (instance == null) {
                return false;
            }
            Object biomes = configClass.getField("biomes").get(instance);
            if (!(biomes instanceof Map<?, ?> map)) {
                return false;
            }
            return !Boolean.FALSE.equals(map.get(id));
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] 天灾群系替换：无法读取 BWG 群系配置（{}），"
                            + "相关目标将一律保留原版群系", t.toString());
            return false;
        }
    }
}
