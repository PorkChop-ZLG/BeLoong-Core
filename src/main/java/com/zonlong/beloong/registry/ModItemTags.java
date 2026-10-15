package com.zonlong.beloong.registry;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 本模组用到的<b>物品 tag</b> 常量。
 * <p>
 * 数据包文件都写在 {@code data/beloong/tags/item/} 下：
 * <ul>
 *   <li>{@link #HELL_GATE_KEYS} —— {@code hell_gate_keys.json}（默认只含铁魔法的骸骨钥匙），
 *       <b>参与判定</b>：门开不开就看它。</li>
 *   <li>{@link #ESSENCES} —— {@code essences.json}（十件元素魔源），
 *       ⚠️ <b>当前不参与任何判定</b>，见下。</li>
 * </ul>
 *
 * <h3>⚠️ {@link #ESSENCES} 目前是"声明性"的，不要误以为它在生效</h3>
 *
 * 「这件物品是不是魔源」的判定走的是
 * {@code stack.getItem() instanceof com.zonlong.beloong.item.effect.EssenceItem}（精确到类），
 * <b>不读这个 tag</b>。本 tag 的存在只是为了给整合包留一个可引用的面
 * （配方、战利品表、KubeJS 等需要按"魔源这一类"批量操作时用得上）。
 * <p>
 * ⇒ 若日后要改成<b>按 tag 判定</b>，那是<b>行为变更</b>（判定对象就不再由代码决定，而由数据包决定），
 * 必须先在设计文档登记新 rev 再动手，不要顺手改。详见
 * {@code docs/plans/2026-10-15-essence-skill-storage-design.md} 的 D10 / D11。
 *
 * <h3>为什么用 tag 而不是写死物品</h3>
 *
 * 灾变把封印之门的钥匙写死成 {@code cataclysm:strange_key}，整合包改不了；
 * 换成 tag 之后，整合包/其他模组用数据包就能增删（因而天然支持多种钥匙，也支持
 * {@code "#其他:tag"} 形式的嵌套 tag），不必改代码或配置，且 {@code /reload} 即时生效。
 * 与本模组已有的 {@link ModBlockTags#LOONG_PALACE_PORTAL_FRAME}（龙宫门框架方块）同类做法。
 * <p>
 * 想让整合包「追加」条目时，文件<b>必须写在 {@code beloong} 命名空间下</b>
 * （tag 文件的命名空间 = tag ID 的命名空间，不是整合包自己的）：
 * {@code data/beloong/tags/item/<tag>.json}，用 {@code "replace": false} 合并；
 * 彻底替换则用 {@code "replace": true}，或用 KubeJS 的 {@code ServerEvents.tags('item', ...)} 增删。
 * <p>
 * 注意：tag 缺失或为空时**不会崩**，只是「谁都开不了门」（{@code Holder#is} 对未绑定的 tag 返回 {@code false}）。
 * 这条静默失败面由 {@link com.zonlong.beloong.block.HellGateKeyWatcher} 在加载期告警兜住。
 */
public final class ModItemTags {

    /** 可以开启地狱之门的物品（默认：铁魔法骸骨钥匙）。 */
    public static final TagKey<Item> HELL_GATE_KEYS = TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "hell_gate_keys"));

    /**
     * 十件元素魔源（{@code beloong:essences}）。
     * <p>
     * ⚠️ <b>当前不参与任何判定</b> —— 「是不是魔源」由 {@code instanceof EssenceItem} 决定。详见类注释。
     */
    public static final TagKey<Item> ESSENCES = TagKey.create(
            Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "essences"));

    private ModItemTags() {}
}
