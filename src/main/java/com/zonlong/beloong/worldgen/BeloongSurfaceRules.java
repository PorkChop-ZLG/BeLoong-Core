package com.zonlong.beloong.worldgen;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.biome.Biome;
import terrablender.api.SurfaceRuleManager;
import terrablender.worldgen.surface.NamespacedSurfaceRuleSource;

import java.util.Set;

/**
 * {@code beloong:} 命名空间的地表规则（天灾维度第二阶段）。
 *
 * <h3>为什么必须有这个类</h3>
 * 天灾维度用的是 {@code settings: minecraft:overworld}，而 TerraBlender 会把它的
 * {@code surfaceRule} 换成 {@code NamespacedSurfaceRuleSource}——
 * <b>按群系命名空间分发，且是两段式</b>
 * （{@code NamespacedSurfaceRuleSource.java:56-68}）：
 * <pre>
 * if (biome 的命名空间在规则表里) state = 该命名空间的规则.tryApply(...);
 * if (state == null)              state = baseRule.tryApply(...);   // baseRule = 原版 overworld 规则
 * </pre>
 * 原版规则按<strong>具体群系 ID</strong> 分支（{@code SurfaceRuleData.java} 内 30 余处
 * {@code isBiome(...)}），对 {@code beloong:} 群系一条都不命中。
 * 因此本类只需负责 {@code beloong:} 群系；返回 {@code null} 时自动落回原版规则。
 *
 * <h3>为什么只有两条规则</h3>
 * 关键观察：<b>若原版对应群系本身在原版规则里没有分支，则复制品不需要规则</b>——
 * 两者都会走"未命中"路径，行为天然一致。
 *
 * <table border="1">
 *   <tr><th>自制群系</th><th>复制自</th><th>原版有分支？</th><th>是否需要规则</th></tr>
 *   <tr><td>{@code beloong:ocean}</td><td>{@code minecraft:ocean}</td><td>否</td>
 *       <td><b>不需要</b>——未命中路径即原版行为</td></tr>
 *   <tr><td>{@code beloong:river}</td><td>{@code minecraft:river}</td><td>否</td>
 *       <td><b>不需要</b></td></tr>
 *   <tr><td>{@code beloong:caves}</td><td>{@code minecraft:lush_caves}</td><td>否</td>
 *       <td><b>不需要</b>（见下方"已接受的简化"）</td></tr>
 *   <tr><td>{@code beloong:frozen_ocean}</td><td>{@code minecraft:frozen_ocean}</td><td>是（冰）</td>
 *       <td><b>需要</b>——否则海面冰失去 {@code hole()} 斑驳外观</td></tr>
 *   <tr><td>{@code beloong:windswept}</td><td>{@code minecraft:windswept_hills}</td><td>是（石头/砂砾）</td>
 *       <td><b>需要</b>——"碎裂地形"的身份就在这层地表上</td></tr>
 * </table>
 *
 * <h3>已接受的简化</h3>
 * <ul>
 *   <li>{@code beloong:caves}：原版 {@code dripstone_caves} 有一条"强制石头"规则
 *       （{@code SurfaceRuleData.java:88}）。它已被合并进 {@code beloong:caves}，
 *       而基底是 {@code lush_caves}（本身无规则）⇒ 该条丢失。影响仅限地下深处 1 个参数点。</li>
 *   <li>{@code beloong:frozen_ocean}：原版冰海另有一条专属的<b>海底</b>规则
 *       {@code rulesource7}（{@code SurfaceRuleData.java:130-175}）。本类只复刻<b>海面冰</b>，
 *       海底沿用未命中路径的默认材质。海面冰是主导观感，海底在数十格水下不易察觉。</li>
 * </ul>
 *
 * <h3>与 BWG 的写法对齐</h3>
 * 采用 {@code BWGOverworldSurfaceRules} 的既有惯例：
 * {@code biomeAbovePreliminarySurface(biome, rule)} —— 群系门 + {@code abovePreliminarySurface()}
 * 包裹，避免规则作用到洞穴底面。
 *
 * @see com.zonlong.beloong.BeLoongCore 注册处
 */
public final class BeloongSurfaceRules {

    /** 本模组命名空间。 */
    private static final String NS = "beloong";

