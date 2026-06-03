package com.zonlong.beloong.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * 天灾传送门框架的眼球类型枚举 — 通用数字槽位。
 * <p>
 * 实现了 {@link StringRepresentable}，使得 Minecraft 的 {@link net.minecraft.world.level.block.state.properties.EnumProperty}
 * 可以将其值序列化为字符串，用于 BlockState JSON 中的 {@code eye_type} 属性。
 * <p>
 * <b>13 个枚举值：</b>
 * <ul>
 *   <li>{@link #EMPTY} — 空框架（默认状态），序列化名 "0"</li>
 *   <li>{@link #SLOT_1} ~ {@link #SLOT_12} — 12 个通用槽位，序列化名 "1"~"12"</li>
 * </ul>
 * <p>
 * 槽位与配置的关系：{@code Config.DisasterPortal.eyeItems} 列表的第 N 项（N=0~11）
 * 对应槽位 N+1（即 {@code SLOT_1} ~ {@code SLOT_12}）。
 * <p>
 * 纹理资源：{@code disaster_portal_frame_top_N.png} 和 {@code disaster_portal_frame_eye_N.png}，
 * 模型 {@code disaster_portal_frame_N.json}。
 *
 * @see DisasterPortalFrame
 * @see com.zonlong.beloong.registry.ModBlocks#getEyeSlot
 */
public enum EyeType implements StringRepresentable {

    /** 空框架（无眼球），序列化名 "0" */
    EMPTY("0"),

    /** 槽位 1~12，对应配置 eyeItems[0]~[11] */
    SLOT_1("1"),
    SLOT_2("2"),
    SLOT_3("3"),
    SLOT_4("4"),
    SLOT_5("5"),
    SLOT_6("6"),
    SLOT_7("7"),
    SLOT_8("8"),
    SLOT_9("9"),
    SLOT_10("10"),
    SLOT_11("11"),
    SLOT_12("12");

    /** BlockState 序列化时使用的数字编号（"0"~"12"） */
    private final String name;

    EyeType(String name) {
        this.name = name;
    }

    @Override
    @NotNull
    public String getSerializedName() {
        return this.name;
    }

    /**
     * 根据数字编号字符串查找对应的枚举值。
     * 未找到时返回 {@link #EMPTY}。
     *
     * @param key 数字编号字符串（如 "5"）
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
