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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.LevelBasedValue;

import java.util.List;

/**
 * 「斩杀」被动的能力效果（{@code effect_type: beloong:execute}）——<b>数值载体</b>。
 *
 * <h3>它为什么几乎什么都不做</h3>
 * 这个效果存在的意义有两个，都不在 {@link #apply} 里：
 * <ol>
 *   <li><b>承载全部数值</b>。能力 JSON 的 {@code actions[].target_selection.applied_effects.entity_effect[]}
 *       是 DS 唯一允许挂自定义字段的位置，因此伤害、斩杀线曲线、冷却曲线、标记时长都写在这里，
 *       由 {@link ExecuteAbility#config(Player)} 从能力的定义里取回。</li>
 *   <li><b>提供动态描述</b>。DS 的技能工具提示只从 {@code actions} 里取动态数值
 *       （{@code DragonAbility#getInfo} 遍历 {@code actions}）；如果 {@code actions} 为空数组，
 *       界面里就只剩 {@code .desc} 静态文案，看不到当前等级的斩杀线 / 冷却 / 伤害。</li>
 * </ol>
 *
 * <h3>为什么不按 {@code on_target_hit} 行动来标记</h3>
 * DS 的 {@code OnTargetHit} 触发只会调 {@code ability.tick(player)}，<b>被击中的实体不会传给行动</b>；
 * 行动只能按自己的 {@code target_selection} 重新找目标 —— {@code looking_at} 对远程攻击必然打偏，
 * {@code area}/{@code disc} 又会波及无关生物。因此标记与结算分别放在
 * {@link com.zonlong.beloong.registry.ExecuteMarkHandler}（{@code LivingDamageEvent.Post}，
 * 直接拿 {@code event.getEntity()}）与
 * {@link com.zonlong.beloong.registry.ExecuteThresholdEffect}（受害者身上的每 tick 判定）里。
 *
 * <p>为了让这个「空壳行动」的代价可忽略，能力 JSON 把它压到 {@code trigger_rate: 20}
 * （每 20 刻才 {@code apply} 一次）。</p>
 *
 * @param damage       斩杀伤害（真实伤害，默认 999999）
 * @param threshold    斩杀线（百分比数值，例如 3.0 表示 3.0%）
 * @param cooldown     触发斩杀后的冷却（刻）
 * @param markDuration 斩杀线标记的持续时间（刻）
 */
public record ExecuteEffect(
        LevelBasedValue damage,
        LevelBasedValue threshold,
        LevelBasedValue cooldown,
        LevelBasedValue markDuration
) implements AbilityEntityEffect {

    /** 同时支持纯数字和 LevelBasedValue 对象格式（与 AirStrikeEffect / TornadoEffect 同一取舍）。 */
    private static final Codec<LevelBasedValue> FLEXIBLE_LBV = Codec.either(
            LevelBasedValue.CODEC,
            Codec.DOUBLE
    ).xmap(
            either -> either.map(lbv -> lbv, d -> LevelBasedValue.constant((float) (double) d)),
            Either::left
    );

    public static final MapCodec<ExecuteEffect> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FLEXIBLE_LBV.fieldOf("damage").forGetter(ExecuteEffect::damage),
            FLEXIBLE_LBV.fieldOf("threshold").forGetter(ExecuteEffect::threshold),
            FLEXIBLE_LBV.fieldOf("cooldown").forGetter(ExecuteEffect::cooldown),
            FLEXIBLE_LBV.fieldOf("mark_duration").forGetter(ExecuteEffect::markDuration)
    ).apply(instance, ExecuteEffect::new));

    /**
     * 说明载体：真正的标记与结算都不在这里，见类注释。
     * 刻意保持空实现——这个行动每 20 刻被 {@code dragonsurvival:self} 调一次。
     */
    @Override
    public void apply(final ServerPlayer dragon, final DragonAbilityInstance ability, final Entity target) {
        // no-op by design
    }

    @Override
    public MapCodec<? extends AbilityEntityEffect> entityCodec() {
        return CODEC;
    }

    @Override
    public List<MutableComponent> getDescription(final Player dragon, final DragonAbilityInstance ability) {
        // 未学习（等级 0）时按 1 级展示；Lookup 不接受 level 0。
        int level = Math.max(DragonAbilityInstance.MIN_LEVEL_FOR_CALCULATIONS, ability.level());
        float percent = threshold.calculate(level);
        float seconds = cooldown.calculate(level) / 20.0F;

        return List.of(
                Component.translatable("dragon_ability.beloong.execute.dynamic_desc",
                        String.format("%.1f%%", percent),
                        String.format("%.2f", seconds),
                        String.format("%.0f", damage.calculate(level)),
                        String.format("%.1f", markDuration.calculate(level) / 20.0F))
        );
    }
}
