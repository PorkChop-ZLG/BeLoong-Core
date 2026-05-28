package com.zonlong.beloong;

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
            .translation("config.beloong.fixGlowingItemInvisibility")
            .define("fixGlowingItemInvisibility", true);

    /** 修复稳定悬浮漂移（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("修复稳定悬浮漂移")
            .translation("config.beloong.fixStableHoverDrift")
            .define("fixStableHoverDrift", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== 通用配置 ====================

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    /** 龙之生存FTB区块兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue DS_FTBCHUNKS_COMPAT = COMMON_BUILDER
            .comment("龙之生存FTB区块兼容")
            .translation("config.beloong.dsFTBChunksCompat")
            .define("ds_ftbchunks_compat", true);

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // ==================== dimension_transport ====================

    /** dimension_transport 配置字段聚合 */
    public static final class DimensionTransport {
        private DimensionTransport() {}

        // 共享配置
        public static ModConfigSpec.IntValue checkIntervalTicks;
        public static ModConfigSpec.IntValue cooldownTicks;

        // overworld → loong_palace
        public static ModConfigSpec.BooleanValue owToLP_enabled;
        public static ModConfigSpec.IntValue owToLP_triggerY;
        public static ModConfigSpec.ConfigValue<String> owToLP_targetDimension;
        public static ModConfigSpec.DoubleValue owToLP_targetX;
        public static ModConfigSpec.DoubleValue owToLP_targetZ;
        public static ModConfigSpec.DoubleValue owToLP_fallbackY;

        // loong_palace → overworld
        public static ModConfigSpec.BooleanValue lpToOw_enabled;
        public static ModConfigSpec.IntValue lpToOw_triggerY;
        public static ModConfigSpec.ConfigValue<String> lpToOw_targetDimension;
        public static ModConfigSpec.DoubleValue lpToOw_targetX;
        public static ModConfigSpec.DoubleValue lpToOw_targetZ;
        public static ModConfigSpec.DoubleValue lpToOw_fallbackY;
    }

    static {
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
                .defineInRange("targetX", 0.0, -3.0E7, 3.0E7);
        DimensionTransport.owToLP_targetZ = SERVER_BUILDER
                .comment("目标固定 Z 坐标")
                .defineInRange("targetZ", 0.0, -3.0E7, 3.0E7);
        DimensionTransport.owToLP_fallbackY = SERVER_BUILDER
                .comment("高度图查找失败时的回退 Y 坐标")
                .defineInRange("fallbackY", 64.0, -2032.0, 2032.0);
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
                .defineInRange("targetX", 0.0, -3.0E7, 3.0E7);
        DimensionTransport.lpToOw_targetZ = SERVER_BUILDER
                .comment("目标固定 Z 坐标")
                .defineInRange("targetZ", 0.0, -3.0E7, 3.0E7);
        DimensionTransport.lpToOw_fallbackY = SERVER_BUILDER
                .comment("高度图查找失败时的回退 Y 坐标")
                .defineInRange("fallbackY", 64.0, -2032.0, 2032.0);
        SERVER_BUILDER.pop();

        SERVER_BUILDER.pop(); // dimension_transport
    }

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
