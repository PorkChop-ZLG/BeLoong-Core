package com.zonlong.beloong.compat.mowziesmobs;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

/**
 * Mowzie's Mobs 兼容辅助方法。
 *
 * <p>目前只负责判断一次伤害是否来自“玩家手持原版重锤的近战攻击”。
 * 该判断用于让钢铁守护者（Ferrous Wroughtnaut）在已激活且处于战斗中时，
 * 可以被原版重锤绕过默认免伤造成正常伤害。</p>
 */
public final class MowzieMobsCompat {
    private MowzieMobsCompat() {
    }

    /**
     * 判断伤害来源是否为玩家手持 {@link Items#MACE} 的原版近战攻击。
     *
     * <p>通过 {@link DamageTypes#PLAYER_ATTACK} 限定为玩家普通近战，
     * 通过 {@link DamageSource#getDirectEntity()} 排除箭/弹射物等间接来源。</p>
     *
     * @param source 伤害来源
     * @return 如果是玩家手持重锤的近战攻击则返回 {@code true}
     */
    public static boolean isMaceAttack(DamageSource source) {
        // 只接受原版玩家近战伤害类型，避免爆炸等其他 directEntity=Player 的伤害误判
        if (!source.is(DamageTypes.PLAYER_ATTACK)) {
            return false;
        }

        if (source.getDirectEntity() instanceof Player player) {
            return player.getMainHandItem().is(Items.MACE);
        }

        return false;
    }
}
