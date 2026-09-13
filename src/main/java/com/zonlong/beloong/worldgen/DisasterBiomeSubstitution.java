package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.dimension.LevelStem;
import net.neoforged.fml.ModList;
import terrablender.api.Region;
import terrablender.api.RegionType;
import terrablender.api.Regions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
 * <h3>三条注入路径（第二阶段起）</h3>
 * 改写发生在三个位置，<strong>共用同一套判据与映射</strong>（{@link #rewriteKey}）：
 * <ol>
 *   <li><b>index 0 兜底树</b> —— {@link com.zonlong.beloong.mixin.CloneParameterListMixin}
 *       （生成层）</li>
 *   <li><b>查询层</b> —— {@link com.zonlong.beloong.mixin.PossibleBiomesFilterMixin}</li>
 *   <li><b>BWG region 树</b> —— {@link com.zonlong.beloong.mixin.BwgRegionBiomeRewriteMixin}。
 *       <b>第二阶段新增</b>：TerraBlender 为每个 region 各建一棵 RTree，且
 *       {@code findValuePositional} <b>先查 region 树</b>，只有拿到
 *       {@code DEFERRED_PLACEHOLDER} 才回退到 index 0。<b>不加这条，被 region 树
 *       直接吐出的群系（河流 / 洞穴 / {@code stony_shore} / {@code windswept_savanna}）
 *       就无法接管</b>，且第一阶段会有 5 项黑名单群系持续泄漏</li>
 * </ol>
 * 完整取证见 {@code docs/reviews/2026-09-11-disaster-region-tree-probe.md}。
 *
 * <h3>白名单：已清空</h3>
 * 第一阶段曾保留 21 个原版群系（判据：BWG 在结构上不覆盖该参数区）。
 * <b>第二阶段已全部接管</b>——14 项由 5 个 {@code beloong:} 自制群系覆盖，7 项交给 BWG。
 * 因此 {@link #WHITELIST} 现在是空集，天灾维度的群系里<strong>不应再出现任何
 * {@code minecraft:} 群系</strong>。
 * <p>
 * 逐项去向与依据见 {@code docs/plans/2026-09-11-disaster-phase2-biome-table.md}。
 *
 * @see DisasterBiomeMapping
 * @see com.zonlong.beloong.mixin.CloneParameterListMixin
 * @see com.zonlong.beloong.mixin.PossibleBiomesFilterMixin
 * @see com.zonlong.beloong.mixin.BwgRegionBiomeRewriteMixin
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

    /** 本模组命名空间。 */
    private static final String BELOONG_NAMESPACE = "beloong";

    /**
     * 第二阶段为天灾维度自制的群系（路径部分）。
     * <p>
     * <b>这里是自制群系清单的权威来源，供启动诊断使用</b>（数据文件在
     * {@code src/main/resources/data/beloong/worldgen/biome/<name>.json}）。
     * <p>
     * ⚠️ {@link DisasterBiomeMapping} 与 {@code BeloongSurfaceRules} 里各自还硬编码了相同的
     * 群系 ID 字面量，本列表**没有**被它们引用。新增第 6 个自制群系时需同时改三处；
     * <p>
     * 定案见 {@code docs/plans/2026-09-11-disaster-phase2-biome-table.md}：
     * 自制 5 个（海洋 2 + 河流 1 + 洞穴 1 + 碎裂地形 1），其余 7 项交给 BWG。
     */
    public static final List<String> CUSTOM_BIOMES = List.of(
            "frozen_ocean", "ocean", "river", "caves", "windswept");

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
     * 「当前正在为<strong>目标维度</strong>建立追加群系列表」的作用域标志。
     * <p>
     * <b>为什么不能用 {@link #substitutionApplied} 代替：</b>那是一个<strong>全局</strong>标志，
     * 一旦天灾维度处理过就永久为真。而 {@code LevelUtils.initializeOnServerStart} 会遍历
     * <strong>所有</strong> level stem，其中{@code minecraft:the_nether} 同样会走到
     * {@code appendDeferredBiomesList}（它也在 {@code overworld_regions} 之外的 NETHER 分支）。
     * <p>
     * 实测后果（2026-09-11 发现并修复）：下界被误判为目标维度，其 5 个原版群系被
     * {@link #isBlocklisted} 判为黑名单并从 {@code possibleBiomes()} 剔除
     * ⇒ 下界结构集因 {@code hasBiomesForStructureSet} 预筛失败而整条消失、
     * {@code /locate biome minecraft:nether_wastes} 失效。生成层不受影响，只有查询层坏掉。
     * <p>
     * 本标志由 {@code CloneParameterListMixin} 在 {@code LevelUtils.initializeBiomes}
     * 调用 {@code appendDeferredBiomesList} 的<strong>前后</strong>包夹设置，
     * 那里直接拿得到 {@code levelKey}，因此判定是精确的。
     */
    private static volatile boolean filteringTargetBiomeList = false;

    /** 进入目标维度的 {@code appendDeferredBiomesList} 调用（由 Mixin 包夹调用）。 */
    public static void beginTargetBiomeList() {
        filteringTargetBiomeList = true;
    }

    /** 离开目标维度的 {@code appendDeferredBiomesList} 调用。 */
    public static void endTargetBiomeList() {
        filteringTargetBiomeList = false;
    }

    /**
     * 当前是否正在为目标维度建立追加群系列表 —— 查询层过滤的<strong>唯一</strong>维度判据。
     *
     * @return true 表示此刻的 {@code appendDeferredBiomesList} 调用属于天灾维度
     */
    public static boolean isFilteringTargetBiomeList() {
        return filteringTargetBiomeList;
    }

    /**
     * 最近一次替换所用的群系注册表。
     * <p>
     * 由 {@link #filter} 在取得注册表后写入，供 region 树路径
     * （{@code BwgRegionBiomeRewriteMixin}）解析映射目标使用。
     * <p>
     * <b>为什么可以共用静态状态：</b>与 {@link #substitutionApplied} 同属"两条注入路径
     * 共用同一信号"的做法（总设计决策 21）。<b>时序上安全</b>——
     * {@code filter()} 在 {@code CloneParameterListMixin.cloneBeforeInit} 内
     * <strong>先于</strong> {@code initializeForTerraBlender}（建 region 树）执行，
     * 而 region 树的 {@code addBiomes} 只可能在那之后被调用。
     */
    private static volatile Registry<Biome> activeBiomeRegistry;

    /**
     * 供 region 树路径取用的群系注册表。
     *
     * @return 当前生效的群系注册表；替换尚未发生时为 {@code null}
     */
    public static Registry<Biome> activeBiomeRegistry() {
        return activeBiomeRegistry;
    }

    /**
     * 刻意保留在天灾维度的原版群系白名单 —— <b>第二阶段起已清空</b>。
     * <p>
     * <b>为什么保留这个（空的）字段而不是删掉：</b>它是两条注入路径共用的抽象
     * ——{@link #isWhitelisted} 与 {@link #isBlocklisted} 都基于它。
     * 删除会让"白名单"这个概念从代码里消失，而这个概念本身仍然有效
     * （只要往这里加回条目，对应原版群系就会重新被放行）。
     * <p>
     * <b>清空的后果：</b>{@link #isBlocklisted} 现在等价于"是 {@code minecraft:} 命名空间"，
     * 因此<strong>原版参数空间里的每一个 {@code minecraft:} 群系都会被改写</strong>，
     * 这就要求 {@link DisasterBiomeMapping} 覆盖全部 53 个原版群系。
     * 覆盖不全会让 {@link #filter} 走"保留原版 + 记 ERROR"分支，日志出现「未能求解 &gt; 0」。
     * <p>
     * 第一阶段的 21 项去向见
     * {@code docs/plans/2026-09-11-disaster-phase2-biome-table.md}：
     * 14 项由 5 个 {@code beloong:} 自制群系接管，7 项交给 BWG。
     */
    private static final Set<String> WHITELIST = Set.of();

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
            activeBiomeRegistry = biomeRegistry;
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] disaster biome substitution: cannot obtain the biome registry, skipping this pass", t);
            substitutionApplied = false;
            return original;
        }

        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : original) {
            Pair<Climate.ParameterPoint, Holder<Biome>> current = entry;
            try {
                Optional<ResourceKey<Biome>> keyOpt = entry.getSecond().unwrapKey();
                if (keyOpt.isPresent() && isBlocklisted(keyOpt.get().location())) {
                    ResourceKey<Biome> originalKey = keyOpt.get();
                    // 与 region 树路径共用同一入口，保证两条路径口径一致
                    ResourceKey<Biome> resolved = rewriteKey(biomeRegistry, originalKey, true);
                    if (!resolved.equals(originalKey)) {
                        // rewriteKey 只在 isUsable 通过时才返回不同的键，而 isUsable 已断言
                        // registry.getHolder(mapped).isPresent()。此处直接取，无需再判空。
                        current = Pair.of(entry.getFirst(),
                                (Holder<Biome>) biomeRegistry.getHolderOrThrow(resolved));
                        replaced++;
                    } else {
                        // 映射未覆盖或目标不可用 —— rewriteKey 已记 ERROR，此处只计数
                        unsolved++;
                    }
                }
            } catch (Throwable t) {
                // 单条失败不影响整体：保留原版群系，仅计数
                unsolved++;
                BeLoongCore.LOGGER.error(
                        "[BeLoong] disaster biome substitution: failed to process one entry,"
                                + " keeping the vanilla biome for that parameter point", t);
            }
            out.add(current);
        }

        substitutionApplied = replaced > 0;

        // 逐点完整：替换 + 非原版保留 + 未能求解 == 总数
        //
        // 注：第二项是"原样放行的条目数"，即**非 minecraft: 命名空间**的条目（BWG / beloong:）。
        // 白名单已清空，故它现在等价于"非原版保留"，不再是"白名单保留"。
        BeLoongCore.LOGGER.info(
                "[BeLoong] disaster biome substitution: parameter points {} = replaced {} + non-vanilla kept {}"
                        + " + unsolved {} (substitution {})",
                out.size(), replaced, out.size() - replaced - unsolved, unsolved,
                substitutionApplied ? "applied" : "NOT applied");
        if (unsolved > 0) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] disaster biome substitution: {} parameter points could not be resolved;"
                            + " vanilla biomes will be kept there. Common causes: the mapping target is disabled"
                            + " in BWG's world_generation.json, or the mapping table does not cover that biome",
                    unsolved);
        }

        return out;
    }

    /**
     * 逐 region 统计其参数树实际输出的 {@code minecraft:} 群系，打一行账目日志。
     * <p>
     * <b>为什么需要它：</b>总设计 §4.8 的生成层验证读的是
     * {@code biomeSource.parameters().values} —— 那是 <strong>index 0 兜底树</strong>，
     * <strong>完全不覆盖 BWG 的 region 树</strong>。这个盲区正是第一阶段 5 项黑名单泄漏
     * （{@code badlands} 系 / {@code mushroom_fields} / {@code beach}）长期未被发现的原因。
     * <p>
     * <b>为什么它读到的是改写后的结果：</b>本方法自行调用
     * {@code region.addBiomes(registry, consumer)}，而 {@code BwgRegionBiomeRewriteMixin}
     * 会在 {@code addBiomes} 的 HEAD 处<strong>包裹传入的 consumer</strong>。
     * 因此本统计<strong>走的是真实代码路径</strong>，不是另起一套逻辑。
     * <p>
     * 纯读取：{@code addBiomes} 只把静态数组展开成参数对，无副作用。
     * <strong>预期结果</strong>：{@code minecraft:} 群系集合应与 {@link #WHITELIST} 一致
     * （第二阶段白名单清空后应为空集）。
     *
     * @param registryAccess 服务器注册表访问
     */
    public static void logRegionTreeBiomes(RegistryAccess registryAccess) {
        try {
            Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
            StringBuilder sb = new StringBuilder();
            for (Region region : Regions.get(RegionType.OVERWORLD)) {
                Set<String> vanilla = new TreeSet<>();
                int[] total = {0};
                int[] deferred = {0};
                region.addBiomes(biomeRegistry, pair -> {
                    total[0]++;
                    ResourceKey<Biome> key = pair.getSecond();
                    if (Region.DEFERRED_PLACEHOLDER.equals(key)) {
                        deferred[0]++;
                    } else if (VANILLA_NAMESPACE.equals(key.location().getNamespace())) {
                        vanilla.add(key.location().getPath());
                    }
                });
                // index 0 = DefaultOverworldRegion。它的**树**由 Climate.RTree.create(this.values)
                // 建立（MixinParameterList.java:79-82）——也就是被本类改写的那一份，
                // 因此它这一行的 addBiomes 输出**不代表实际取群系来源**（那只喂查询层的追加列表，
                // 由 PossibleBiomesFilterMixin 负责过滤）。标注出来，免得这行 53 个原版群系
                // 与标题「应为空集」看起来自相矛盾。
                boolean isIndexZero =
                        Regions.getIndex(RegionType.OVERWORLD, region.getName()) == 0;
                sb.append(String.format("%n    %s%s: parameter points %d, DEFERRED %d, minecraft: biomes %d %s",
                        region.getName(),
                        isIndexZero ? " (index 0; its tree comes from the rewritten values, NOT a real source)" : "",
                        total[0], deferred[0], vanilla.size(), vanilla));
            }
            BeLoongCore.LOGGER.info(
                    "[BeLoong] disaster region tree audit (only regions with index!=0 are real sources;"
                            + " their minecraft: biomes must be empty): {}", sb);

            // 自制群系是否真的注册成功——映射目标一旦指向未注册的群系，
            // filter() 会走"保留原版 + 记 ERROR"分支，账目出现「未能求解 > 0」。
            // 这里提前给出确定性结论，避免到那时才排查。
            List<String> missing = new ArrayList<>();
            for (String name : CUSTOM_BIOMES) {
                ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME,
                        ResourceLocation.fromNamespaceAndPath(BELOONG_NAMESPACE, name));
                if (biomeRegistry.getHolder(key).isEmpty()) {
                    missing.add(name);
                }
            }
            if (missing.isEmpty()) {
                BeLoongCore.LOGGER.info("[BeLoong] custom biome registration: all {} ready {}",
                        CUSTOM_BIOMES.size(), CUSTOM_BIOMES);
            } else {
                BeLoongCore.LOGGER.error("[BeLoong] custom biome registration: the following {} are not registered,"
                                + " vanilla biomes mapped to them will be kept: {}",
                        missing.size(), missing);
            }
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error("[BeLoong] region tree audit failed", t);
        }
    }

    /**
     * 逐维度统计 {@code possibleBiomes()}，验证<strong>查询层</strong>是否干净。
     * <p>
     * 这是总设计 §4.8 记录的查询层验证方法。查询层与生成层是<strong>两条独立路径</strong>：
     * {@code /locate biome}、自然罗盘、地图读的都是 {@code BiomeSource.possibleBiomes()}，
     * 而生成走 {@code getNoiseBiome → findValuePositional}。
     * <p>
     * <b>预期结果</b>：{@code beloong:disaster} 的 {@code minecraft:} 群系应为<strong>空集</strong>
     * （白名单已清空 ⇒ {@link #isBlocklisted} 覆盖全部 {@code minecraft:}）。
     * 其余维度不应受影响（尤其主世界应保留完整 53 个原版群系）。
     * <p>
     * 附带作用：它同时验证了 {@code ChunkGeneratorStructureState} 的结构预筛条件
     * ——那个预筛用的正是 {@code possibleBiomes()}。
     *
     * @param server 服务器实例
     */
    public static void logPossibleBiomes(MinecraftServer server) {
        try {
            StringBuilder sb = new StringBuilder();
            for (ServerLevel level : server.getAllLevels()) {
                Set<Holder<Biome>> biomes =
                        level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
                Map<String, Integer> byNamespace = new TreeMap<>();
                Set<String> vanilla = new TreeSet<>();
                for (Holder<Biome> holder : biomes) {
                    String ns = holder.unwrapKey()
                            .map(k -> k.location().getNamespace()).orElse("(unregistered)");
                    byNamespace.merge(ns, 1, Integer::sum);
                    if (VANILLA_NAMESPACE.equals(ns)) {
                        holder.unwrapKey().ifPresent(k -> vanilla.add(k.location().getPath()));
                    }
                }
                sb.append(String.format("%n    %s: %d biomes %s%s",
                        level.dimension().location(), biomes.size(), byNamespace,
                        vanilla.isEmpty() ? "" : "  (!) residual minecraft: " + vanilla));
            }
            BeLoongCore.LOGGER.info(
                    "[BeLoong] per-dimension possibleBiomes audit (beloong:disaster minecraft: must be empty): {}", sb);
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error("[BeLoong] possibleBiomes audit failed", t);
        }
    }

    /**
     * 判断某个群系是否为 TerraBlender 的<strong>延迟哨兵</strong>。
     * <p>
     * {@code terrablender:deferred_placeholder} 是 TerraBlender 用来表示
     * "此处交回 index 0 兜底树"的标记（{@code Region.DEFERRED_PLACEHOLDER}）。
     * BWG 的 region 树会把它写进输出列表，而它**确实注册在群系注册表里**，
     * 于是 {@code LevelUtils.initializeBiomes} 的收集分支放行、混进了 {@code possibleBiomes()}。
     * <p>
     * <b>它不是真实群系</b>：{@code findValuePositional} 遇到它会回退到 index 0 树，
     * 因此永远不会被生成；也没有任何结构集引用它。
     * <p>
     * <b>为什么要剔除：</b>{@code possibleBiomes()} 是自然罗盘列表与 {@code /locate biome}
     * 的数据源。留着它会让自然罗盘把它列成一个"可搜索"的群系，而搜索永远扫不到东西。
     * <p>
     * 实测（2026-09-11）：天灾维度的 {@code possibleBiomes()} 共 61 种，其中 1 种即本哨兵;
     * 剔除后为 60 种，全部为真实群系。
     *
     * @param id 群系资源位置
     * @return true 表示是 TerraBlender 的延迟哨兵
     */
    public static boolean isDeferredSentinel(ResourceLocation id) {
        return "terrablender".equals(id.getNamespace())
                && "deferred_placeholder".equals(id.getPath());
    }

    /**
     * 改写单条原版群系键 —— <strong>两条注入路径共用的唯一入口</strong>。
     * <p>
     * 命中黑名单（{@link #isBlocklisted}）时查映射表并校验目标可用性，返回可用的目标键；
     * 其余情况（非黑名单、映射表未覆盖、目标不可用）一律<strong>返回原键</strong>，
     * 由调用方决定如何计数与记录。
     * <p>
     * <b>为什么需要这个入口：</b>index 0 兜底树（{@code CloneParameterListMixin}）与
     * BWG 的 region 树（{@code BwgRegionBiomeRewriteMixin}）是两条独立路径，
     * 但必须使用<strong>完全相同的判据与映射</strong>。把口径收敛到一处，
     * 避免白名单缩小或映射表调整时两条路径漂移。
     *
     * @param registry  群系注册表
     * @param from      原始群系键
     * @param logErrors 解析失败时是否记 ERROR。region 树对每个 region 都会调用本方法
     *                  （且 {@code addBiomes} 每 region 会被调用两次），传 {@code false}
     *                  以免刷屏，由调用方聚合后统一上报
     * @return 应使用的群系键；不应改写或无法改写时返回 {@code from} 本身（不返回 null）
     */
    public static ResourceKey<Biome> rewriteKey(
            Registry<Biome> registry, ResourceKey<Biome> from, boolean logErrors) {
        if (from == null || !isBlocklisted(from.location())) {
            return from;
        }
        ResourceKey<Biome> resolved = resolveTarget(registry, from.location(), logErrors);
        return resolved != null ? resolved : from;
    }

    /**
     * 解析某个原版群系在天灾维度的替换目标。
     * <p>
     * 无兜底：映射表未覆盖或目标不可用时返回 {@code null}，由调用方保留原版群系。
     *
     * @param registry  群系注册表
     * @param from      被替换的原版群系
     * @param logErrors 失败时是否记 ERROR（见 {@link #rewriteKey}）
     * @return 可用的目标群系键；不可用时返回 null
     */
    private static ResourceKey<Biome> resolveTarget(
            Registry<Biome> registry, ResourceLocation from, boolean logErrors) {
        ResourceKey<Biome> mapped = DisasterBiomeMapping.substitute(from);
        if (mapped == null) {
            if (logErrors) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] disaster biome substitution: no mapping entry for {}, keeping the vanilla biome", from);
            }
            return null;
        }
        if (!isUsable(registry, mapped)) {
            if (logErrors) {
                BeLoongCore.LOGGER.error(
                        "[BeLoong] disaster biome substitution: target {} for {} is unusable"
                                + " (not registered or disabled in BWG config), keeping the vanilla biome",
                        mapped.location(), from);
            }
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
                    "[BeLoong] disaster biome substitution: BWG ({}) is not loaded - it is a hard dependency,"
                            + " the feature cannot work", BWG_MOD_ID);
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
                    "[BeLoong] disaster biome substitution: cannot read the BWG biome config ({}),"
                            + " affected targets will keep their vanilla biomes", t.toString());
            return false;
        }
    }
}
