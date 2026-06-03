package com.zonlong.beloong.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * 天灾传送门框架的眼球类型枚举。
 * <p>
 * 实现了 {@link StringRepresentable}，使得 Minecraft 的 {@link net.minecraft.world.level.block.state.properties.EnumProperty}
 * 可以将其值序列化为字符串，用于 BlockState JSON 中的 {@code eye_type} 属性。
 * <p>
 * <b>13 个枚举值：</b>
 * <ul>
 *   <li>{@link #EMPTY} — 空框架（默认状态）</li>
 *   <li>{@link #ENDER_EYE} — 原版末影之眼（minecraft:ender_eye）</li>
 *   <li>{@link #MECH_EYE} ~ {@link #STORM_EYE} — 灾变模组（Cataclysm）的 9 种眼球</li>
 *   <li>{@link #EYE_OF_CHESED} ~ {@link #EYE_OF_GEBURAH} — 逆卡巴拉模组（FDBosses）的 3 种眼球</li>
 * </ul>
 * <p>
 * <b>序列化名称与 BlockState JSON 的对应关系：</b>
 * {@code getSerializedName()} 返回短键（如 "mech_eye"），
 * BlockState JSON 中的 {@code eye_type=mech_eye} 与该短键精确对应。
 * {@link #fromKey(String)} 实现反向查找。
 * <p>
 * 完整物品 ID 到短键的映射见 {@link com.zonlong.beloong.registry.ModBlocks#EYE_KEY_TO_FULL_ID}。
 *
 * @see com.zonlong.beloong.registry.ModBlocks#EYE_KEYS
 * @see DisasterPortalFrame
 */
public enum EyeType implements StringRepresentable {

    /** 空框架（无眼球） */
    EMPTY("empty"),

    // === 原版眼球 ===
    /** 原版末影之眼 */
    ENDER_EYE("ender_eye"),

    // === 灾变（Cataclysm）模组眼球 ===
    /** 机械之眼 */
    MECH_EYE("mech_eye"),
    /** 焰火之眼 */
    FLAME_EYE("flame_eye"),
    /** 虚空之眼 */
    VOID_EYE("void_eye"),
    /** 怪物之眼 */
    MONSTROUS_EYE("monstrous_eye"),
    /** 深渊之眼 */
    ABYSS_EYE("abyss_eye"),
    /** 荒漠之眼 */
    DESERT_EYE("desert_eye"),
    /** 诅咒之眼 */
    CURSED_EYE("cursed_eye"),
    /** 风暴之眼 */
    STORM_EYE("storm_eye"),

    // === 逆卡巴拉（FDBosses）模组眼球 ===
    /** 慈悲之眼（Chesed 质点） */
    EYE_OF_CHESED("eye_of_chesed"),
    /** 王国之眼（Malkuth 质点） */
    EYE_OF_MALKUTH("eye_of_malkuth"),
    /** 严厉之眼（Geburah 质点） */
    EYE_OF_GEBURAH("eye_of_geburah");

    /** BlockState 序列化时使用的短键名称（不可包含冒号） */
    private final String name;

    EyeType(String name) {
        this.name = name;
    }

    /**
     * Minecraft 属性系统通过此方法获取枚举值的字符串表示。
     * 该字符串用于 BlockState JSON 的 {@code eye_type=xxx} 值。
     */
    @Override
    @NotNull
    public String getSerializedName() {
        return this.name;
    }

    /**
     * 根据短键名称查找对应的 {@link EyeType} 枚举值。
     * 未找到时返回 {@link #EMPTY}。
     *
     * @param key 眼球短键（如 "mech_eye"）
     * @return 对应的枚举值，未找到则返回 EMPTY
     */
    public static EyeType fromKey(String key) {
        for (EyeType type : values()) {
            if (type.name.equals(key)) {
                return type;
            }
        }
        return EMPTY;
    }
}
