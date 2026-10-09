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
 * 内容全在一个文件里（{@code data/beloong/tags/damage_type/underworld_knight_guard_break.json}）：
 * 目前列了 {@code mowziesmobs} 的<b>四个</b>太阳伤害类型
 * （{@code solar_flare} / {@code solar_beam} / {@code sun_strike} / {@code supernova}；
 * 注册文件由本模组提供，见 {@code compat/mowziesmobs/SolarDamageTypes}）。
 *
 * <p><b>为什么用 tag 而不是在代码里写死类型判断</b>：这个 tag 就是「魔改入口」。
 * 任何整合包 / 数据包 / 模组只要把自己的伤害类型追加进来
 * （{@code data/beloong/tags/damage_type/underworld_knight_guard_break.json}，默认 {@code "replace": false} 即并集，
 * 或用 KubeJS），冥界骑士侧<b>不需要改一行代码</b>就会把它当作破防伤害。
 *
 * <p><b>条目的 {@code required} 语义（与 {@link ModEntityTypeTags} 里那段警告同理）</b>：
 * 原版 {@code TagLoader} 对缺失的 <b>required</b> 条目会让<b>整条 tag</b>加载失败
 * （{@code "Couldn't load tag {} as it is missing following references: {}"}）。
 * 本 tag 里那四个 id 都是本模组自己注册的类型（只是借用 {@code mowziesmobs} 命名空间）⇒ 恒存在；
 * 但仍按项目纪律写成 {@code {"id": "...", "required": false}}：万一命名空间被更高优先级的数据包遮蔽，
 * tag 只会少一条，而不是整条失效。<b>往这里追加别的模组的类型时同样必须写 {@code "required": false}</b>。
 *
 * <p><b>⚠️ 不要把 {@code minecraft:player_attack} 之类的通用原版类型加进来</b>：
 * ① 那等于让所有玩家近战都能破防（设计红线，见调研文档 §11.5）；
 * ② 骑士类内部有一处<b>自我调用</b> —— {@code this.hurt(this.damageSources().playerAttack(player), 999.0F)}
 *    （死亡序列，{@code UnderworldKnightEntity.java:1198}）—— 一旦 {@code player_attack} 进了本 tag，
 *    我们的 {@code hurt} 注入会在这条自我调用里再次介入，属于设计外的递归面。
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
     * 并在"真的打掉一层"时扣 1 层免疫层数 + 播破防反馈（只扣 1 层，不强制倒地）。
     */
    public static final TagKey<DamageType> UNDERWORLD_KNIGHT_GUARD_BREAK = TagKey.create(
            Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "underworld_knight_guard_break"));

    private ModDamageTypeTags() {}
}
