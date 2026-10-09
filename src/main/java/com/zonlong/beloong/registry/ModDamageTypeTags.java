package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;

/**
 * 本模组用到的<b>伤害类型 tag</b> 常量。
 *
 * <p>目前只有一个：{@link #UNDERWORLD_KNIGHT_GUARD_BREAK} ——
 * 「可以击穿〈首领崛起〉冥界骑士（赫尔瓦）护盾的伤害」。
 * 全部内容都在一个文件里（{@code data/beloong/tags/damage_type/underworld_knight_guard_break.json}）：
 * 目前列了 {@code mowziesmobs} 的三个太阳伤害类型
 * （{@code solar_flare} / {@code solar_beam} / {@code sun_strike}，注册文件由本模组提供）。
 *
 * <p><b>为什么用 tag 而不是在代码里写死类型判断</b>：这个 tag 就是「魔改入口」。
 * 任何整合包 / 数据包 / 模组只要把自己的伤害类型追加进来
 * （{@code data/beloong/tags/damage_type/underworld_knight_guard_break.json}，默认 {@code "replace": false} 即并集，
 * 或用 KubeJS），冥界骑士侧<b>不需要改一行代码</b>就会把它当作破防伤害。
 *
 * <p><b>条目的 {@code required} 语义（与 {@link ModEntityTypeTags} 里那段警告同理）</b>：
 * 原版 {@code TagLoader} 对缺失的 <b>required</b> 条目会直接让整条 tag 失败
 * （{@code "Couldn't load tag {} as it is missing following references: {}"}）。
 * 本 tag 里现有的三个 id 是<b>本模组自己注册</b>的伤害类型（只是借用 {@code mowziesmobs} 命名空间，
 * 见 {@code compat/mowziesmobs/SolarDamageTypes}），因此恒存在，用默认的 {@code required: true} 即可。
 * <b>若将来要往这里追加别的模组的类型，务必写 {@code {"id": "...", "required": false}}</b>，
 * 否则那个模组缺席时整条 tag（连同本模组的破防功能）都会一起加载失败。
 *
 * <p><b>静默失败面</b>：tag 缺失或为空时<b>不会崩</b>，只是骑士恢复「太阳伤害打不动」的原状
 * （{@code DamageSource#is(TagKey)} 对空 tag 恒为 {@code false}）。
 */
public final class ModDamageTypeTags {

    /**
     * 可以击穿冥界骑士护盾的伤害类型。
     *
     * <p>由 {@code mixin/legendarymonsters/UnderworldKnightGuardBreakMixin} 消费：
     * 命中该 tag 的伤害会被当作「打中冥界印记」处理 —— 走
     * {@code processHurt(source, amount, true)}（无视护盾全额结算），
     * 并按设计每次命中扣 1 层免疫层数（只扣 1 层，不强制倒地）。
     */
    public static final TagKey<DamageType> UNDERWORLD_KNIGHT_GUARD_BREAK = TagKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "underworld_knight_guard_break"));

    private ModDamageTypeTags() {}
}
