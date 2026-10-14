package com.zonlong.beloong.ability;

import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.DragonAbilityInstance;
import by.dragonsurvivalteam.dragonsurvival.registry.dragon.ability.entity_effects.AbilityEntityEffect;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;

import java.util.List;

/**
 * 「灵愈」的技能效果（{@code effect_type: beloong:life_heal}）——<b>固定数值回血</b>。
 *
 * <h3>为什么不直接用原版的 {@code dragonsurvival:heal}</h3>
 * 原版 {@code HealEffect} 唯一的字段是 {@code percentage}，回血量 =
 * {@code 目标最大生命 × 百分比}。本技能要求「1 级每 10 刻回 1 点生命」这种<b>固定数值</b>，
 * 用百分比表示就得写 {@code 1 / 最大生命}——每个玩家最大生命不同，20 点生命写 0.3% 只回 0.06，
 * 换算关系（1 魔力 = 10 生命）根本无法成立。所以这里只把「回多少」换成一个固定值字段，
 * 其余全部沿用原版：持续施法、每 tick 扣魔、{@code trigger_rate} 决定多少刻回一次，
 * 都是 {@code life_heal.json} 里用原版数据驱动配置写的，不需要 Java 代码参与。
 *
 * <h3>与 {@code HealEffect} 的两处刻意不同</h3>
 * <ul>
 *   <li><b>满血时直接不回复</b>（见 {@link #apply}）。{@link LivingEntity#heal} 本来就会把结果夹在
 *       最大生命以内，但那样「消耗了魔力、回血量为 0」时看不出任何原因；
 *       这里显式提前返回，并顺手把单次回血量夹到缺口大小，避免大数值溢出成无效治疗。</li>
 *   <li><b>0 血时不回血</b>：原版 {@code heal} 对已死实体无效，
 *       且本技能靠 {@code LivingIncomingDamageEvent} 打断，死亡时施法早已停止。
 *       因此不像 {@code HealEffect} 那样在 {@code getHealth() <= 0} 时特殊处理——
 *       本技能不该有「把尸体拉起来」的语义。</li>
 * </ul>
 *
 * @param healAmount 每次触发回复的生命值（固定值，按技能等级取值）
 */
public record LifeHealEffect(LevelBasedValue healAmount) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（与 AirStrikeEffect / TornadoEffect / ExecuteEffect 同一取舍）。 */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float) (double) d)),
            Either::left
    );

    public static final MapCodec<LifeHealEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("heal_amount").forGetter(LifeHealEffect::healAmount)
    ).apply(instance, LifeHealEffect::new));

    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        if (!(target instanceof LivingEntity living)) {
            return;
        }

        // 已死实体不复活：与「把魔力换成血」的语义不符，见类注释。
        if (living.getHealth() <= 0) {
            return;
        }

        // 满血时不回复。
        //
        // 为什么不用能力 JSON 的 `usage_blocked` 来「禁止满血施法」：原版实体谓词
        // （1.21.1 的 `net.minecraft.advancements.critereon.EntityPredicate` / `PlayerPredicate`
        // 只有 type / nbt / flags / equipment / vehicle / passenger / targeted_entity /
        // stepping_on / movement_affected_by / location / effects / type_specific 这些字段，
        // 其中 PlayerPredicate 只有 level / gamemode / stats / recipes / advancements / looking_at）
        // **没有任何能表达「当前生命 < 最大生命」的条件**，做不到。
        // 而用 `usage_blocked` 屏蔽整个技能会更糟：它把技能标成「不可用」并在生效时直接把施法打断，
        // 玩家满血按下只是没有收益，不该被判定成技能失效。
        //
        // 注意：这里只拦「回血」一步，魔力仍然照常消耗——每 tick 的魔力是由 channeled 激活的
        // `continuous_mana_cost` 扣的，独立于本效果。也就是满血时施法会空烧魔力。
        // 这是刻意保留的行为：魔力耗尽是原版 channeled 的常规停止路径，
        // 玩家自己松手即可，不需要额外机制介入。
        float health = living.getHealth();
        float maxHealth = living.getMaxHealth();

        if (health >= maxHealth) {
            return;
        }

        // 把单次回血量夹到缺口大小，与 HealEffect 的取舍一致：
        // 不改变实际结果（heal 也会夹），但让「超量治疗」的意图在代码里显式可见。
        living.heal(Math.min(healAmount.calculate(ability.level()), maxHealth - health));
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        // 未学习（等级 0）时按 1 级展示；LevelBasedValue.Lookup 不接受 level 0（会索引 values.get(-1) 崩溃）。
        int level = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());

        return List.of(
                Component.translatable("dragon_ability.beloong.life_heal.dynamic_desc",
                        String.format("%.1f", healAmount.calculate(level)))
        );
    }
}
