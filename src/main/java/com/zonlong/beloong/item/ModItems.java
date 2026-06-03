package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.item.effect.EternalPorkchopEffect;
import com.zonlong.beloong.registry.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items Items =
            DeferredRegister.createItems(BeLoongCore.MODID);

    public static final DeferredItem<Item> BELOONG_LOGO =
            Items.register("beloong_logo", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            Items.register("eternal_porkchop",
                    EternalPorkchopEffect::new
            );

    public static final DeferredItem<BlockItem> DISASTER_PORTAL_FRAME =
            Items.register("disaster_portal_frame",
                    () -> new BlockItem(ModBlocks.DISASTER_PORTAL_FRAME.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> DISASTER_PORTAL_BLOCK =
            Items.register("disaster_portal_block",
                    () -> new BlockItem(ModBlocks.DISASTER_PORTAL_BLOCK.get(), new Item.Properties()));

    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
