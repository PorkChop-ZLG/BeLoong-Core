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

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // ==================== beloong_water ====================

    public static final class BeloongWater {
        private BeloongWater() {}

        public static ModConfigSpec.IntValue triggerCooldownSeconds;
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
        public static ModConfigSpec.ConfigValue<String> lpToOw_targetDimension;
        public static ModConfigSpec.DoubleValue lpToOw_targetX;
        public static ModConfigSpec.DoubleValue lpToOw_targetZ;
        public static ModConfigSpec.DoubleValue lpToOw_fallbackY;
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

    static {
        // ========== beloong_water ==========
        SERVER_BUILDER.push("beloong_water");

        BeloongWater.triggerCooldownSeconds = SERVER_BUILDER
                .comment("化龙池水冷却（秒）")
                .translation("beloong.configuration.beloongWaterCooldown")
                .defineInRange("beloongWaterCooldown", 10, 0, 3600);

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
                        s -> s instanceof String str && str.contains(":"));

        SERVER_BUILDER.pop(); // structure_effects
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
