package com.zonlong.beloong;

import java.util.List;
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

    /**
     * 稳定悬停修复总开关（默认启用，客户端侧）。
     *
     * <p>实际生效还需同时满足：DS 配置 {@code stable_hover = true}（该值由 NeoForge 在连接时
     * 自动同步给客户端，判定处直接读 {@code ServerFlightHandler.stableHover}），
     * 且玩家的 {@code dragonsurvival:flight_level} ≥ 1。飞行等级不足时按 DS 原版非稳定悬停下坠。</p>
     *
     * <p>滑翔由独立路径处理（{@code ClientFlightHandlerMixin} 的滑翔分支）：去重力 + 跟随视线，
     * <b>不受本开关与飞行等级门控</b>。</p>
     */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("稳定悬停修复总开关；需同时满足 DS 的 stable_hover=true 且 flight_level>=1；滑翔另有独立处理（不受本开关影响）")
            .define("fixStableHoverDrift", true);

    /** 禁用王国场地的冰火天空特效（默认禁用） */
    public static final ModConfigSpec.BooleanValue DISABLE_MALKUTH_HELLSCAPE_SKYBOX = CLIENT_BUILDER
            .comment("禁用王国场地的冰火天空盒渲染，解决渲染异常的问题")
            .define("disableMalkuthHellscapeSkybox", false);

    // ==================== 简易 NPC 对话 ====================
    // 本组字段**分宿两处**，取决于它的判定发生在哪一端：
    //   - enabled      → SERVER_SPEC：触发判定在服务端，由服务端决定是否受理右键；
    //   - charsPerTick / nameScale → CLIENT_SPEC：纯渲染参数，只影响本地观感。
    // 首版三项全在客户端，那是因为当时的触发判定也在客户端（见
    // docs/plans/2026-09-25-npc-dialogue-data-driven-design.md）。

    /** 简易 NPC 对话。字段在下方两个 static 块中分别赋值。 */
    public static final class NpcDialogue {
        private NpcDialogue() {}

        /** 总开关（默认启用）。**服务端配置** —— 服务端据此决定是否受理右键。 */
        public static ModConfigSpec.BooleanValue enabled;
        /** 打字机速度（字/tick），默认 1 ≈ 每秒 20 字 */
        public static ModConfigSpec.IntValue charsPerTick;
        /** 说话人名字字号倍数（1.0 = 与正文同号，最清晰） */
        public static ModConfigSpec.DoubleValue nameScale;
    }

    static {
        CLIENT_BUILDER.push("npc_dialogue");

        // enabled 刻意不在客户端定义 —— 它已迁到 SERVER_SPEC（见文件底部的服务端 static 块）。
        // 理由：判定在服务端；且首版 D19 想要的"将来给第三方模组一键让位"本就是
        // 整合包/服务端级决定，不是每个玩家各自的偏好。

        NpcDialogue.charsPerTick = CLIENT_BUILDER
                .comment("Typewriter speed in characters per tick (1 = default, about 20 chars per second)",
                        "打字机速度（字/tick），默认 1 ≈ 每秒 20 字")
                .translation("beloong.configuration.npcDialogueCharsPerTick")
                .defineInRange("charsPerTick", 1, 1, 20);

        NpcDialogue.nameScale = CLIENT_BUILDER
                .comment("Speaker-name font scale. 1.0 = same size as body text (crispest);",
                        "1.5 = as in the reference screenshots, but non-integer scaling of the",
                        "bitmap font makes some strokes 1px wide and others 2px",
                        "说话人名字的字号倍数。1.0 = 与正文同号（最清晰）；",
                        "1.5 = 对齐参考图，但位图字体非整数缩放会让部分笔画 1px、部分 2px")
                .translation("beloong.configuration.npcDialogueNameScale")
                .defineInRange("nameScale", 1.5D, 1.0D, 2.0D);

        CLIENT_BUILDER.pop(); // npc_dialogue
    }

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

    // ==================== 天灾维度群系剔除 ====================
    // 本功能**没有任何配置项**：白名单与映射表都是硬编码的结构性决策。
    // 实现见 worldgen/DisasterBiomeSubstitution.java + worldgen/DisasterBiomeMapping.java
    // + mixin/CloneParameterListMixin.java（生成层）+ mixin/PossibleBiomesFilterMixin.java（查询层）。

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

    // ==================== 特效实体上限（跨模组修复） ====================
    // 传奇怪物的 camera_shake / dynamic_camera_zoom 是"裸 Entity + MobCategory.MISC +
    // 只能靠 tick() 自毁"的纯视觉特效实体。1.21.1 的 Entity#tickCount 由关卡写入
    // （ServerLevel#tickNonPassenger 里先 tickCount++ 才是 tick()），而实体 tick 另受
    // inEntityTickingRange 门控 ⇒ 不在实体刻范围内的实例永不被 tick、tickCount 永不增长、
    // discard() 永不执行，却仍留在 ChunkMap.entityMap 里，被"每个移动包跑一次"的
    // ChunkMap.move 全量遍历。实例上累积到 183 万个后，玩家登录时单 tick 超过 60 秒，
    // 被 ServerHangWatchdog 强杀（2026-10-06 悚域事故）。
    // 这里在守两条入世界路径，共用同一份账、同一个上限（判定只在服务端发生，见 perf/EffectEntityCap）：
    //   ① 生成入口：CameraShakeEntity#cameraShake / DynamicCameraZoomEntity#dynamicCameraZoom
    //      （mixin/legendarymonsters/ 下两个 Mixin）；
    //   ② 存档读盘：NeoForge EntityJoinLevelEvent（perf/EffectEntityJoinGate）——
    //      这一条负责"修复前就已堆积在旧存档里的存量"。实测某个旧存档单个区块里冻着 310,530 个
    //      camera_shake（外置实体文件 c.3.1.mcc 解压后 108 MB NBT），一进世界即卡死；
    //      读盘闸门超限即丢弃，且因为实体未登记，下次存盘时它就从文件里消失（存档自愈）。
    // 取名与默认值理由见 docs/plans/2026-10-06-lm-camera-shake-entity-flood-handover.md。
    //
    // ⚠️ 新增/改名配置项时同样必须补中英翻译键（见上方 dread_king_ritual 的说明）。

    public static final class EffectEntityCap {
        private EffectEntityCap() {}

        /** 总开关 */
        public static ModConfigSpec.BooleanValue enabled;
        /** 纳入上限的实体类型 ID 列表 */
        public static ModConfigSpec.ConfigValue<List<? extends String>> types;
        /** 每维度、每类型的在存量上限（生成入口与读盘闸门统一使用） */
        public static ModConfigSpec.IntValue maxPerDimension;
        /** 存量重采样间隔（ticks） */
        public static ModConfigSpec.IntValue rescanTicks;
        /** 抑制日志的节流间隔（ticks） */
        public static ModConfigSpec.IntValue logIntervalTicks;
    }

    /**
     * 太阳破防（《首领崛起》冥界骑士）相关开关。
     *
     * <p>消费方：{@code mixin/legendarymonsters/UnderworldKnightGuardBreakMixin}。
     * 判定入口是伤害类型标签 {@code beloong:underworld_knight_guard_break}
     * （见 {@code registry/ModDamageTypeTags}），目前由 Mowzie 的太阳三招供给伤害类型。
     *
     * <p>关联设计：{@code docs/plans/2026-10-09-underworld-knight-solar-guard-break-design.md}。
     */
    public static final class SolarGuardBreak {
        private SolarGuardBreak() {}

        /** 总开关：关闭后太阳伤害恢复"被护盾挡下"的原版行为 */
        public static ModConfigSpec.BooleanValue enabled;
    }

    static {
        COMMON_BUILDER.push("effect_entity_cap");

        EffectEntityCap.enabled = COMMON_BUILDER
                .comment("Cap concurrent effect entities per dimension (evaluated on the server only)",
                        "按维度限制特效实体的在存量上限（仅服务端生效）")
                .translation("beloong.configuration.effectEntityCapEnabled")
                .define("enabled", true);

        EffectEntityCap.types = COMMON_BUILDER
                .comment("Entity type ids under the cap; append other mods' bare effect entities as needed",
                        "纳入上限的实体类型 ID 列表；其它模组的同类裸特效实体可直接追加，无需改代码")
                .translation("beloong.configuration.effectEntityCapTypes")
                .defineList("types",
                        List.of(
                                "legendary_monsters:camera_shake",
                                "legendary_monsters:dynamic_camera_zoom"
                        ),
                        () -> "",
                        s -> s instanceof String str && str.contains(":"));

        EffectEntityCap.maxPerDimension = COMMON_BUILDER
                .comment("Max concurrent entities per dimension per listed type; used by the join gate (spawn pre-filter + load from disk) and as the per-pass budget of the frozen-residue sweep",
                        "每个维度、每种列出类型的在存量上限；入世界闸门（生成预筛 + 读盘）统一使用，同时作为\"冻结残骸清扫\"的每趟预算（正常 Boss 战同时在场的抖动只有几十个量级）")
                .translation("beloong.configuration.effectEntityCapMaxPerDimension")
                .defineInRange("maxPerDimension", 200, 1, 1024);

        EffectEntityCap.rescanTicks = COMMON_BUILDER
                .comment("How often the loaded-entity count is resampled, in ticks (only when a spawn or a disk load happens)",
                        "重新统计已加载实体数的间隔（ticks）；只在有召唤或读盘行为时才会统计")
                .translation("beloong.configuration.effectEntityCapRescanTicks")
                .defineInRange("rescanTicks", 20, 1, 1200);

        EffectEntityCap.logIntervalTicks = COMMON_BUILDER
                .comment("Throttle for the cap log lines, in ticks (1200 = one line per minute)",
                        "上限相关日志的节流间隔（ticks），默认 1200 = 每分钟一条")
                .translation("beloong.configuration.effectEntityCapLogIntervalTicks")
                .defineInRange("logIntervalTicks", 1200, 20, 72000);

        COMMON_BUILDER.pop(); // effect_entity_cap

        COMMON_BUILDER.push("solar_guard_break");

        SolarGuardBreak.enabled = COMMON_BUILDER
                .comment("Let Mowzie's sun damage (solar flare / solar beam / sun strike) pierce the Underworld Knight's shield, consuming one immune stack per hit",
                        "允许 Mowzie 的太阳伤害（太阳耀斑 / 太阳射线 / 太阳打击）击穿《首领崛起》冥界骑士的护盾，每次命中扣 1 层免疫层数")
                .translation("beloong.configuration.solarGuardBreakEnabled")
                .define("enabled", true);

        COMMON_BUILDER.pop(); // solar_guard_break
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

        /**
         * 「其他世界 → 龙宫」的落点参数（供 {@code teleport/TeleportTarget#toLoongPalace} 读取）。
         * <p>
         * 这三个值<b>只</b>描述落点；触发方式由调用方决定（技能、将来的传送门方块等）。
         * 目标维度 {@code beloong:loong_palace} 是编译期常量，不在这里配置。
         */
        public static ModConfigSpec.DoubleValue loongPalace_targetX;
        public static ModConfigSpec.DoubleValue loongPalace_targetZ;
        public static ModConfigSpec.DoubleValue loongPalace_fallbackY;
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
    //   - 在天灾维度进入传送门 → 1:1 坐标传送到主世界（见《天灾维度总设计》决策 35）

    public static final class DisasterPortal {
        private DisasterPortal() {}

        /** 激活传送门所需的 12 种眼球物品 ID（固定列表，不可扩展） */
        public static ModConfigSpec.ConfigValue<List<? extends String>> eyeItems;
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

    // ==================== dread_king_ritual ====================
    // 黯影宝库「死王仪式」：在指定结构内开启黯影宝库时，于宝库顶生成一个标记实体，
    // 播放 suspense 音效并在周围铺 blood_ground 血渍（共 140 tick），随后原地召唤不祥状态的死者之王。
    //
    // ⚠️ 新增/改名配置项时**必须同时补中英翻译键**，否则 NeoForge 配置界面会显示裸键名：
    //   节标题      `beloong.configuration.dread_king_ritual`
    //   节说明      `beloong.configuration.dread_king_ritual.tooltip`
    //   值标签      `beloong.configuration.<显式 translation 或值名>`
    //   值说明      `<值标签键>.tooltip`
    // 键名推导见 NeoForge `ConfigurationScreen#getTranslationKey`：
    // 显式 `.translation(x)` 优先，其次节级 translation，最后兜底 `modid.configuration.<末段名>`。
    // 实现见 dreadking/DreadKingRitualStarter.java + entity/DreadKingRitualMarker.java
    // + mixin/minecraft/DreadKingRitualTriggerMixin.java

    public static final class DreadKingRitual {
        private DreadKingRitual() {}

        /** 死王仪式总开关（关闭时宝库照常开箱，只是没有仪式） */
        public static ModConfigSpec.BooleanValue enabled;

        /** 触发仪式的限定结构 ID（单个，不支持标签） */
        public static ModConfigSpec.ConfigValue<String> structure;

        /**
         * 仪式音乐音量。
         * <p>
         * ⚠️ <b>这一个值同时决定「多响」与「多远」，无法只降其一。</b>
         * 服务端按 {@code SoundEvent.getRange(volume)} 决定把音效包发给谁
         * （{@code volume > 1 ? 16 × volume : 16} 格）；客户端也按同一 volume 计算线性衰减跨度
         * （{@code max(volume, 1) × attenuation_distance}）。
         * <p>
         * 默认 0.5 的由来：原版唱片机硬编码 {@code 4.0F}（见 {@code SimpleSoundInstance#forJukeboxSong}），
         * 而实测该音量听感偏大，故降到唱片机默认的 1/8；代价是可闻半径同时由 64 格收窄到 16 格
         * （已确认接受）。想要更大覆盖范围就调大它，半径随之线性增长。
         */
        public static ModConfigSpec.DoubleValue musicVolume;
    }


    static {
        // ========== npc_dialogue ==========
        // 触发判定在服务端（NpcDialogueHandler 在服务端受理右键），所以"要不要受理"是
        // 服务端决定。纯渲染参数（charsPerTick / nameScale）仍在 CLIENT_SPEC。
        // 语言键与客户端同名分组复用同一个，故模组菜单里分组标题仍是"简易 NPC 对话"。
        SERVER_BUILDER.push("npc_dialogue");

        NpcDialogue.enabled = SERVER_BUILDER
                .comment("Enable the simple NPC dialogue screen",
                        "启用简易 NPC 对话界面")
                .translation("beloong.configuration.npcDialogueEnabled")
                .define("enabled", true);

        SERVER_BUILDER.pop(); // npc_dialogue

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
        // 龙宫传送的<b>兜底安全网</b>：玩家在龙宫掉到触发 Y 以下时送返主世界出生点。
        // 主世界 → 龙宫的方向已于 2026-09-16 移除（原为"飞到 Y > 8848 自动传送"，
        // 而主世界 maxBuildHeight = 320 ⇒ 该条件永假，是死配置）。
        SERVER_BUILDER.push("dimension_transport");

        DimensionTransport.checkIntervalTicks = SERVER_BUILDER
                .comment("玩家 Y 坐标检查间隔（ticks），默认 20 = 每秒一次")
                .defineInRange("checkIntervalTicks", 20, 1, 1200);

        // 传送冷却不在这里配置：统一传送路径的冷却由 teleport/TeleportCooldown 硬编码（60 tick）。
        // 龙宫 Y<0 兜底传送是该冷却的<b>特例</b>——既不检查也不写它，故也没有配置项。

        // 「其他世界 → 龙宫」的落点。节名保持原样（历史沿用），但内容只服务落点：
        // 原先驱动它的「主世界飞到 Y > 8848 自动传送」已于 2026-09-16 删除（该条件在主世界恒为假）。
        SERVER_BUILDER.push("overworldToLoongPalace");
        DimensionTransport.loongPalace_targetX = SERVER_BUILDER
                .comment("龙宫侧落点的固定 X 坐标",
                        "本节的三个值只描述「其他世界 → 龙宫」的落点；触发方式由技能或传送门方块决定。")
                .defineInRange("targetX", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.loongPalace_targetZ = SERVER_BUILDER
                .comment("龙宫侧落点的固定 Z 坐标")
                .defineInRange("targetZ", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.loongPalace_fallbackY = SERVER_BUILDER
                .comment("高度图取不到落点时的兜底 Y 坐标（龙宫是纯虚空维度，该列没有方块，",
                        "因此高度图必然取不到——实际落点恒为该值）。")
                .defineInRange("fallbackY", 65.0, -2032.0, 2032.0);
        SERVER_BUILDER.pop();

        // 「龙宫 → 主世界」的兜底安全网已于 2026-09-16 改为硬编码：恒启用、触发线固定为 Y < 0，
        // 落点恒为世界出生点。故本节不再有配置项。

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

        // ========== dread_king_ritual ==========
        SERVER_BUILDER.push("dread_king_ritual");

        DreadKingRitual.enabled = SERVER_BUILDER
                .comment("Enable the Dark Vault dread-king ritual",
                        "启用黯影宝库死王仪式")
                .translation("beloong.configuration.dreadKingRitualEnabled")
                .define("enabled", true);

        DreadKingRitual.structure = SERVER_BUILDER
                .comment("Structure id that gates the ritual (single id, no tags)",
                        "触发仪式的限定结构 ID（单个，不支持标签）")
                .translation("beloong.configuration.dreadKingRitualStructure")
                .define("structure", "dragonsurvival:dragon_hunters_castle");

        DreadKingRitual.musicVolume = SERVER_BUILDER
                .comment("Ritual music volume; ALSO determines the audible radius (16 * max(volume, 1) blocks)",
                        "仪式音乐音量；同时决定可闻半径（16 × max(volume, 1) 格）",
                        "Vanilla jukebox uses 4.0F (64-block radius). 0.5 is 1/8 of that, i.e. a 16-block radius.",
                        "原版唱片机是 4.0F（64 格）。0.5 等于其 1/8，半径随之降为 16 格。")
                .translation("beloong.configuration.dreadKingRitualMusicVolume")
                .defineInRange("musicVolume", 0.5, 0.0, 16.0);

        SERVER_BUILDER.pop(); // dread_king_ritual
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
