package com.zonlong.beloong;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件。分为通用、客户端和服务端三类：
 * <ul>
 *   <li>通用配置 — 两端均需加载，服务端同步至客户端</li>
 *   <li>客户端配置 — 仅影响本地渲染和输入，单人/多人均可在本地修改</li>
 *   <li>服务端配置 — 控制仅由服务端判定的玩法参数</li>
 * </ul>
 */
public class Config {

    // ==================== 客户端配置 ====================

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    /** 修复稳定悬浮漂移（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("修复稳定悬浮漂移")
            .define("fixStableHoverDrift", true);

    /** 禁用王国场地的冰火天空特效（默认禁用） */
    public static final ModConfigSpec.BooleanValue DISABLE_MALKUTH_HELLSCAPE_SKYBOX = CLIENT_BUILDER
            .comment("禁用王国场地的冰火天空盒渲染，解决渲染异常的问题")
            .define("disableMalkuthHellscapeSkybox", false);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== 通用配置 ====================

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    /** 龙之生存FTB区块兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue DS_FTBCHUNKS_COMPAT = COMMON_BUILDER
            .comment("龙之生存FTB区块兼容")
            .define("ds_ftbchunks_compat", true);

    /** 超越维度FTB区块兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue BD_FTBCHUNKS_COMPAT = COMMON_BUILDER
            .comment("超越维度FTB区块兼容")
            .define("bd_ftbchunks_compat", true);

    /** 修复财宝堆复制（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_TREASURE_DUPLICATION = COMMON_BUILDER
            .comment("移除财宝堆重力下落行为，从根源杜绝刷沙机复制财宝堆")
            .define("fixTreasureDuplication", true);

    /** 修复灾变结构生成（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_CATACLYSM_STRUCTURE_HEIGHT = COMMON_BUILDER
            .comment("修复灾变结构无视数据包start_height配置，在固定Y轴生成的问题")
            .define("fixCataclysmStructureHeight", true);

    /** 修复龙之生存弹射物崩溃（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_DS_PROJECTILE_CRASH = COMMON_BUILDER
            .comment("修复龙之生存弹射物崩溃")
            .define("fixDragonsurvivalProjectileCrash", true);

    /** 修复Fsweep打开部分容器崩溃（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_FSWEEP_CONTAINER_CRASH = COMMON_BUILDER
            .comment("修复Fsweep打开部分容器崩溃")
            .define("fixFsweepContainerCrash", true);

    /** Mowzie's Mobs 钢铁守护者：允许原版重锤伤害（默认启用） */
    public static final ModConfigSpec.BooleanValue ENABLE_MOWZIE_MACE_DAMAGE = COMMON_BUILDER
            .comment("Enable Ferrous Wroughtnaut damage by vanilla mace",
                    "允许使用原版重锤对已激活的钢铁守护者造成伤害")
            .define("enableMowzieMaceDamage", true);

    /** Mowzie's Mobs 通臂大师：移除跑酷试炼中的飞行/水中/持续上升反作弊（默认启用） */
    public static final ModConfigSpec.BooleanValue REMOVE_SCULPTOR_ANTI_CHEAT = COMMON_BUILDER
            .comment("Remove Sculptor anti-cheat",
                    "移除通臂大师跑酷试炼中的飞行、水中和持续上升反作弊；保留距离、低于石柱和传送检测")
            .define("removeSculptorAntiCheat", true);

    // ==================== 旧存档模板维度迁移 ====================

    public static final class TemplateUpdate {
        private TemplateUpdate() {}

        /** 旧存档模板维度自动迁移总开关（默认启用） */
        public static ModConfigSpec.BooleanValue enabled;
        /** 模板版本号，更新地图模板后手动 +1 可触发旧存档覆盖更新 */
        public static ModConfigSpec.IntValue templateVersion;
    }

    static {
        COMMON_BUILDER.push("template_update");
        TemplateUpdate.enabled = COMMON_BUILDER
                .comment("Enable old-save template dimension migration",
                        "是否启用旧存档模板维度自动迁移")
                .define("enabled", true);
        TemplateUpdate.templateVersion = COMMON_BUILDER
                .comment("Template version; bump this to force old saves to be overwritten from the template",
                        "模板版本号；更新地图模板后手动 +1 可触发旧存档覆盖更新")
                .defineInRange("templateVersion", 1, 1, Integer.MAX_VALUE);
        COMMON_BUILDER.pop();
    }

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // ==================== loong_palace ====================

