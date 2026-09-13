package com.zonlong.beloong.item.essence;

import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;

/**
 * 「元素魔源」物品。
 * <p>
 * 八种元素共用这一个类：它们的行为完全一致（无任何效果、仅一条 tooltip），
 * 差异只来自 {@link ElementType} 提供的 tooltip 代入词，以及各自的动态贴图。
 * 因此不按元素拆成 8 个类——项目 {@code item/effect/} 下每个物品一个类，
 * 是因为那几件各有独立行为。
 * <p>
 * 贴图为 12 帧 16×16 竖排帧条，由 {@code tools/generate_element_essence_textures.py} 生成。
 *
 * @see ElementType
 */
public class ElementEssenceItem extends Item {

    /**
     * tooltip 模板翻译键。
     * <p>
     * 模板含<b>一个</b> {@code %s}，即元素代入词：
     * <ul>
     *   <li>中文：{@code 自然界的%s元素凝聚成的魔源，可以为龙的成长提供魔力。}</li>
     *   <li>英文：{@code An Elemental Essence condensed from the %s element; it provides mana for a dragon's growth.}</li>
     * </ul>
     * <b>不要把物品名也作为参数传入。</b>{@code %s} 是按出现顺序取参的（
     * {@code TranslatableContents.decomposeTemplate}：无显式序号时 {@code i++} 递增取
     * {@code args[i]}），中文模板的第一个 {@code %s} 位于「自然界的…元素」中间，传物品名会被
     * 渲染成「自然界的金魔源元素…」。两种语言需要的词不同，而位置相同，因此模板只接受元素词一个参数；
     * 英文侧的物品名改用固定的 {@code Elemental Essence} 表述（八种元素的英文词首音均为元音，
     * {@code An} 一律成立），同时避免 "Metal Essence … the metal element" 在一句里重复 metal。
     */
    public static final String TOOLTIP_KEY = "item.beloong.element_essence.tooltip";

    private final ElementType element;

    /**
     * @param element 该物品对应的元素种类
     */
    public ElementEssenceItem(ElementType element) {
        super(new Item.Properties().rarity(Rarity.UNCOMMON));
        this.element = Objects.requireNonNull(element, "element");
    }

    /** @return 该物品对应的元素种类 */
    public ElementType element() {
        return this.element;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        tooltipComponents.add(Component.translatable(
                TOOLTIP_KEY,
                Component.translatable(this.element.tooltipNameKey())));
    }
}
