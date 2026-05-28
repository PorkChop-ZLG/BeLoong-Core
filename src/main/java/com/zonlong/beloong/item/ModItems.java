package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import com.zonlong.beloong.item.effect.EternalPorkchopEffect;
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

    public static void register(IEventBus eventBus) {
        Items.register(eventBus);
    }
}
