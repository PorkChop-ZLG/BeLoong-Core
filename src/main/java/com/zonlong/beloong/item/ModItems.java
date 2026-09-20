package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.item.effect.AmplificationCharmEffect;
import com.zonlong.beloong.item.effect.DawnLightEffect;
import com.zonlong.beloong.item.effect.EternalPorkchopEffect;
import com.zonlong.beloong.registry.ModBlocks;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 化龙核心模组的物品注册中心。
 * <p>
 * 使用 NeoForge 的 {@link DeferredRegister.Items} 机制进行懒加载注册。
 * <p>
 * <b>天灾传送门相关物品：</b>
 * <ul>
 *   <li>{@link #DISASTER_PORTAL_FRAME} — 天灾传送门框架的 {@link BlockItem}，
 *       放置后生成 {@link com.zonlong.beloong.block.DisasterPortalFrame} 方块</li>
 *   <li>{@link #DISASTER_PORTAL_BLOCK} — 天灾传送门方块的 {@link BlockItem}，
 *       主要用于配置模板中的结构方块引用</li>
 * </ul>
 * <p>
 * 注：传送门框架和传送门方块都在创造模式物品栏的"化龙"标签页中可见，
 * 通过 {@link ModCreativeModeTabs#BELOONG_TAB} 配置。
 *
 * @see com.zonlong.beloong.registry.ModBlocks
 * @see ModCreativeModeTabs
 */
public class ModItems {

    /** 物品延迟注册器 */
    public static final DeferredRegister.Items Items =
            DeferredRegister.createItems(BeLoongCore.MODID);

    /** 化龙图标 */
    public static final DeferredItem<Item> BELOONG_LOGO =
            Items.register("beloong_logo", () -> new Item(new Item.Properties()));

    /** 永恒猪排 */
    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            Items.register("eternal_porkchop", EternalPorkchopEffect::new);

    /** 放大护符 */
    public static final DeferredItem<Item> AMPLIFICATION_CHARM =
            Items.register("amplification_charm", AmplificationCharmEffect::new);

    /** 黎明曙光 */
    public static final DeferredItem<Item> DAWN_LIGHT =
            Items.register("dawn_light", DawnLightEffect::new);

    // ==================== 元素魔源十件套 ====================
    // 只是十件普通物品，就地注册（沿用本类既有的"一个物品一行"写法），不额外建类。
    // 每个元素一个文件、或一个枚举、或一个参数化基类，用来省下那几行都是多余的抽象。
    // 十件的属性完全相同，差异只有 tooltip 键，因此统一走下面这个私有工厂。
    // 声明顺序 = 创造模式页签的展示顺序：五行 + 风雷冰 + 光暗。

    /** 元素魔源（金）。 */
    public static final DeferredItem<Item> METAL_ESSENCE =
            Items.register("metal_essence", essenceSupplier("item.beloong.metal_essence.tooltip"));

    /** 元素魔源（木）。 */
    public static final DeferredItem<Item> WOOD_ESSENCE =
            Items.register("wood_essence", essenceSupplier("item.beloong.wood_essence.tooltip"));

    /** 元素魔源（水）。 */
    public static final DeferredItem<Item> WATER_ESSENCE =
            Items.register("water_essence", essenceSupplier("item.beloong.water_essence.tooltip"));

    /** 元素魔源（火）。 */
    public static final DeferredItem<Item> FIRE_ESSENCE =
            Items.register("fire_essence", essenceSupplier("item.beloong.fire_essence.tooltip"));

    /** 元素魔源（土）。 */
    public static final DeferredItem<Item> EARTH_ESSENCE =
            Items.register("earth_essence", essenceSupplier("item.beloong.earth_essence.tooltip"));

    /** 元素魔源（风）。 */
    public static final DeferredItem<Item> WIND_ESSENCE =
            Items.register("wind_essence", essenceSupplier("item.beloong.wind_essence.tooltip"));

    /** 元素魔源（雷）。 */
    public static final DeferredItem<Item> THUNDER_ESSENCE =
            Items.register("thunder_essence", essenceSupplier("item.beloong.thunder_essence.tooltip"));

    /** 元素魔源（冰）。 */
    public static final DeferredItem<Item> ICE_ESSENCE =
            Items.register("ice_essence", essenceSupplier("item.beloong.ice_essence.tooltip"));

    /** 元素魔源（光）。 */
    public static final DeferredItem<Item> LIGHT_ESSENCE =
            Items.register("light_essence", essenceSupplier("item.beloong.light_essence.tooltip"));

    /** 元素魔源（暗）。 */
    public static final DeferredItem<Item> DARK_ESSENCE =
            Items.register("dark_essence", essenceSupplier("item.beloong.dark_essence.tooltip"));

    /**
     * 构造一件元素魔源：唯一的行为变化是 tooltip，文案在语言文件里。
     *
     * @param tooltipKey 该物品的 tooltip 翻译键
     */
    private static Supplier<Item> essenceSupplier(String tooltipKey) {
        return () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)) {
            @Override
            public void appendHoverText(ItemStack stack, TooltipContext context,
                                        List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
                tooltipComponents.add(Component.translatable(tooltipKey));
            }
        };
    }

    /**
     * 天灾传送门框架的 BlockItem。
     * 物品 ID：{@code beloong:disaster_portal_frame}
     */
    public static final DeferredItem<BlockItem> DISASTER_PORTAL_FRAME =
            Items.register("disaster_portal_frame",
                    () -> new BlockItem(ModBlocks.DISASTER_PORTAL_FRAME.get(), new Item.Properties()));

    /**
     * 天灾传送门方块的 BlockItem。
     * 物品 ID：{@code beloong:disaster_portal_block}
     */
    public static final DeferredItem<BlockItem> DISASTER_PORTAL_BLOCK =
            Items.register("disaster_portal_block",
                    () -> new BlockItem(ModBlocks.DISASTER_PORTAL_BLOCK.get(), new Item.Properties()));

    /** Red-orange-yellow decorative board. */
    public static final DeferredItem<BlockItem> RED_YELLOW_BOARD =
            Items.register("red_yellow_board",
                    () -> new BlockItem(ModBlocks.RED_YELLOW_BOARD.get(), new Item.Properties()));

    /**
     * 龙宫传送门方块的 BlockItem。
     * <p>
     * 物品 ID：{@code beloong:loong_palace_portal}。放下去只是一块"门方块"（玩家正常玩法里
     * 用荧石框架注水点亮，不需要这个物品）；它的主要用途是创造模式搭建与结构引用。
     */
    public static final DeferredItem<BlockItem> LOONG_PALACE_PORTAL =
            Items.register("loong_palace_portal",
                    () -> new BlockItem(ModBlocks.LOONG_PALACE_PORTAL.get(), new Item.Properties()));

    /**
     * 地狱之门的 BlockItem（灾变封印之门物品的移植件）。
     * <p>
     * 物品 ID 与方块同名：{@code beloong:hell_gate}（这样客户端会自动取
     * {@code models/item/hell_gate.json} 作物品模型）。属性照搬灾变：
     * {@code fireResistant()} + {@link Rarity#EPIC}。
     * <p>
     * 放下 1 格即由 {@link com.zonlong.beloong.block.HellGateBlock#setPlacedBy} 铺满 5×8 的整扇门。
     */
    public static final DeferredItem<BlockItem> HELL_GATE =
            Items.register("hell_gate",
                    () -> new BlockItem(ModBlocks.HELL_GATE.get(),
                            new Item.Properties().fireResistant().rarity(Rarity.EPIC)));

    /** 将物品注册到 Mod 事件总线 */
    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
