package com.zonlong.beloong.structure;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;

/**
 * 结构药水效果配置条目。
 *
 * @param effect        药水效果的 Holder 引用
 * @param amplifier     效果等级（0 = I 级，对应原版 amplifier）
 * @param durationTicks 效果持续时间（tick）
 */
public record EffectEntry(Holder<MobEffect> effect, int amplifier, int durationTicks) {}
