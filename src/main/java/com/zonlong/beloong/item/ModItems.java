package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items Items =
            DeferredRegister.createItems(BeLoongCore.MODID);

    public static final DeferredItem<Item> EVERLASTING_PORKCHOP =
            Items.register("everlasting_porkchop", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> ETERNAL_PORKCHOP =
            Items.register("eternal_porkchop", () -> new Item(new Item.Properties()));

    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
