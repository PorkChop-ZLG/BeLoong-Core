package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * 本模组用到的<b>实体类型 tag</b> 常量。
 *
 * <p>目前只有一个：{@link #HUNTER_ALLIES} —— 「龙之生存猎人应当视为同伴的生物」。
 * 全部内容都在一个文件里（{@code data/beloong/tags/entity_type/hunter_allies.json}）：
 * 原版那部分用一条 tag 引用带进来，袭击增强那部分直接列 id。
 *
 * <p><b>⚠️ 袭击增强的 4 个 id 必须写 {@code "required": false}</b>：本模组把「袭击增强」
 * 声明为<b>可选</b>依赖（{@code build.gradle} 用 {@code compileOnly} + {@code localRuntime}，
 * {@code mods.toml} 写 {@code type="optional"}）。原版 {@code TagLoader} 对缺失的 <b>required</b>
 * 条目会直接失败 —— 字节码里的报错文案是
 * {@code "Couldn't load tag {} as it is missing following references: {}"} ——
 * 那会让<b>没装袭击增强的整合包整个加载不了</b>。写成 {@code required: false} 后，
 * 该模组缺席时这几条只是被跳过。这不是洁癖，是必须。
 *
 * <p><b>为什么另开一个 tag，而不是复用龙之生存的 {@code #dragonsurvival:hunter_faction}</b>：
 * 那个 tag 是<b>阵营身份</b>标记，只服务「诅咒·仁慈 / 猎人预兆」体系
 * （DS 源码注释即 {@code // Used in 'curse_of_kindness' enchantment}），被三处消费：
 * {@code ProjectileHandler:21,26}（投射物友伤豁免）、{@code HunterOmenHandler:53}
 * （玩家击杀该阵营 → 猎人预兆）、{@code HunterOmenHandler:185-205}
 * （玩家<b>攻击</b>该阵营 → 伤害倍率 + 获得 30 秒猎人预兆）。
 * 往它里面加袭击者会造成两个后果：① 对目标选择<b>毫无作用</b>（它不参与目标选择）；
 * ② 玩家打一下掠夺者就吃 30 秒猎人预兆，猎人转而追杀玩家 —— 打袭击时几乎必然触发。
 * 所以盟友名单必须自己开一份。
 *
 * <p>想让整合包/其他模组「追加」盟友时，文件<b>必须写在 {@code beloong} 命名空间下</b>
 * （tag 文件的命名空间 = tag ID 的命名空间，不是整合包自己的），用 {@code "replace": false} 合并；
 * 彻底替换则用 {@code "replace": true}，或用 KubeJS 的 {@code ServerEvents.tags('entity_type', ...)} 增删。
 *
 * <p><b>静默失败面</b>：tag 缺失或为空时<b>不会崩</b>，只是猎人恢复攻击名单里的生物
 * （{@code Holder#is} 对未绑定的 tag 返回 {@code false}）。
 */
public final class ModEntityTypeTags {

    /**
     * 会被龙之生存猎人<b>视为同伴</b>的生物。
     *
     * <p>只有 {@code dragonsurvival} 的猎人（{@code Hunter} 的 5 个子类）会消费它；
     * 见 {@code RaiderHunterAllyMixin} 里对「为什么不能按 {@code hunter_faction} 判定」的说明
     * （村民 / 铁傀儡也在那个 tag 里，若按它判定会连铁傀儡一起「缴械」）。
     *
     * <p>袭击增强的 {@code player_blimp} <b>不在此列</b>：它 {@code extends FDVehicle}，
     * 是玩家载具而不是袭击者。
     */
    public static final TagKey<EntityType<?>> HUNTER_ALLIES = TagKey.create(
            Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "hunter_allies"));

    private ModEntityTypeTags() {}
}
