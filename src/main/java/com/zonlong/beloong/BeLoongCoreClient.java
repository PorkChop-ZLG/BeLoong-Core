package com.zonlong.beloong;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.zonlong.beloong.client.DisasterPortalRenderer;
import com.zonlong.beloong.client.LoongPalaceSkyTickHandler;
import com.zonlong.beloong.client.TornadoRenderer;
import com.zonlong.beloong.client.model.TornadoModel;
import com.zonlong.beloong.client.sky.LoongPalaceSkyEffects;
import com.zonlong.beloong.registry.ModBlocks;
import com.zonlong.beloong.registry.ModEntities;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

/**
 * 化龙核心模组的客户端初始化类。
 * <p>
 * 仅在客户端（物理客户端或单人游戏的内置服务端客户端）加载，
 * 专用服务器不会加载此类 —— 由 {@code dist = Dist.CLIENT} 保证。
 * <p>
 * <b>客户端特有注册：</b>
 * <ul>
 *   <li>配置文件 GUI（NeoForge 模组菜单集成）</li>
 *   <li>{@link DisasterPortalRenderer} — 天灾传送门方块的 BlockEntity 渲染器绑定</li>
 * </ul>
 *
 * @see BeLoongCore 主模组类
 * @see DisasterPortalRenderer 传送门自定义渲染器
 */
@Mod(value = BeLoongCore.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BeLoongCore.MODID, value = Dist.CLIENT)
public class BeLoongCoreClient {

    /** 配置 GUI 扩展点注册。允许在 NeoForge 模组菜单中直接编辑配置。 */
    public BeLoongCoreClient(IEventBus modEventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        NeoForge.EVENT_BUS.register(new LoongPalaceSkyTickHandler());
    }

    /** 天灾传送门自定义着色器实例。由 RegisterShadersEvent 回调设置。 */
    @Nullable
    public static ShaderInstance disasterPortalShader;

    /**
     * 注册 BlockEntity 渲染器（BER）。
     * <p>
     * 将自定义的 {@link DisasterPortalRenderer} 绑定到
     * {@link ModBlocks#DISASTER_PORTAL_BLOCK_ENTITY}，
     * 实现天灾传送门方块的旋转隧道视觉效果。
     * <p>
     * 该渲染器是原版 {@code TheEndPortalRenderer} 的重新实现，
     * 使用自定义着色器（{@code rendertype_disaster_portal}）和自定义贴图，
     * 通过 {@link #onRegisterShaders(RegisterShadersEvent)} 注册。
     * 由于原版渲染器内部将 BlockEntity 硬转型为 {@code TheEndPortalBlockEntity}，
     * 无法直接复用，因此需要自定义实现。
     */
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlocks.DISASTER_PORTAL_BLOCK_ENTITY.get(),
                DisasterPortalRenderer::new);
        event.registerEntityRenderer(ModEntities.TORNADO.get(), TornadoRenderer::new);
    }

    /**
     * 注册实体模型层。
     * <p>
     * 必须在 {@link #registerRenderers} 之前或同时完成，否则
     * {@code context.bakeLayer(TornadoModel.LAYER)} 会抛
     * {@code IllegalArgumentException: Model layer ... not registered}。
     */
    @SubscribeEvent
    static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(TornadoModel.LAYER, TornadoModel::createBodyLayer);
    }

    @SubscribeEvent
    static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "rendertype_disaster_portal"),
                            DefaultVertexFormat.POSITION),
                    shader -> disasterPortalShader = shader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load disaster portal shader", e);
        }
    }

    @SubscribeEvent
    static void registerDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(
                ResourceLocation.fromNamespaceAndPath(BeLoongCore.MODID, "loong_palace"),
                new LoongPalaceSkyEffects());
    }


}
