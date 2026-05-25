package com.zonlong.beloong;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件。分为客户端和服务端两类：
 * <ul>
 *   <li>客户端配置 — 仅影响本地渲染和输入，单人/多人均可在本地修改</li>
 *   <li>服务端配置 — 空占位，预留给未来需要服务端同步的配置项</li>
 * </ul>
 */
public class Config {

    // ==================== 客户端配置 ====================

    private static final ModConfigSpec.Builder CLIENT_BUILDER = new ModConfigSpec.Builder();

    /** 修复手持发光物品时龙身体变隐形的问题（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = CLIENT_BUILDER
            .comment("修复手持发光物品时龙身体变隐形的问题")
            .define("fixGlowingOutline", true);

    /** 修复稳定悬停时漂移——生存模式水平、创造模式向上（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("修复稳定悬停时的漂移问题（生存模式水平漂移，创造模式向上漂移）")
            .define("fixStableHover", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    // 暂无服务端配置项，保留空配置用于未来拓展

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
