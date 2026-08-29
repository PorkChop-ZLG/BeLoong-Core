package com.zonlong.beloong;

import com.mojang.logging.LogUtils;
import com.zonlong.beloong.compat.betterendisland.DragonSummonHandler;
import com.zonlong.beloong.compat.ftbchunks.LoongPalaceProtectionHandler;
import com.zonlong.beloong.compat.lockdown.LockdownTemplateMigration;
import com.zonlong.beloong.fluid.BeloongWaterContactHandler;
import com.zonlong.beloong.fluid.BeloongWaterRegionLoader;
import com.zonlong.beloong.item.ModCreativeModeTabs;
import com.zonlong.beloong.item.ModItems;
import com.zonlong.beloong.network.TreasureSyncPayload;
import com.zonlong.beloong.registry.ModAttributes;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModMobEffects;
import com.zonlong.beloong.registry.ManaLossHandler;
import com.zonlong.beloong.structure.StructureEffectHandler;
import com.zonlong.beloong.structure.StructureEffectLoader;
import com.zonlong.beloong.transport.DimensionTransportHandler;
import com.zonlong.beloong.treasure.TreasureGrowthLoader;
import com.zonlong.beloong.waystoneplacement.WaystonePlacementHandler;
import com.zonlong.beloong.waystoneplacement.WaystonePlacementLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

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
        ModItems.register(modEventBus);              // 物品
        ModBlocks.register(modEventBus);             // 方块 + BlockEntity
        ModCreativeModeTabs.register(modEventBus);   // 创造模式标签页
        ModAttributes.REGISTRY.register(modEventBus);
        ModMobEffects.REGISTRY.register(modEventBus);

        // === 事件处理器 ===
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new DimensionTransportHandler());
        NeoForge.EVENT_BUS.register(new StructureEffectHandler());
        NeoForge.EVENT_BUS.register(new ManaLossHandler());
        NeoForge.EVENT_BUS.register(new BeloongWaterContactHandler());
        NeoForge.EVENT_BUS.register(new WaystonePlacementHandler());
        if (ModList.get().isLoaded("lockdown")) {
            NeoForge.EVENT_BUS.register(new LockdownTemplateMigration());
        }
        if (ModList.get().isLoaded("ftbchunks")) {
            LoongPalaceProtectionHandler.register();
        }
        if (ModList.get().isLoaded("betterendisland")) {
            NeoForge.EVENT_BUS.register(new DragonSummonHandler());
        }

        // === 配置文件 ===
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);

        // === 网络包注册 ===
        modEventBus.addListener((RegisterPayloadHandlersEvent evt) ->
                evt.registrar(MODID).playToClient(
                        TreasureSyncPayload.TYPE,
                        TreasureSyncPayload.STREAM_CODEC,
                        TreasureSyncPayload::handleClient));
    }

    /** FML 通用设置（双端都执行）。 */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("BeLoong Launch!");
    }

    /** 注册服务器资源重载监听器。 */
    @SubscribeEvent
    public void addServerReloadListeners(AddReloadListenerEvent event) {
        event.addListener(TreasureGrowthLoader.INSTANCE);
        event.addListener(StructureEffectLoader.INSTANCE);
        event.addListener(BeloongWaterRegionLoader.INSTANCE);
        event.addListener(WaystonePlacementLoader.INSTANCE);
    }

    /** 服务端启动时触发。 */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("BeLoong Launch!");
    }

    /**
     * 玩家登录时将全量财宝条目同步至客户端。
     * <p>
     * 仅在登录时同步一次（非数据包重载），避免频繁网络传输。
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        List<TreasureSyncPayload.SyncedEntry> entries = new ArrayList<>();
        for (var entry : TreasureGrowthLoader.INSTANCE.getDragonEntries().entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString();
            entries.add(new TreasureSyncPayload.SyncedEntry(
                    id, entry.getValue().value(), entry.getValue().limit(), true));
        }
        for (var entry : TreasureGrowthLoader.INSTANCE.getOtherEntries().entrySet()) {
            String id = BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString();
            entries.add(new TreasureSyncPayload.SyncedEntry(
                    id, entry.getValue().value(), entry.getValue().limit(), false));
        }

        PacketDistributor.sendToPlayer(player, new TreasureSyncPayload(entries));
    }
}
