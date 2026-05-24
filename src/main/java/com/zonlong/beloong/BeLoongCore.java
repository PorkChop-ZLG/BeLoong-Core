package com.zonlong.beloong;

import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(BeLoongCore.MODID)
public class BeLoongCore {
    public static final String MODID = "beloong";

    public BeLoongCore(IEventBus modEventBus, ModContainer modContainer) {
        ModItems.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