    public static final class LoongPalaceProtection {
        private LoongPalaceProtection() {}

        public static ModConfigSpec.BooleanValue environmentProtectionEnabled;
        public static ModConfigSpec.BooleanValue protectExplosions;
        public static ModConfigSpec.BooleanValue protectNonPlayerBlockPlacement;
        public static ModConfigSpec.BooleanValue protectLivingBlockDestruction;
        public static ModConfigSpec.BooleanValue protectMobGriefing;
        public static ModConfigSpec.BooleanValue protectFarmlandTrampling;
        public static ModConfigSpec.BooleanValue protectToolModifications;
        public static ModConfigSpec.BooleanValue protectCropGrowth;
        public static ModConfigSpec.BooleanValue protectFeatureGrowth;
        public static ModConfigSpec.BooleanValue protectPortalCreation;
        public static ModConfigSpec.BooleanValue protectFluidContainerEdits;
        public static ModConfigSpec.BooleanValue protectHangingEntityEdits;
        public static ModConfigSpec.BooleanValue protectFlowerPotEdits;
    }

    // ==================== beloong_water ====================

    public static final class BeloongWater {
        private BeloongWater() {}

        public static ModConfigSpec.IntValue triggerCooldownTicks;
    }

    // ==================== dimension_transport ====================

    public static final class DimensionTransport {
        private DimensionTransport() {}

        public static ModConfigSpec.IntValue checkIntervalTicks;
        public static ModConfigSpec.IntValue cooldownTicks;

        public static ModConfigSpec.BooleanValue owToLP_enabled;
        public static ModConfigSpec.IntValue owToLP_triggerY;
        public static ModConfigSpec.ConfigValue<String> owToLP_targetDimension;
        public static ModConfigSpec.DoubleValue owToLP_targetX;
        public static ModConfigSpec.DoubleValue owToLP_targetZ;
        public static ModConfigSpec.DoubleValue owToLP_fallbackY;

        public static ModConfigSpec.BooleanValue lpToOw_enabled;
        public static ModConfigSpec.IntValue lpToOw_triggerY;
    }

    // ==================== treasure_growth ====================

    public static final class TreasureGrowth {
        private TreasureGrowth() {}

        public static ModConfigSpec.BooleanValue enabled;
        public static ModConfigSpec.IntValue maxTreasureValue;
        public static ModConfigSpec.IntValue amplifierStep;
        public static ModConfigSpec.IntValue maxAmplifier;
        public static ModConfigSpec.IntValue effectDurationTicks;
        public static ModConfigSpec.IntValue checkIntervalTicks;
    }

    // ==================== disaster_portal ====================
    // 天灾传送门配置节。
    // 传送逻辑说明：
    //   - 在任意非天灾维度进入传送门 → 1:1 坐标传送到 beloong:disaster
    //   - 在天灾维度进入传送门 → 传送到主世界玩家重生点（原版末地逻辑）

    public static final class DisasterPortal {
        private DisasterPortal() {}

        /** 激活传送门所需的 12 种眼球物品 ID（固定列表，不可扩展） */
        public static ModConfigSpec.ConfigValue<List<? extends String>> eyeItems;
        /** 传送后的冷却时间（ticks），防止玩家在传送门中来回弹跳 */
        public static ModConfigSpec.IntValue teleportCooldownTicks;
    }

    // ==================== structure_effects ====================

    public static final class StructureEffects {
        private StructureEffects() {}

        /** 需要监听过期事件的药水效果 ID 列表 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> watchedEffects;
    }

    // ==================== dragon_summon ====================
    // 末影龙手动召唤仪式（YUNG's Better End Island 兼容）

    public static final class DragonSummon {
        private DragonSummon() {}

        /** 启用手动召唤末影龙仪式 */
        public static ModConfigSpec.BooleanValue enabled;
        /** 用于召唤末影龙的特殊方块 ID */
        public static ModConfigSpec.ConfigValue<String> summonBlock;
        /** 自定义返回传送门偏移 X */
        public static ModConfigSpec.IntValue offsetX;
        /** 自定义返回传送门偏移 Y */
        public static ModConfigSpec.IntValue offsetY;
        /** 自定义返回传送门偏移 Z */
        public static ModConfigSpec.IntValue offsetZ;
    }

