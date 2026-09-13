package com.zonlong.beloong.item.essence;

/**
 * 「元素魔源」八件套的元素种类。
 * <p>
 * 每个值的 {@link #id} 同时是物品注册名的一部分（{@code beloong:<id>_essence}）与
 * 英文显示名（{@code Metal Essence} 等，用户要求英文名与物品 ID 等同）。
 * <p>
 * tooltip 的代入词（「自然界的<b>X</b>元素凝聚成的魔源」中的 X）走独立翻译键
 * {@code element.beloong.<id>}：中文侧「金」需要代入「金属」而不是「金」，
 * 所以代入词不能直接从 {@link #id} 拼出来。
 *
 * @see ElementEssenceItem
 */
public enum ElementType {

    /** 金（五行之金，对应 {@code metal} 而非 {@code gold}）。 */
    METAL("metal"),
    /** 木。 */
    WOOD("wood"),
    /** 水。 */
    WATER("water"),
    /** 火。 */
    FIRE("fire"),
    /** 土。 */
    EARTH("earth"),
    /** 冰。 */
    ICE("ice"),
    /** 风。 */
    WIND("wind"),
    /** 雷。 */
    THUNDER("thunder");

    /** 物品名与 tooltip 代入词的翻译键前缀。 */
    private static final String ITEM_PREFIX = "item.beloong.";

    /** 元素代入词的翻译键前缀。 */
    private static final String ELEMENT_PREFIX = "element.beloong.";

    /** 物品 ID 后缀与英文名，例如 {@code metal}。 */
    private final String id;

    ElementType(String id) {
        this.id = id;
    }

    /** @return 物品 ID 后缀与英文名，例如 {@code metal} */
    public String id() {
        return this.id;
    }

    /** @return 物品翻译键，例如 {@code item.beloong.metal_essence} */
    public String itemTranslationKey() {
        return ITEM_PREFIX + this.id + "_essence";
    }

    /** @return tooltip 代入词翻译键，例如 {@code element.beloong.metal} */
    public String tooltipNameKey() {
        return ELEMENT_PREFIX + this.id;
    }
}
