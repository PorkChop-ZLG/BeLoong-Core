package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.item.custom.EternalPorkchopItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(BeLoongCore.MODID);

    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            ITEMS.register("eternal_porkchop",
                    EternalPorkchopItem::new
            );

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
