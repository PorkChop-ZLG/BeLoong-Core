package com.zonlong.beloong;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FIX_GLOWING_OUTLINE = BUILDER
            .comment("修复手持发光物品时龙身体变隐形的问题")
            .define("fixGlowingOutline", true);

    public static final ModConfigSpec.BooleanValue FIX_STABLE_HOVER = BUILDER
            .comment("修复稳定悬停时的漂移问题（生存模式水平漂移，创造模式向上漂移）")
            .define("fixStableHover", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
