package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.item.effect.EternalPorkchopEffect;
import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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

    /** 模组图标物品 */
    public static final DeferredItem<Item> BELOONG_LOGO =
            Items.register("beloong_logo", () -> new Item(new Item.Properties()));

    /** 永恒猪排（自定义食物效果） */
    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            Items.register("eternal_porkchop",
                    EternalPorkchopEffect::new
            );

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

    /** 将物品注册到 Mod 事件总线 */
    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
