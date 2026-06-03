package com.zonlong.beloong.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

public enum EyeType implements StringRepresentable {
    EMPTY("empty"),
    ENDER_EYE("ender_eye"),
    MECH_EYE("mech_eye"),
    FLAME_EYE("flame_eye"),
    VOID_EYE("void_eye"),
    MONSTROUS_EYE("monstrous_eye"),
    ABYSS_EYE("abyss_eye"),
    DESERT_EYE("desert_eye"),
    CURSED_EYE("cursed_eye"),
    STORM_EYE("storm_eye"),
    EYE_OF_CHESED("eye_of_chesed"),
    EYE_OF_MALKUTH("eye_of_malkuth"),
    EYE_OF_GEBURAH("eye_of_geburah");

    private final String name;

    EyeType(String name) {
        this.name = name;
    }

    @Override
    @NotNull
    public String getSerializedName() {
        return this.name;
    }

    public static EyeType fromKey(String key) {
        for (EyeType type : values()) {
            if (type.name.equals(key)) {
                return type;
            }
        }
        return EMPTY;
    }
}
