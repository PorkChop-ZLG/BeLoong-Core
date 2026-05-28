package com.zonlong.beloong;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.registry.ModAttributes;
import com.zonlong.beloong.transport.DimensionTransportHandler;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import net.neoforged.neoforge.event.server.ServerStartingEvent;
import org.slf4j.Logger;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BeLoongCore.MODID)
public class BeLoongCore {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "beloong";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BeLoongCore(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModCreativeModeTabs.register(modEventBus);
        ModAttributes.REGISTRY.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BeLoongCore) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new DimensionTransportHandler());

        // 注册模组配置
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }


    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
