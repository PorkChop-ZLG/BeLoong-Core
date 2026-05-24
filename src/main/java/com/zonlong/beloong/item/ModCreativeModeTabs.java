package com.zonlong.beloong.item;

import com.zonlong.beloong.BeLoongCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BeLoongCore.MODID);

    public static final Supplier<CreativeModeTab> BELOONG_TAB =
            CREATIVE_MODE_TABS.register("beloong_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.ETERNAL_PORKCHOP.get()))
                    .title(Component.translatable("itemGroup.beloong_tab"))
                    .displayItems((itemDisplayParameters, output) -> {
                        output.accept(ModItems.ETERNAL_PORKCHOP);
                    }).build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
