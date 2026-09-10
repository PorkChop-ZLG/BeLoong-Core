package com.zonlong.beloong.worldgen;

import com.mojang.datafixers.util.Pair;
import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.Config;
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
 * 由 {@link CloneParameterListMixin} 在 TerraBlender 初始化参数列表<strong>之前</strong>调用，
 * 把 {@link Climate.ParameterList} 里的原版群系替换成 BWG 群系，从而实现：
 * <ol>
 *   <li>天灾维度不再生成黑名单原版群系（生成层）</li>
 *   <li>这些群系从 {@code BiomeSource.possibleBiomes()} 中消失，{@code /locate biome} 搜不到、
 *       自然罗盘只显示在主世界（查询层）</li>
 * </ol>
 * 两者是同一处改动的结果：{@code possibleBiomes()} 由 {@code parameters().values()} 每次调用现算
 * （{@code MultiNoiseBiomeSource.collectPossibleBiomes} → {@code ParameterList.values()}），
 * 不是构造期快照。
 *
 * <h3>白名单：刻意保留的原版群系</h3>
 * <table border="1">
 *   <tr><th>类别</th><th>群系</th></tr>
 *   <tr><td>海洋（9）</td><td>{@code frozen_ocean} {@code deep_frozen_ocean} {@code cold_ocean}
 *       {@code deep_cold_ocean} {@code ocean} {@code deep_ocean} {@code lukewarm_ocean}
 *       {@code deep_lukewarm_ocean} {@code warm_ocean}</td></tr>
 *   <tr><td>河流（2）</td><td>{@code river} {@code frozen_river}</td></tr>
 *   <tr><td>洞穴（3）</td><td>{@code lush_caves} {@code dripstone_caves} {@code deep_dark}</td></tr>
 * </table>
 * 保留理由：这三类的参数区域（尤其是洞穴的 {@code depth ≥ 0.2}）在 BWG 中没有对应物，
 * 强行替换会让地下直接生成地表群系。详见设计文档。
 *
 * <h3>求解顺序</h3>
 * <pre>
 *   非 minecraft: 命名空间        → 原样保留
 *   命中白名单                    → 原样保留
 *   查 DisasterBiomeMapping 表     → 得到 BWG 目标
 *   目标缺失 / 未在注册表 / 被 BWG 配置禁用 → 改用 Config 的 fallbackBiome
 *   最终再校验一次                 → 仍不可用则放弃替换（保留原版），并记 WARN
 * </pre>
 *
 * @see com.zonlong.beloong.mixin.CloneParameterListMixin
 * @see DisasterBiomeMapping
 */
public final class DisasterBiomeSubstitution {

    /** BWG 的模组 ID。 */
    private static final String BWG_MOD_ID = "biomeswevegone";

    /** BWG 命名空间。 */
    private static final String BWG_NAMESPACE = "biomeswevegone";

    /**
     * BWG 世界生成配置类的全限定名。
     * <p>
     * 刻意写成字符串而非直接 import——见 {@link #isBwgEnabled(ResourceLocation)} 的说明。
     */
    private static final String BWG_WORLDGEN_CONFIG_CLASS =
            "net.potionstudios.biomeswevegone.config.configs.BWGWorldGenConfig";

    /** 原版命名空间。 */
    private static final String VANILLA_NAMESPACE = "minecraft";

    /** 目标维度 ID。 */
    private static final ResourceLocation TARGET_DIMENSION =
            ResourceLocation.fromNamespaceAndPath("beloong", "disaster");

    /**
     * 刻意保留在天灾维度的原版群系白名单（14 项）。
     * <p>
     * 与 {@code Config.DisasterBiomes} 分开放置的原因：这是<strong>结构性的</strong>决策
     * （由参数空间的形状决定），不适合暴露成可自由编辑的配置项——删错一项会让地下生成地表群系。
     */
    private static final Set<String> WHITELIST = Set.of(
            // 海洋：BWG 只有 lush_stacks / dead_sea 两个水体群系，远不足以覆盖 5 个温度带
            "frozen_ocean", "deep_frozen_ocean",
            "cold_ocean", "deep_cold_ocean",
            "ocean", "deep_ocean",
            "lukewarm_ocean", "deep_lukewarm_ocean",
            "warm_ocean",
            // 河流：BWG 完全没有河流群系（源码内 river 零命中，也不声明 minecraft:is_river 标签）
            "river", "frozen_river",
            // 洞穴：BWG 不注册任何 depth > 0 的群系
            "lush_caves", "dripstone_caves", "deep_dark"
    );