    // ==================== disaster_biomes ====================
    // 天灾维度生物群系与结构管控。
    // 机制：CloneParameterListMixin 在天灾维度初始化时，不使用 TerraBlender 的
    // 区域唯一性噪声路由，而是重建一张“白名单参数表”：
    //   - 命名空间 ∈ allowedNamespaces 的群系（默认 BWG + RU）
    //   - 以及 allowedBiomes 中精确列出的额外群系 ID
    // 生物群系选择退化为最近邻匹配，因此白名单里的水/洞穴类群系参数点
    // 会自然占据对应的气候区间（河流/海洋/洞穴照常出现）。
    // 结构管控：ChunkMapMixin 只允许 structureSetWhitelist 中列出的结构集
    // 在天灾维度放置，并可将 spacing/separation/frequency 覆写为配置值
    // （设为 -1 表示保持结构集原值）。

    public static final class DisasterBiomes {
        private DisasterBiomes() {}

        /** 总开关：false 时天灾维度完全保持原样（与主世界同源） */
        public static ModConfigSpec.BooleanValue enabled;
        /** 允许进入天灾维度的群系命名空间列表 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> allowedNamespaces;
        /** 额外精确白名单的群系 ID（如 "minecraft:river"），与命名空间规则取并集 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> allowedBiomes;
        /** 天灾维度允许生成的结构集 ID 白名单 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> structureSetWhitelist;
        /** 结构集 spacing 覆写（-1 = 保持原值） */
        public static ModConfigSpec.IntValue structureSpacingOverride;
        /** 结构集 separation 覆写（-1 = 保持原值；必须小于 spacing） */
        public static ModConfigSpec.IntValue structureSeparationOverride;
        /** 结构出现频率覆写（0.0~1.0，-1 = 保持原值） */
        public static ModConfigSpec.DoubleValue structureFrequencyOverride;
    }


