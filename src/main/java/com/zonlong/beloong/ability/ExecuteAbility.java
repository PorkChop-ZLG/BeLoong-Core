package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.common.codecs.ability.ActionContainer;
import by.dragonsurvivalteam.dragonsurvival.registry.attachments.MagicData;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbility;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.targeting.AbilityTargeting;
import com.zonlong.beloong.BeLoongCore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import org.jetbrains.annotations.Nullable;

/**
 * 「斩杀」被动技能的查询入口：把「玩家 → 能力等级 → 全部数值」这三步收口到一处。
 *
 * <p>能力本身是一个纯数据包定义（{@code data/beloong/dragonsurvival/dragon_ability/execute.json}），
 * 数值全部写在它的 {@link ExecuteEffect} 字段里。本类负责从能力定义里把它们取回来，
 * 供标记方（{@code ExecuteMarkHandler}）与结算方（{@code ExecuteThresholdEffect}）共用。</p>
 *
 * <p>注意能力是<b>每个龙种各存一份</b>的（{@code MagicData#getAbilities} 按当前物种分桶），
 * 所以 {@link #config(Player)} 天然只在玩家是龙、且当前物种拥有该技能时才返回非空。</p>
 */
public final class ExecuteAbility {

    /** 能力 ID：{@code beloong:execute}。必须与 JSON 路径 {@code data/beloong/dragonsurvival/dragon_ability/execute.json} 一致。 */
    public static final ResourceKey<DragonAbility> KEY = ResourceKey.create(
            DragonAbility.REGISTRY,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "execute"));

    private ExecuteAbility() {}

    /**
     * 斩杀技能的当前数值快照。
     *
     * @param level        当前等级（≥ 1）
     * @param damage       斩杀伤害
     * @param threshold    斩杀线（百分比数值，3.0 = 3.0%）
     * @param cooldown     触发后的冷却（刻）
     * @param markDuration 斩杀线标记时长（刻）
     */
    public record Config(int level,
                         LevelBasedValue damage,
                         LevelBasedValue threshold,
                         LevelBasedValue cooldown,
                         LevelBasedValue markDuration) {}

    /**
     * 玩家可用（已学习且未被禁用）的斩杀能力实例；不可用时返回 {@code null}。
     *
     * <p>非龙玩家同样走这条路：{@code MagicData#getAbility} 在 {@code currentSpecies == null} 时
     * 直接返回 {@code null}，不需要额外判 {@code DragonStateProvider}。</p>
     */
    public static @Nullable DragonAbilityInstance instance(Player player) {
        DragonAbilityInstance instance = MagicData.getData(player).getAbility(KEY);
        return instance != null && instance.isUsable() ? instance : null;
    }

    /** 玩家当前的斩杀数值；不可用时返回 {@code null}。 */
    public static @Nullable Config config(Player player) {
        DragonAbilityInstance instance = instance(player);
        if (instance == null) {
            return null;
        }

        ExecuteEffect effect = effect(instance);
        if (effect == null) {
            // 能力被数据包改坏了（行动里没有 beloong:execute）——静默降级，不影响其它系统。
            return null;
        }

        return new Config(instance.level(),
                effect.damage(),
                effect.threshold(),
                effect.cooldown(),
                effect.markDuration());
    }

    /** 从能力定义里取出 {@link ExecuteEffect}（行动里的第一个）。 */
    public static @Nullable ExecuteEffect effect(DragonAbilityInstance instance) {
        for (ActionContainer action : instance.value().actions()) {
            // target_selection 是 Either<BlockTargeting, EntityTargeting>，这里只要实体那一支。
            var entityTargeting = action.effect().target().right();
            if (entityTargeting.isEmpty()) {
                continue;
            }

            for (var effect : entityTargeting.get().effects()) {
                if (effect instanceof ExecuteEffect execute) {
                    return execute;
                }
            }
        }

        return null;
    }

    /** 便于外部（工具提示 / 调试）拿到目标类型，避免重复 import。 */
    public static @Nullable AbilityTargeting.EntityTargeting entityTargeting(DragonAbilityInstance instance) {
        for (ActionContainer action : instance.value().actions()) {
            var targeting = action.effect().target().right();
            if (targeting.isPresent()) {
                return targeting.get();
            }
        }

        return null;
    }
}
