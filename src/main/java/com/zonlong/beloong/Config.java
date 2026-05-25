package com.zonlong.beloong;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件。使用 NeoForge {@link ModConfigSpec} 系统，
 * 配置项通过 {@code ModConfigSpec.Builder} 声明后注册到模组容器。
 */
public class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 修复手持发光物品时龙身体变隐形的问题（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = BUILDER
            .comment("修复手持发光物品时龙身体变隐形的问题")
            .define("fixGlowingOutline", true);

    /** 修复稳定悬停时漂移——生存模式水平、创造模式向上（默认启用） */
    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = BUILDER
            .comment("修复稳定悬停时的漂移问题（生存模式水平漂移，创造模式向上漂移）")
            .define("fixStableHover", true);

    /** 由 {@code BUILDER.build()} 生成，包含所有配置项的不可变规范 */
    public static final ModConfigSpec SPEC = BUILDER.build();
}
