package com.zonlong.beloong;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件。分为通用、客户端和服务端三类：
 * <ul>
 *   <li>通用配置 — 两端均需加载，服务端同步至客户端</li>
 *   <li>客户端配置 — 仅影响本地渲染和输入，单人/多人均可在本地修改</li>
 *   <li>服务端配置 — 空占位，预留给未来需要服务端同步的配置项</li>
 * </ul>
 */
public class Config {

    // ==================== 客户端配置 ====================

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    /** 修复发光效果导致身体透明（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = CLIENT_BUILDER
            .comment("修复发光效果导致身体透明")
            .define("fixGlowingItemInvisibility", true);

    /** 修复稳定悬浮漂移（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("修复稳定悬浮漂移")
            .define("fixStableHoverDrift", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== 通用配置 ====================

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    /** 龙之生存FTB区块兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue DS_FTBCHUNKS_COMPAT = COMMON_BUILDER
            .comment("龙之生存FTB区块兼容")
            .define("ds_ftbchunks_compat", true);

    /** 修复财宝堆复制（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_TREASURE_DUPLICATION = COMMON_BUILDER
            .comment("移除财宝堆重力下落行为，从根源杜绝刷沙机复制财宝堆")
            .define("fixTreasureDuplication", true);

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

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
        public static ModConfigSpec.ConfigValue<String> lpToOw_targetDimension;
        public static ModConfigSpec.DoubleValue lpToOw_targetX;
        public static ModConfigSpec.DoubleValue lpToOw_targetZ;
        public static ModConfigSpec.DoubleValue lpToOw_fallbackY;
    }

    // ==================== treasure_growth ====================

    public static final class TreasureGrowth {
        private TreasureGrowth() {}

        public static ModConfigSpec.BooleanValue enabled;
        public static ModConfigSpec.ConfigValue<List<? extends String>> treasureWeights;
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
    //     + 在目标位置生成返回传送门结构模板（.nbt 文件）
    //   - 在天灾维度进入传送门 → 传送到主世界玩家重生点（原版末地逻辑）
    //
    // 结构模板说明：
    //   - 存储位置：data/beloong/structure/disaster/return_portal.nbt
    //   - structureOffsetX/Y/Z 用于微调结构相对玩家落地位置的偏移

    public static final class DisasterPortal {
        private DisasterPortal() {}

        /** 激活传送门所需的 12 种眼球物品 ID（固定列表，不可扩展） */
        public static ModConfigSpec.ConfigValue<List<? extends String>> eyeItems;
        /** 返回传送门结构模板的资源路径（如 beloong:disaster/return_portal） */
        public static ModConfigSpec.ConfigValue<String> returnStructureTemplate;
        /** 传送后的冷却时间（ticks），防止玩家在传送门中来回弹跳 */
        public static ModConfigSpec.IntValue teleportCooldownTicks;
        /** 结构模板放置位置相对于玩家传送坐标的 X 偏移（方块坐标） */
        public static ModConfigSpec.IntValue structureOffsetX;
        /** 结构模板放置位置相对于地表高度的 Y 偏移（方块坐标） */
        public static ModConfigSpec.IntValue structureOffsetY;
        /** 结构模板放置位置相对于玩家传送坐标的 Z 偏移（方块坐标） */
        public static ModConfigSpec.IntValue structureOffsetZ;
    }

    static {
        // ========== treasure_growth ==========
        SERVER_BUILDER.push("treasure_growth");

        TreasureGrowth.enabled = SERVER_BUILDER
                .comment("是否启用财宝堆成长加速")
                .define("enabled", true);

        TreasureGrowth.treasureWeights = SERVER_BUILDER
                .comment(
                        "财宝方块权重，格式: modid:block_id=weight",
                        "未列出的方块默认权重 1.0",
                        "内置默认: debris=5, diamond=4, emerald=3, gold=2, iron=1, copper=0.5"
                )
                .defineList("treasureWeights", List.of(
                        "dragonsurvival:copper_dragon_treasure=0.5",
                        "dragonsurvival:iron_dragon_treasure=1.0",
                        "dragonsurvival:gold_dragon_treasure=2.0",
                        "dragonsurvival:emerald_dragon_treasure=3.0",
                        "dragonsurvival:diamond_dragon_treasure=4.0",
                        "dragonsurvival:debris_dragon_treasure=5.0"
                ), s -> s instanceof String str && str.contains("="));

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
        DimensionTransport.lpToOw_targetDimension = SERVER_BUILDER
                .comment("目标维度 ID")
                .define("targetDimension", "minecraft:overworld");
        DimensionTransport.lpToOw_targetX = SERVER_BUILDER
                .comment("目标固定 X 坐标")
                .defineInRange("targetX", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.lpToOw_targetZ = SERVER_BUILDER
                .comment("目标固定 Z 坐标")
                .defineInRange("targetZ", 0.5, -3.0E7, 3.0E7);
        DimensionTransport.lpToOw_fallbackY = SERVER_BUILDER
                .comment("高度图查找失败时的回退 Y 坐标")
                .defineInRange("fallbackY", 64.5, -2032.0, 2032.0);
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
                        s -> s instanceof String str && str.contains(":"));

        DisasterPortal.returnStructureTemplate = SERVER_BUILDER
                .comment("返回传送门结构模板 ID")
                .define("returnStructureTemplate", "beloong:disaster/return_portal");

        DisasterPortal.teleportCooldownTicks = SERVER_BUILDER
                .comment("传送冷却时间（ticks），防止循环传送")
                .defineInRange("teleportCooldownTicks", 100, 0, 72000);

        // 结构放置的 X 偏移。
        // 正数向东偏移，负数向西偏移。
        // 默认 0 表示结构原点对准玩家落地位置的 X 坐标。
        DisasterPortal.structureOffsetX = SERVER_BUILDER
                .comment("返回结构相对于玩家传送坐标的 X 偏移")
                .defineInRange("structureOffsetX", 0, -1000, 1000);

        // 结构放置的 Y 偏移。
        // 正数向上偏移，负数向下偏移。
        // 默认 1 表示结构放置在当前地形的最高方块之上 1 格处。
        DisasterPortal.structureOffsetY = SERVER_BUILDER
                .comment("返回结构相对于玩家传送坐标的 Y 偏移")
                .defineInRange("structureOffsetY", 1, -1000, 1000);

        // 结构放置的 Z 偏移。
        // 正数向南偏移，负数向北偏移。
        // 默认 0 表示结构原点对准玩家落地位置的 Z 坐标。
        DisasterPortal.structureOffsetZ = SERVER_BUILDER
                .comment("返回结构相对于玩家传送坐标的 Z 偏移")
                .defineInRange("structureOffsetZ", 0, -1000, 1000);

        SERVER_BUILDER.pop(); // disaster_portal
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
