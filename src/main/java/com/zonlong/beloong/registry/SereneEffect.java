package com.zonlong.beloong.registry;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * 气定神闲效果——提升法力回复速度。
 *
 * <p>每级 +0.001 到 {@code dragonsurvival:mana_regeneration} 属性（ADD_VALUE）。
 * 属性修饰符在 {@link ModMobEffects} 注册时通过
 * {@code addAttributeModifier} 链接。</p>
 */
public class SereneEffect extends MobEffect {

    public SereneEffect() {
        super(MobEffectCategory.BENEFICIAL, 0x87CEEB);
    }
}
