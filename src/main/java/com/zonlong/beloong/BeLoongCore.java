package com.zonlong.beloong;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.registry.ModAttributes;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModMobEffects;
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

/**
 * 化龙核心（BeLoong Core）模组主类。
 * <p>
 * 模组 ID：{@value #MODID}。
 * 这是 NeoForge 加载该模组的入口点，构造函数按以下顺序初始化所有子系统：
 * <ol>
 *   <li>物品注册（{@link ModItems}）</li>
 *   <li>方块和 BlockEntity 注册（{@link ModBlocks}）— 包含天灾传送门相关方块</li>
 *   <li>创造模式标签页注册（{@link ModCreativeModeTabs}）</li>
 *   <li>属性注册（{@link ModAttributes}）</li>
 *   <li>药水效果注册（{@link ModMobEffects}）</li>
 *   <li>事件处理器注册（{@link DimensionTransportHandler}）</li>
 *   <li>配置文件注册（客户端/通用/服务端 三个配置）</li>
 * </ol>
 *
 * @see BeLoongCoreClient 客户端初始化（渲染器注册）
 * @see Config 配置文件
 */
@Mod(BeLoongCore.MODID)
public class BeLoongCore {

    /** 模组 ID，全局唯一标识符。在 {@code neoforge.mods.toml} 中定义。 */
    public static final String MODID = "beloong";

    /** SLF4J 日志记录器。日志输出到 {@code run/logs/latest.log}。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 模组构造函数。
     * FML 自动注入 {@link IEventBus} 和 {@link ModContainer} 参数。
     *
     * @param modEventBus  Mod 事件总线，用于注册方块、物品、配置等
     * @param modContainer 模组容器，用于注册配置文件
     */
    public BeLoongCore(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // === 注册阶段 ===
        ModItems.register(modEventBus);              // 物品（含天灾传送门框架和方块的 BlockItem）
        ModBlocks.register(modEventBus);             // 方块 + BlockEntity（天灾传送门）
        ModCreativeModeTabs.register(modEventBus);   // 创造模式标签页
        ModAttributes.REGISTRY.register(modEventBus);
        ModMobEffects.REGISTRY.register(modEventBus);

        // === 事件处理器 ===
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new DimensionTransportHandler());

        // === 配置文件 ===
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        // 服务端配置包含天灾传送门配置节（disaster_portal）
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
    }

    /** FML 通用设置（双端都执行）。 */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM COMMON SETUP");
    }

    /** 服务端启动时触发。 */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }
}
