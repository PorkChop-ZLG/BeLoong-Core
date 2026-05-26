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
            .comment("修复：在龙之生存配置文件中开启 渲染口中持有物品 后，当口持物品并且身上有 发光 效果时，龙的身体部分会变成半透明")
            .translation("config.beloong.fixGlowingItemInvisibility")
            .define("fixGlowingItemInvisibility", true);

    /** 修复稳定悬停漂移（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = CLIENT_BUILDER
            .comment("修复：在龙之生存配置文件中开启 稳定悬浮 后，当使用俯冲结束后，龙依旧会向前缓慢滑翔；当开启创造模式飞行后，龙会自动向上或向下移动")
            .translation("config.beloong.fixStableHoverDrift")
            .define("fixStableHoverDrift", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT_BUILDER.build();

    // ==================== 通用配置 ====================

    private static final ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();

    /** 龙之生存FTB区块兼容（默认启用） */
    public static final ModConfigSpec.BooleanValue DS_FTBCHUNKS_COMPAT = COMMON_BUILDER
            .comment("兼容：在安装 FTB Chunks模组后，阻止龙之生存的技能效果（方块破坏、方块转化、作物收割、骨粉催熟、爆炸、放火）和远古龙的碾压、连锁破坏等行为破坏已认领区块（无视权限）")
            .translation("config.beloong.dsFTBChunksCompat")
            .define("ds_ftbchunks_compat", true);

    public static final ModConfigSpec COMMON_SPEC = COMMON_BUILDER.build();

    // ==================== 服务端配置 ====================

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();
}