    static {
        // ========== loong_palace.environment_protection ==========
        SERVER_BUILDER.push("loong_palace");
        SERVER_BUILDER.push("environment_protection");

        LoongPalaceProtection.environmentProtectionEnabled = SERVER_BUILDER
                .comment("Master switch for configurable Loong Palace environment protection",
                        "龙宫可配置环境保护的总开关")
                .translation("beloong.configuration.loongPalaceEnvironmentProtectionEnabled")
                .define("enabled", true);
        LoongPalaceProtection.protectExplosions = SERVER_BUILDER
                .comment("Prevent explosions reaching NeoForge's detonate event from changing blocks",
                        "Dragon Survival's protected explosion skill remains fully canceled",
                        "阻止进入 NeoForge 爆炸事件的爆炸修改方块",
                        "龙之生存受保护的爆炸技能仍会被完整取消")
                .translation("beloong.configuration.protectExplosions")
                .define("protectExplosions", true);
        LoongPalaceProtection.protectNonPlayerBlockPlacement = SERVER_BUILDER
                .comment("Prevent block placement attributed to non-player entities or no actor",
                        "阻止由非玩家实体或无来源行为放置方块")
                .translation("beloong.configuration.protectNonPlayerBlockPlacement")
                .define("protectNonPlayerBlockPlacement", true);
        LoongPalaceProtection.protectLivingBlockDestruction = SERVER_BUILDER
                .comment("Prevent living entities from destroying blocks",
                        "阻止生物破坏方块")
                .translation("beloong.configuration.protectLivingBlockDestruction")
                .define("protectLivingBlockDestruction", true);
        LoongPalaceProtection.protectMobGriefing = SERVER_BUILDER
                .comment("Disable mob-griefing actions",
                        "阻止生物破坏环境的行为")
                .translation("beloong.configuration.protectMobGriefing")
                .define("protectMobGriefing", true);
        LoongPalaceProtection.protectFarmlandTrampling = SERVER_BUILDER
                .comment("Prevent farmland trampling",
                        "阻止耕地被踩踏")
                .translation("beloong.configuration.protectFarmlandTrampling")
                .define("protectFarmlandTrampling", true);
        LoongPalaceProtection.protectToolModifications = SERVER_BUILDER
                .comment("Prevent tool actions such as stripping, tilling, and flattening",
                        "阻止去皮、耕作和铲平等工具修改方块的行为")
                .translation("beloong.configuration.protectToolModifications")
                .define("protectToolModifications", true);
        LoongPalaceProtection.protectCropGrowth = SERVER_BUILDER
                .comment("Prevent random-tick crop growth and non-bypass bone meal use",
                        "阻止随机刻作物生长和无绕过权限玩家使用骨粉")
                .translation("beloong.configuration.protectCropGrowth")
                .define("protectCropGrowth", true);
        LoongPalaceProtection.protectFeatureGrowth = SERVER_BUILDER
                .comment("Prevent saplings and similar blocks from growing configured features",
                        "阻止树苗等方块生长为地物")
                .translation("beloong.configuration.protectFeatureGrowth")
                .define("protectFeatureGrowth", true);
        LoongPalaceProtection.protectPortalCreation = SERVER_BUILDER
                .comment("Prevent block updates from creating portals",
                        "阻止方块更新创建传送门")
                .translation("beloong.configuration.protectPortalCreation")
                .define("protectPortalCreation", true);
        LoongPalaceProtection.protectFluidContainerEdits = SERVER_BUILDER
                .comment("Prevent players from placing or collecting fluids with buckets and solid buckets",
                        "阻止玩家使用桶和固体桶放置或收集流体")
                .translation("beloong.configuration.protectFluidContainerEdits")
                .define("protectFluidContainerEdits", true);
        LoongPalaceProtection.protectHangingEntityEdits = SERVER_BUILDER
                .comment("Prevent players from placing, changing, or destroying paintings and item frames",
                        "阻止玩家放置、修改或破坏画与物品展示框")
                .translation("beloong.configuration.protectHangingEntityEdits")
                .define("protectHangingEntityEdits", true);
        LoongPalaceProtection.protectFlowerPotEdits = SERVER_BUILDER
                .comment("Prevent players from inserting or removing flower-pot contents",
                        "阻止玩家放入或取出花盆内容物")
                .translation("beloong.configuration.protectFlowerPotEdits")
                .define("protectFlowerPotEdits", true);

        SERVER_BUILDER.pop(); // environment_protection
        SERVER_BUILDER.pop(); // loong_palace

        // ========== beloong_water ==========
        SERVER_BUILDER.push("beloong_water");

        BeloongWater.triggerCooldownTicks = SERVER_BUILDER
                .comment("化龙池水冷却（ticks）")
                .translation("beloong.configuration.beloongWaterCooldown")
                .defineInRange("beloongWaterCooldown", 200, 0, 72000);

        SERVER_BUILDER.pop(); // beloong_water

        // ========== treasure_growth ==========
        SERVER_BUILDER.push("treasure_growth");

        TreasureGrowth.enabled = SERVER_BUILDER
                .comment("是否启用财宝堆成长加速")
                .define("enabled", true);

        TreasureGrowth.maxTreasureValue = SERVER_BUILDER
                .comment("最大财宝价值上限，超出部分不再计入")
                .defineInRange("maxTreasureValue", 9800, 1, Integer.MAX_VALUE);

        TreasureGrowth.amplifierStep = SERVER_BUILDER
                .comment("每多少财宝价值提升 1 级效果等级")
                .defineInRange("amplifierStep", 100, 1, 10000);

        TreasureGrowth.maxAmplifier = SERVER_BUILDER
                .comment("最大效果等级（0-255）")
                .defineInRange("maxAmplifier", 255, 0, 255);

        TreasureGrowth.effectDurationTicks = SERVER_BUILDER
                .comment("效果持续时间（ticks），需大于检查间隔确保不闪烁")
                .defineInRange("effectDurationTicks", 40, 20, 6000);

        TreasureGrowth.checkIntervalTicks = SERVER_BUILDER
                .comment("财宝价值检查间隔（ticks），默认 20 = 每秒一次")
                .defineInRange("checkIntervalTicks", 20, 1, 1200);

        SERVER_BUILDER.pop(); // treasure_growth

        // ========== dimension_transport ==========
        SERVER_BUILDER.push("dimension_transport");

        DimensionTransport.checkIntervalTicks = SERVER_BUILDER
                .comment("玩家 Y 坐标检查间隔（ticks），默认 20 = 每秒一次")
                .defineInRange("checkIntervalTicks", 20, 1, 1200);

        DimensionTransport.cooldownTicks = SERVER_BUILDER
                .comment("传送后冷却时间（ticks），防止循环传送")
                .defineInRange("cooldownTicks", 100, 0, 72000);

        SERVER_BUILDER.push("overworldToLoongPalace");
        DimensionTransport.owToLP_enabled = SERVER_BUILDER
                .comment("是否启用 主世界 → 龙宫 的传送")
                .define("enabled", true);
        DimensionTransport.owToLP_triggerY = SERVER_BUILDER
                .comment("触发传送的 Y 轴高度（玩家 Y > 此值时传送）")
                .defineInRange("triggerY", 8848, -4064, 100000);
        DimensionTransport.owToLP_targetDimension = SERVER_BUILDER
                .comment("目标维度 ID")
                .define("targetDimension", "beloong:loong_palace");
        DimensionTransport.owToLP_targetX = SERVER_BUILDER
                .comment("目标固定 X 坐标")
                .defineInRange("targetX", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.owToLP_targetZ = SERVER_BUILDER
                .comment("目标固定 Z 坐标")
                .defineInRange("targetZ", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.owToLP_fallbackY = SERVER_BUILDER
                .comment("高度图查找失败时的回退 Y 坐标")
                .defineInRange("fallbackY", 64.5, -2032.0, 2032.0);
        SERVER_BUILDER.pop();

        SERVER_BUILDER.push("loongPalaceToOverworld");
        DimensionTransport.lpToOw_enabled = SERVER_BUILDER
                .comment("是否启用 龙宫 → 主世界 的传送")
                .define("enabled", true);
        DimensionTransport.lpToOw_triggerY = SERVER_BUILDER
                .comment("触发传送的 Y 轴高度（玩家 Y < 此值时传送）")
                .defineInRange("triggerY", 0, -2032, 2032);
        SERVER_BUILDER.pop();

        SERVER_BUILDER.pop(); // dimension_transport

        // ========== disaster_portal ==========
        SERVER_BUILDER.push("disaster_portal");

        DisasterPortal.eyeItems = SERVER_BUILDER
                .comment("激活传送门所需的 12 种眼球物品 ID（顺序可任意）")
                .defineList("eyeItems",
                        List.of(
                                "minecraft:ender_eye",
                                "cataclysm:mech_eye",
                                "cataclysm:flame_eye",
                                "cataclysm:void_eye",
                                "cataclysm:monstrous_eye",
                                "cataclysm:abyss_eye",
                                "cataclysm:desert_eye",
                                "cataclysm:cursed_eye",
                                "cataclysm:storm_eye",
                                "fdbosses:eye_of_chesed",
                                "fdbosses:eye_of_malkuth",
                                "fdbosses:eye_of_geburah"
                        ),
                        () -> "",
                        s -> s instanceof String str && str.contains(":"));

        DisasterPortal.teleportCooldownTicks = SERVER_BUILDER
                .comment("传送冷却时间（ticks），防止循环传送")
                .defineInRange("teleportCooldownTicks", 100, 0, 72000);

        SERVER_BUILDER.pop(); // disaster_portal

        // ========== structure_effects ==========
        SERVER_BUILDER.push("structure_effects");

        StructureEffects.watchedEffects = SERVER_BUILDER
                .comment("需要监听过期/移除事件的药水效果ID列表",
                        "当这些效果过期、被牛奶清除或被指令移除时，触发结构重检",
                        "具体效果与结构的映射请在 data/beloong/beloong/structure_effects/ 中配置")
                .defineList("watchedEffects",
                        List.of("beloong:flight_ban"),
                        () -> "",
                        s -> s instanceof String str && str.contains(":"));

        SERVER_BUILDER.pop(); // structure_effects

        // ========== dragon_summon ==========
        SERVER_BUILDER.push("dragon_summon");

        DragonSummon.enabled = SERVER_BUILDER
                .comment("Enable the Ender Dragon summon ritual",
                        "启用手动召唤末影龙仪式")
                .translation("beloong.configuration.dragonSummonEnabled")
                .define("enabled", true);

        DragonSummon.summonBlock = SERVER_BUILDER
                .comment("Block used to summon the Ender Dragon",
                        "用于召唤末影龙的特殊方块 ID")
                .translation("beloong.configuration.dragonSummonBlock")
                .define("summonBlock", "bosses_of_mass_destruction:levitation_block");

        DragonSummon.offsetX = SERVER_BUILDER
                .comment("Custom return portal offset X",
                        "自定义返回传送门偏移 X")
                .translation("beloong.configuration.dragonSummonOffsetX")
                .defineInRange("offsetX", -6, -1000, 1000);

        DragonSummon.offsetY = SERVER_BUILDER
                .comment("Custom return portal offset Y",
                        "自定义返回传送门偏移 Y")
                .translation("beloong.configuration.dragonSummonOffsetY")
                .defineInRange("offsetY", -5, -1000, 1000);

        DragonSummon.offsetZ = SERVER_BUILDER
                .comment("Custom return portal offset Z",
                        "自定义返回传送门偏移 Z")
                .translation("beloong.configuration.dragonSummonOffsetZ")
                .defineInRange("offsetZ", -6, -1000, 1000);

        SERVER_BUILDER.pop(); // dragon_summon

        // ========== disaster_biomes ==========
        SERVER_BUILDER.push("disaster_biomes");

        DisasterBiomes.enabled = SERVER_BUILDER
                .comment("Restrict disaster dimension to the biome namespaces below and curate its structure sets",
                        "将天灾维度限制为下列命名空间的群系，并只保留白名单结构集",
                        "关闭后天灾维度与主世界同源（含原版群系）")
                .translation("beloong.configuration.disasterBiomesEnabled")
                .define("enabled", true);

        DisasterBiomes.allowedNamespaces = SERVER_BUILDER
                .comment("Biome mod namespaces allowed in the disaster dimension.",
                        "BWG provides land biomes; RU (regions_unexplored) provides oceans/rivers/caves.",
                        "天灾维度允许的群系模组命名空间。",
                        "BWG 提供陆地群系；RU(regions_unexplored) 提供海洋/河流/洞穴群系")
                .translation("beloong.configuration.disasterBiomesAllowedNamespaces")
                .defineList("allowedNamespaces",
                        List.of("biomeswevegone", "regions_unexplored"),
                        () -> "",
                        s -> s instanceof String str && !str.isBlank());

        DisasterBiomes.allowedBiomes = SERVER_BUILDER
                .comment("Extra biomes allowed by exact ID, merged with the namespace rule.",
                        "Useful when running without RU, e.g. minecraft:river, minecraft:deep_ocean, minecraft:lush_caves",
                        "额外按精确 ID 放行的群系，与命名空间规则取并集。",
                        "没有装 RU 时可在此填原版水体/洞穴群系，例如 minecraft:river、minecraft:deep_ocean、minecraft:lush_caves")
                .translation("beloong.configuration.disasterBiomesAllowedBiomes")
                .defineList("allowedBiomes",
                        List.<String>of(),
                        () -> "",
                        s -> s instanceof String str && ResourceLocation.tryParse(str) != null);

        DisasterBiomes.structureSetWhitelist = SERVER_BUILDER
                .comment("Structure sets allowed to generate in the disaster dimension. Everything else (villages,",
                        "strongholds, mineshafts, ...) is dropped there. Vanilla land structures disappear",
                        "automatically anyway once vanilla biomes are filtered out.",
                        "天灾维度允许生成的结构集白名单，其余结构集（村庄/要塞/矿井等）在该维度不放置。",
                        "原版陆地结构在群系过滤后本就会自动消失，这里兜底覆盖要塞/矿井等按标签残留的结构",
                        "注意：beloong:disaster_set 必须保留，否则 Boss 竞技场不会生成")
                .translation("beloong.configuration.disasterBiomesStructureSetWhitelist")
                .defineList("structureSetWhitelist",
                        List.of("beloong:disaster_set"),
                        () -> "",
                        s -> s instanceof String str && ResourceLocation.tryParse(str) != null);

        DisasterBiomes.structureSpacingOverride = SERVER_BUILDER
                .comment("Override structure set spacing in the disaster dimension (-1 = keep structure set value).",
                        "覆写天灾维度结构集的 spacing（-1 = 保持结构集原值）",
                        "spacing 越大结构分布越稀疏")
                .translation("beloong.configuration.disasterBiomesStructureSpacingOverride")
                .defineInRange("structureSpacingOverride", -1, -1, 4096);

        DisasterBiomes.structureSeparationOverride = SERVER_BUILDER
                .comment("Override structure set separation (-1 = keep; must be smaller than spacing).",
                        "覆写结构集的 separation（-1 = 保持；必须小于 spacing）")
                .translation("beloong.configuration.disasterBiomesStructureSeparationOverride")
                .defineInRange("structureSeparationOverride", -1, -1, 4096);

        DisasterBiomes.structureFrequencyOverride = SERVER_BUILDER
                .comment("Override structure occurrence frequency, 0.0~1.0 (-1 = keep).",
                        "覆写结构出现频率 0.0~1.0（-1 = 保持原值）",
                        "0.5 表示每个候选区块有 50% 概率实际生成结构")
                .translation("beloong.configuration.disasterBiomesStructureFrequencyOverride")
                .defineInRange("structureFrequencyOverride", -1.0, -1.0, 1.0);

        SERVER_BUILDER.pop(); // disaster_biomes
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