    private static final ResourceKey<Biome> BELOONG_FROZEN_OCEAN =
            ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(NS, "frozen_ocean"));
    private static final ResourceKey<Biome> BELOONG_WINDSWEPT =
            ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(NS, "windswept"));

    // ---- 方块规则 ----
    private static final SurfaceRules.RuleSource AIR = SurfaceRules.state(Blocks.AIR.defaultBlockState());
    private static final SurfaceRules.RuleSource WATER = SurfaceRules.state(Blocks.WATER.defaultBlockState());
    private static final SurfaceRules.RuleSource ICE = SurfaceRules.state(Blocks.ICE.defaultBlockState());
    private static final SurfaceRules.RuleSource STONE = SurfaceRules.state(Blocks.STONE.defaultBlockState());
    private static final SurfaceRules.RuleSource GRAVEL = SurfaceRules.state(Blocks.GRAVEL.defaultBlockState());
    private static final SurfaceRules.RuleSource DIRT = SurfaceRules.state(Blocks.DIRT.defaultBlockState());

    /**
     * 原版 {@code rulesource2}：洞穴顶面用石头，否则砂砾。
     * <p>
     * 见 {@code SurfaceRuleData.java:73} —— {@code sequence(ifTrue(ON_CEILING, STONE), GRAVEL)}。
     * {@code ON_CEILING} 的判断仍然必要：在 {@code UNDER_FLOOR} 窗口内，
     * 某格也可能同时是下方洞穴的天花板。
     */
    private static final SurfaceRules.RuleSource STONE_OR_GRAVEL = SurfaceRules.sequence(
            SurfaceRules.ifTrue(SurfaceRules.ON_CEILING, STONE),
            GRAVEL);

    // ---- 条件 ----
    /** 对应原版 {@code SurfaceRuleData.java:65} 的 {@code waterBlockCheck(-1, 0)}。 */
    private static final SurfaceRules.ConditionSource WATER_BELOW = SurfaceRules.waterBlockCheck(-1, 0);
    /** 对应原版 {@code :66} 的 {@code waterBlockCheck(0, 0)}。 */
    private static final SurfaceRules.ConditionSource WATER_HERE = SurfaceRules.waterBlockCheck(0, 0);
    /** 对应原版 {@code :68} 的 {@code hole()}。 */
    private static final SurfaceRules.ConditionSource HOLE = SurfaceRules.hole();

    private BeloongSurfaceRules() {
    }

    /**
     * 原版 {@code SurfaceRuleData#surfaceNoiseAbove} 是 {@code private}，此处等价实现。
     *
     * @param value 阈值（原版单位为 1/8.25 缩放前）
     * @return 该条件的来源
     */
    private static SurfaceRules.ConditionSource surfaceNoiseAbove(double value) {
        return SurfaceRules.noiseCondition(Noises.SURFACE, value / 8.25, Double.MAX_VALUE);
    }

    /**
     * 群系门 + {@code abovePreliminarySurface()} 包裹（BWG 惯例）。
     *
     * @param biome 目标群系
     * @param rule  命中后的规则
     * @return 组合后的规则
     */
    private static SurfaceRules.RuleSource biomeAbovePreliminarySurface(
            ResourceKey<Biome> biome, SurfaceRules.RuleSource rule) {
        return SurfaceRules.ifTrue(SurfaceRules.isBiome(biome),
                SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), rule));
    }

    /**
     * 确认 {@code beloong} 命名空间的地表规则<strong>真的进了分发表</strong>。
     * <p>
     * 只打一行"已注册"日志是不够的——{@code SurfaceRuleManager.addSurfaceRules} 是
     * {@code map.put}，写错分类或写错命名空间都不会报错，只会静默失效。
     * 因此这里回读 {@code getNamespacedRules}（它按当前规则表构建一个新的
     * {@code NamespacedSurfaceRuleSource}），直接列出实际生效的命名空间集合。
     * <p>
     * <b>时序说明</b>：游戏真正使用的那个 {@code NamespacedSurfaceRuleSource} 由
     * {@code MixinNoiseGeneratorSettings.surfaceRule()} 在<strong>首次调用时构建并缓存</strong>，
     * 那发生在服务端启动的 {@code LevelUtils.initializeBiomes} 期间——远晚于本规则在
     * {@code FMLCommonSetupEvent} 的注册。因此本检查通过 ⇔ 缓存的实例也包含 {@code beloong}。
     */
    public static void logRegisteredNamespaces() {
        try {
            SurfaceRules.RuleSource probe = SurfaceRuleManager.getNamespacedRules(
                    SurfaceRuleManager.RuleCategory.OVERWORLD, SurfaceRuleData.air());
            if (probe instanceof NamespacedSurfaceRuleSource namespaced) {
                Set<String> namespaces = namespaced.sources().keySet();
                if (namespaces.contains("beloong")) {
                    BeLoongCore.LOGGER.info("[BeLoong] 地表规则分发表命名空间：{} ✓ 含 beloong", namespaces);
                } else {
                    BeLoongCore.LOGGER.error(
                            "[BeLoong] 地表规则分发表里没有 beloong —— 自制群系的地表会掉到默认草/土。"
                                    + "实际命名空间：{}", namespaces);
                }
            } else {
                BeLoongCore.LOGGER.error("[BeLoong] 地表规则未按命名空间装配，得到 {}", probe);
            }
        } catch (Throwable t) {
            BeLoongCore.LOGGER.error("[BeLoong] 地表规则命名空间检查失败", t);
        }
    }

    /**
     * 构建 {@code beloong:} 命名空间的完整地表规则。
     * <p>
     * 返回的序列只在 {@code beloong:} 群系内被查询；任何未命中的情况都返回 {@code null}，
     * 由 {@code NamespacedSurfaceRuleSource} 落回原版规则。
     *
     * @return 地表规则源
     */
    public static SurfaceRules.RuleSource makeRules() {
        return SurfaceRules.sequence(
                biomeAbovePreliminarySurface(BELOONG_FROZEN_OCEAN, frozenOceanIce()),
                biomeAbovePreliminarySurface(BELOONG_WINDSWEPT, windsweptRocky()));
    }

    /**
     * 冰海的海面冰。
     * <p>
     * 逐字对应原版 {@code SurfaceRuleData.java:256-273} 中最内层的冰分支
     * （{@code ON_FLOOR → waterBlockCheck(-1,0) → frozen_ocean → hole()}）：
     * 有洞处按"上方有水则空气 / 温度够冷则冰 / 否则水"三层决定。
     * <b>这正是原版冰海那种斑驳碎冰外观的来源。</b>
     * <p>
     * 原版该分支末尾还接了一条通用海底规则 {@code rulesource7}，此处刻意不复刻——
     * 不返回时自然落回原版规则。见类文档「已接受的简化」。
     *
     * @return 冰面规则
     */
    private static SurfaceRules.RuleSource frozenOceanIce() {
        return SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR,
                SurfaceRules.ifTrue(WATER_BELOW,
                        SurfaceRules.ifTrue(HOLE,
                                SurfaceRules.sequence(
                                        SurfaceRules.ifTrue(WATER_HERE, AIR),
                                        SurfaceRules.ifTrue(SurfaceRules.temperature(), ICE),
                                        WATER))));
    }

    /**
     * 碎裂地形的岩石地表。
     * <p>
     * 对应原版 {@code SurfaceRuleData.java:118-126} 的 {@code WINDSWEPT_GRAVELLY_HILLS} 分支
     * ——三个被合并的原版群系（{@code windswept_hills} / {@code _gravelly_hills} / {@code _forest}）
     * 里，只有它带完整的地表阶梯，且最能体现"碎裂"观感。
     * <p>
     * <b>取舍说明：</b>三者合并成一个群系后只能有一套地表。选这条阶梯会让整片碎裂地形偏岩质
     * （砂砾/石头），而不是"大部分是草、偶尔露石头"（{@code windswept_hills} 的写法）。
     * 这正是自制而非交给 BWG 的目的——保住碎裂地貌特征。
     * 若客户端验收后觉得过于荒芜，把本方法换成原版 {@code :85} 的单条写法即可。
     *
     * <h4>⚠️ 每一级都必须套 {@link #surfaceWindow} —— 这不是风格问题</h4>
     * {@code SurfaceSystem.java:124-153} 的循环是
     * {@code for (int i3 = 地表; i3 >= getMinBuildHeight(); i3--)}，
     * 对整列中<strong>每一个</strong>等于 {@code defaultBlock}（石头）的方块都会调用
     * {@code tryApply}，命中非 null 就替换。
     * <p>
     * 原版这条阶梯整条挂在 {@code :280} 的 {@code ifTrue(UNDER_FLOOR, rulesource6)} 之下，
     * 因此只作用于地表以下若干格。**若照抄规则体而丢掉这层门，
     * 由于最后一级恒不为 null，整片地下石头都会被换成砂砾。**
     * BWG 的 {@code RED_ROCK_PEAKS} / {@code RUGGED_BADLANDS} 等同样给每条叶子都加了
     * {@code ON_FLOOR} / {@code UNDER_FLOOR} 门，是本项目应当照抄的既有范例。
     *
     * @return 岩石地表规则
     */
    private static SurfaceRules.RuleSource windsweptRocky() {
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(surfaceNoiseAbove(2.0), surfaceWindow(STONE_OR_GRAVEL)),
                SurfaceRules.ifTrue(surfaceNoiseAbove(1.0), surfaceWindow(STONE)),
                SurfaceRules.ifTrue(surfaceNoiseAbove(-1.0), surfaceWindow(DIRT)),
                surfaceWindow(STONE_OR_GRAVEL));
    }

    /**
     * 把一条方块规则限制在「地表 + 其下若干格」的窗口内。
     * <p>
     * 对应原版把整条阶梯挂在 {@code UNDER_FLOOR} 之下的做法；同时补上 {@code ON_FLOOR}
     * 以便地表那一格也用同一材质（原版是靠阶梯外侧的其它分支覆盖地表的）。
     * 形态取自 BWG 的 {@code GRASS_DIRT_DIRT_SURFACE} / {@code makeBeachSandRule}。
     *
     * @param rule 方块规则
     * @return 只在表层生效的规则
     */
    private static SurfaceRules.RuleSource surfaceWindow(SurfaceRules.RuleSource rule) {
        return SurfaceRules.sequence(
                SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, rule),
                SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, rule));
    }
}