    private DisasterBiomeSubstitution() {
    }

    /**
     * 判断某个 LevelStem 是否为本方案的目标维度（{@code beloong:disaster}）。
     * <p>
     * <b>为什么必须做这个判断：</b>{@code CloneParameterListMixin} 重定向的
     * {@code LevelUtils.initializeBiomes} 对<strong>所有</strong>被 TerraBlender 判定为
     * {@code RegionType.OVERWORLD} 的维度都会触发。实测 {@code minecraft:the_nether}
     * 也会命中该调用点（TerraBlender 按维度类型 tag 而非维度身份分支），
     * 若不限定维度，下界的 5 个群系（{@code nether_wastes} / {@code soul_sand_valley} /
     * {@code crimson_forest} / {@code warped_forest} / {@code basalt_deltas}）会被替换成
     * BWG 地表群系——已由实机日志确认会发生。
     * <p>
     * 主世界不需要在这里排除：{@code overworld_regions} tag 不含 {@code minecraft:overworld}，
     * {@code getRegionTypeForDimension} 返回 null，{@code initializeBiomes} 在调用点之前就已 return。
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
        return WHITELIST.contains(vanillaId.getPath());
    }

    /**
     * 对参数列表做替换，返回一个<strong>全新的列表</strong>。
     * <p>
     * 在以下任一情况下返回 {@code original} 本身（调用方据此跳过赋值）：
     * <ul>
     *   <li>{@code Config.DisasterBiomes.enabled} 为 false</li>
     *   <li>没有任何条目需要替换</li>
     * </ul>
     *
     * @param registryAccess 服务器注册表访问（群系注册表由此取得，不使用
     *                       {@code BuiltInRegistries.BIOME}——该静态字段在本项目的
     *                       ModDevGradle 生产映射下不可用）
     * @param original       从共享 {@link Climate.ParameterList} 克隆出来的原始条目序列
     * @return 替换后的新列表；无需替换时返回 {@code original}
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Pair<Climate.ParameterPoint, Holder<Biome>>> filter(
            RegistryAccess registryAccess,
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> original) {

        if (!Config.DisasterBiomes.enabled.get()) {
            return original;
        }

        Registry<Biome> biomeRegistry = registryAccess.registryOrThrow(Registries.BIOME);
        ResourceKey<Biome> fallback = resolveFallback(biomeRegistry);

        List<Pair<Climate.ParameterPoint, Holder<Biome>>> out = new ArrayList<>(original.size());
        int replaced = 0;
        int unsolved = 0;

        for (Pair<Climate.ParameterPoint, Holder<Biome>> entry : original) {
            Pair<Climate.ParameterPoint, ? extends Holder<Biome>> current = entry;
            Optional<ResourceKey<Biome>> keyOpt = entry.getSecond().unwrapKey();

            if (keyOpt.isPresent()) {
                ResourceKey<Biome> key = keyOpt.get();
                ResourceLocation id = key.location();

                boolean shouldReplace = VANILLA_NAMESPACE.equals(id.getNamespace())
                        && !isWhitelisted(id);

                if (shouldReplace) {
                    ResourceKey<Biome> target = DisasterBiomeMapping.substitute(id);
                    ResourceKey<Biome> resolved = resolveTarget(biomeRegistry, target, fallback, id);

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
            }

            out.add((Pair<Climate.ParameterPoint, Holder<Biome>>) (Pair) current);
        }

        if (replaced == 0 && unsolved == 0) {
            return original;
        }

        BeLoongCore.LOGGER.info(
                "[BeLoong] 天灾维度群系替换：参数点 {} 个，替换 {} 个，未能求解 {} 个",
                out.size(), replaced, unsolved);

        return out;
    }

    /**
     * 依次尝试映射表目标与兜底群系，返回第一个可用者。
     *
     * @param registry 群系注册表
     * @param mapped   映射表给出的目标（可为 null）
     * @param fallback 兜底群系（可为 null）
     * @param from     被替换的原版群系，仅用于日志
     * @return 可用的目标群系键；都不可用时返回 null
     */
    private static ResourceKey<Biome> resolveTarget(Registry<Biome> registry,
                                                    ResourceKey<Biome> mapped,
                                                    ResourceKey<Biome> fallback,
                                                    ResourceLocation from) {
        if (mapped != null) {
            if (isUsable(registry, mapped)) {
                return mapped;
            }
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] 天灾群系替换：{} 的目标 {} 不可用（未注册或被 BWG 配置禁用），改用兜底群系",
                    from, mapped.location());
        } else {
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] 天灾群系替换：{} 在映射表中没有对应项，改用兜底群系", from);
        }

        if (fallback != null && isUsable(registry, fallback)) {
            return fallback;
        }

        BeLoongCore.LOGGER.error(
                "[BeLoong] 天灾群系替换：{} 无法求解，且兜底群系不可用——该参数点将保留原版群系", from);
        return null;
    }

    /**
     * 判断一个群系键是否可用于天灾维度：必须已注册，且若属于 BWG 则必须在其配置中启用。
     * <p>
     * BWG 的 {@code BWGTerraBlenderRegion.addBiomes} 会把被禁用的群系改写为
     * {@code Region.DEFERRED_PLACEHOLDER}，说明 BWG 自己都回避使用它们；这里做同样的回避，
     * 否则会往参数列表里塞进一个 BWG 主动弃用的群系。
     *
     * @param registry 群系注册表
     * @param key      待检查的群系键
     * @return true 表示可用
     */
    private static boolean isUsable(Registry<Biome> registry, ResourceKey<Biome> key) {
        if (!registry.containsKey(key)) {
            return false;
        }
        return isBwgEnabled(key.location());
    }

    /**
     * 查询 BWG 配置中某个群系是否启用。
     * <p>
     * <b>为什么用反射而不是直接 import {@code BWGWorldGenConfig}：</b>
     * BWG 是可选依赖。直接引用其类会让包含该引用的方法在<strong>类校验期</strong>就解析
     * {@code BWGWorldGenConfig}，BWG 缺席时抛出 {@code NoClassDefFoundError}——这个错误发生在
     * 进入方法体之前，外层的 {@code try/catch} 捕获不到，会导致 Mixin 应用失败。
     * 反射把类解析推迟到运行期，从而让 {@code try/catch} 真正生效。
     * <p>
     * 读不到配置时保守返回 {@code false}（按禁用处理），交由兜底群系接管——
     * 宁可降级，也不要往参数列表里塞一个 BWG 主动弃用的群系。
     *
     * @param id 群系资源位置
     * @return true 表示启用、或不由本模组判断（非 BWG 命名空间）
     */
    private static boolean isBwgEnabled(ResourceLocation id) {
        if (!BWG_NAMESPACE.equals(id.getNamespace())) {
            return true;
        }
        if (!ModList.get().isLoaded(BWG_MOD_ID)) {
            return true;
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
            BeLoongCore.LOGGER.warn(
                    "[BeLoong] 无法读取 BWG 群系配置（{}），保守按禁用处理", t.toString());
            return false;
        }
    }

    /**
     * 解析配置中的兜底群系，并校验其可用性。
     *
     * @param registry 群系注册表
     * @return 兜底群系的资源键；配置非法或群系不可用时返回 null
     */
    private static ResourceKey<Biome> resolveFallback(Registry<Biome> registry) {
        String raw = Config.DisasterBiomes.fallbackBiome.get();
        ResourceLocation loc = ResourceLocation.tryParse(raw);
        if (loc == null) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] disaster_biomes.fallbackBiome 不是合法的资源 ID：{}", raw);
            return null;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, loc);
        if (!isUsable(registry, key)) {
            BeLoongCore.LOGGER.error(
                    "[BeLoong] disaster_biomes.fallbackBiome 指向的群系不可用：{}", raw);
            return null;
        }
        return key;
    }
}
