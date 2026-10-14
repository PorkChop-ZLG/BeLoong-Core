package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 「灵愈」能力本身——<b>纯数据包定义</b>（{@code data/beloong/dragonsurvival/dragon_ability/life_heal.json}）。
 *
 * <h3>这个类为什么几乎是空的</h3>
 * 与 {@link ExecuteAbility} 同一思路：技能的全部行为都由原版龙之生存的数据驱动字段表达，
 * 只有两样东西需要 Java 参与，且都不在这个类里：
 * <ul>
 *   <li><b>回血量</b>是一个固定值（不是百分比），所以用 {@link LifeHealEffect} 承载新字段
 *       {@code heal_amount}；</li>
 *   <li><b>施法期间挨打被打断</b>是原版没有的机制，所以有
 *       {@link LifeHealInterruptHandler} 监听伤害事件。</li>
 * </ul>
 * 而「长按施法、松手结束、魔力耗尽自动停止、每 N 刻扣一次魔力、每 N 刻回一次血、按成长值升级」
 * 全部由 JSON 里的 {@code dragonsurvival:channeled} 激活 + {@code continuous_mana_cost} +
 * {@code trigger_rate} + {@code dragonsurvival:dragon_growth} 升级表达，<b>无需额外代码</b>。
 *
 * <h3>为什么 {@link #KEY} 是 public</h3>
 * {@link LifeHealInterruptHandler} 需要判断「玩家正在引导的是不是这个技能」，
 * 判据是拿 {@code getCurrentlyCasting().key()} 与本 KEY 比较。
 */
public final class LifeHealAbility {

    /**
     * 能力 ID：{@code beloong:life_heal}。
     * <b>必须</b>与 JSON 路径 {@code data/beloong/dragonsurvival/dragon_ability/life_heal.json} 一致，
     * 否则 {@code MagicData.getAbility(KEY)} 永远返回 null，技能界面上什么都不显示。
     */
    public static final ResourceKey<DragonAbility> KEY = ResourceKey.create(
            DragonAbility.REGISTRY,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "life_heal")
    );

    private LifeHealAbility() {}

    /** 该能力实例是否是「灵愈」本身。 */
    public static boolean isLifeHeal(final DragonAbilityInstance instance) {
        return instance != null && KEY.equals(instance.key());
    }
}
